package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись журнала ЯДРА в файл, рядом с журналом приложения.
 *
 * Зачем. Ядро не пишет в logcat вообще: свои строки оно отдаёт по отдельному каналу,
 * который до сих пор читал только экран «Журнал», пока он открыт. Из-за этого в суточном
 * архиве были решения авто-режима и старты сервиса, но не было ни строки о том, что
 * ядро сделало с конкретным соединением. 05.09.2026 жалобу «два сайта не открываются»
 * удалось закрыть только потому, что экран журнала открыли руками и увидели там
 * `dial rmnet_data0: connect: network is unreachable` — ответ, которого в архиве не было.
 *
 * Свой файл, а не общий с [AppLog]. Ядро на нынешней подробности пишет строку на каждое
 * соединение, и в общем файле оно за сутки вытеснило бы решения авто-режима — а они
 * ровно в том же разборе доказали половину. Отдельная ротация оставляет и то, и другое.
 *
 * Отправку менять не пришлось: [LogUploadWork] забирает все файлы каталога.
 */
object CoreLog {
    private const val TAG = "KelevraCoreLog"
    const val BASE_NAME = "kelevra-core.log"

    /** Потолок: 3 × 2 МБ. Журналу приложения оставлено его прежние 14 МБ. */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 3

    /** Цвета в строках ядра нужны экрану, файлу они мешают читаться. */
    private val ansi = Regex("\\[[0-9;]*m")

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var rotator: LogRotator? = null
    private var client: CommandClient? = null
    private var scope: CoroutineScope? = null

    /**
     * Сводка за окно: сколько соединений, сколько отказов и каких.
     *
     * Зачем. Разбор 10.09.2026: журнал ядра при работе заполняется за шесть минут и
     * вытесняет собой всю историю, а жалоба приходит через часы. Строка-сводка на
     * каждые десять минут весит копейки и переживает любую ротацию, поэтому «в тот
     * час у человека падало каждое пятое соединение» остаётся видно даже когда самих
     * строк уже нет.
     *
     * И она же служит поводом отправки: перевалило за порог — журнал уезжает сразу, не
     * дожидаясь ночи, и к моменту жалобы данные уже у меня.
     */
    private const val OKNO_SVODKI_MS = 10 * 60 * 1000L

    /** С какой доли отказов окно считается бедой и просит отправку. */
    private const val DOLYA_TREVOGI = 15

    /** Реже одного раза в час по тревоге не шлём: journal и так уедет ночью. */
    private const val TREVOGA_NE_CHASHCHE_MS = 60 * 60 * 1000L

    @Volatile
    private var oknoNachalo = 0L

    @Volatile
    private var oknoSoedineniy = 0

    @Volatile
    private var oknoOtkazov = 0

    private val oknoKody = LinkedHashMap<String, Int>()

    @Volatile
    private var trevogaBylaV = 0L

    /** Коды причин отказа, по которым потом ставится диагноз на сервере. */
    private val PRICHINY = listOf(
        "set_nedostupna" to "network is unreachable",
        "marshruta_net" to "no route to host",
        "taymaut" to "i/o timeout",
        "imya_ne_reshilos" to "no such host",
        "otkaz_soedineniya" to "connection refused",
        "sbros" to "connection reset",
    )

    private fun uchest(line: String) {
        val teper = System.currentTimeMillis()
        if (oknoNachalo == 0L) oknoNachalo = teper
        if (line.contains("inbound connection to")) oknoSoedineniy++
        if (line.contains("ERROR") && line.contains("open connection to")) {
            oknoOtkazov++
            val nizhnyaya = line.lowercase(Locale.US)
            val kod = PRICHINY.firstOrNull { nizhnyaya.contains(it.second) }?.first ?: "prochee"
            oknoKody[kod] = (oknoKody[kod] ?: 0) + 1
        }
        if (teper - oknoNachalo < OKNO_SVODKI_MS) return
        zakrytOkno(teper)
    }

    private fun zakrytOkno(teper: Long) {
        val soed = oknoSoedineniy
        val otk = oknoOtkazov
        val kody = oknoKody.entries.joinToString(",") { "${it.key}=${it.value}" }
        oknoNachalo = teper
        oknoSoedineniy = 0
        oknoOtkazov = 0
        oknoKody.clear()
        if (soed == 0 && otk == 0) return
        val dolya = if (soed > 0) otk * 100 / soed else 100
        runCatching {
            rotator?.append(
                "${stampFormat.format(Date(teper))} -- сводка за 10 мин: соединений $soed, " +
                    "отказов $otk ($dolya%)${if (kody.isEmpty()) "" else ", причины $kody"}\n",
            )
        }
        if (dolya >= DOLYA_TREVOGI && otk >= 10 && teper - trevogaBylaV > TREVOGA_NE_CHASHCHE_MS) {
            trevogaBylaV = teper
            Log.w(TAG, "отказов $dolya% за окно — отправляю журнал, не дожидаясь ночи")
            LogUploadWork.otpravitSeychas()
        }
    }

    private val handler = object : CommandClient.Handler {
        override fun appendLogs(message: List<LogEntry>) {
            val target = rotator ?: return
            val stamp = stampFormat.format(Date())
            val text = buildString {
                for (entry in message) {
                    val line = ansi.replace(entry.message, "").trimEnd()
                    if (line.isEmpty()) continue
                    runCatching { uchest(line) }
                    append(stamp).append(' ').append(line).append('\n')
                }
            }
            runCatching { target.append(text) }
        }

        override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
            runCatching { rotator?.append("-- связь с ядром потеряна: $kind $message\n") }
        }
    }

    /** Поднимается вместе с ядром. Повторный вызов живую запись не трогает. */
    @Synchronized
    fun start() {
        if (client != null) return
        val started = runCatching {
            val folder = AppLog.dir(Application.application)
            if (!folder.isDirectory && !folder.mkdirs()) error("каталог журнала не создан: $folder")
            rotator = LogRotator(folder, BASE_NAME, MAX_FILE_BYTES, MAX_FILES)
            val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = own
            CommandClient(own, CommandClient.ConnectionType.Log, handler, localOnly = true)
                .also { it.connect() }
        }
        started.onSuccess {
            client = it
            Log.i(TAG, "журнал ядра пишется в $BASE_NAME")
        }.onFailure {
            Log.w(TAG, "журнал ядра не открылся: ${it.message}")
            rotator = null
            scope?.cancel()
            scope = null
        }
    }

    /** Гаснет вместе с ядром: без него канал всё равно молчит. */
    @Synchronized
    fun stop() {
        runCatching { client?.disconnect() }
        client = null
        runCatching { scope?.cancel() }
        scope = null
        runCatching { rotator?.close() }
        rotator = null
    }
}
