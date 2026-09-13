package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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

    /**
     * Короткое окно для шторма, рядом с десятиминутным.
     *
     * Десятиминутное окно ловит медленную деградацию, но шторм в него не помещается.
     * 12.09.2026 весь шторм уложился в два всплеска по десять секунд, до 1240 отказов
     * в секунду, а тревога поднялась через пять минут — уже после того, как автомат
     * сам переключился и всё починилось. Короткое окно ловит такое на первых секундах.
     */
    private const val BYSTRO_OKNO_MS = 30 * 1000L

    /** Сколько отказов в коротком окне считать штормом. Штатно их там единицы. */
    internal const val BYSTRO_POROG = 200

    /** И какая при этом доля: сотня отказов на десятках тысяч соединений — не шторм. */
    private const val BYSTRO_DOLYA = 50

    /** Реже одного раза в час по тревоге не шлём: journal и так уедет ночью. */
    private const val TREVOGA_NE_CHASHCHE_MS = 60 * 60 * 1000L

    @Volatile
    private var oknoNachalo = 0L

    @Volatile
    private var oknoSoedineniy = 0

    @Volatile
    private var oknoOtkazov = 0

    private val oknoKody = LinkedHashMap<String, Int>()

    /** Имена сайтов, по которым отказывали в этом окне: ось «сайт» для разбора. */
    private val oknoImena = LinkedHashMap<String, Int>()

    @Volatile
    private var trevogaBylaV = 0L

    /** Отказы в свой же сокс на петле: см. [SocksBreaker]. */
    private val predohranitel = SocksBreaker()

    @Volatile
    private var bystroNachalo = 0L

    @Volatile
    private var bystroSoed = 0

    @Volatile
    private var bystroOtkazov = 0

    /** Коды причин отказа, по которым потом ставится диагноз на сервере. */
    private val PRICHINY = listOf(
        "set_nedostupna" to "network is unreachable",
        "marshruta_net" to "no route to host",
        "taymaut" to "i/o timeout",
        "imya_ne_reshilos" to "no such host",
        "otkaz_soedineniya" to "connection refused",
        "sbros" to "connection reset",
    )

    /** Адрес назначения из строки отказа: «open connection to [2a02::1]:443» или «1.2.3.4:443». */
    private val RX_ADRES_OTKAZA =
        Regex("""open connection to \[?([0-9a-fA-F:.]+)]?:\d+""")

    /** Порт назначения из той же строки. */
    private val RX_PORT = Regex("""open connection to \[?[0-9a-fA-F:.]+]?:(\d+)""")

    /** Каким выходом шли: «using outbound/direct[direct]». */
    private val RX_VYHOD = Regex("""using outbound/([a-zA-Z0-9_.\-]+)""")

    private fun uchest(line: String) {
        val teper = System.currentTimeMillis()
        if (oknoNachalo == 0L) oknoNachalo = teper
        if (teper - bystroNachalo >= BYSTRO_OKNO_MS) {
            bystroNachalo = teper
            bystroSoed = 0
            bystroOtkazov = 0
        }
        if (line.contains("inbound connection to")) {
            oknoSoedineniy++
            bystroSoed++
        }
        if (line.contains("ERROR") && line.contains("open connection to")) {
            oknoOtkazov++
            bystroOtkazov++
            if (predohranitel.offer(line, teper)) {
                Log.w(TAG, "шторм отказов в локальный сокс — увожу выход с мёртвой комнаты")
                runCatching { Zapisi.perehod("predohranitel", "отказы в локальный сокс") }
                // Переключение выхода — вызов в командный сервер; поток журнала им не держим.
                thread(name = "socks-breaker", isDaemon = true) { AutoMode.roomLost("предохранитель") }
            }
            if (shtorm(bystroOtkazov, bystroSoed)) {
                podnyatTrevogu(teper, dolyaOtkazov(bystroOtkazov, bystroSoed), bystroOtkazov, "за ${BYSTRO_OKNO_MS / 1000} с")
            }
            val nizhnyaya = line.lowercase(Locale.US)
            val kod = PRICHINY.firstOrNull { nizhnyaya.contains(it.second) }?.first ?: "prochee"
            oknoKody[kod] = (oknoKody[kod] ?: 0) + 1
            // Имя сайта у ядра есть, а в строку отказа оно его не кладёт: там только
            // адрес. Без имени ось «сайт» в журнале телефона отсутствует вовсе, и
            // диагноз 10.09.2026 приходилось ставить обратным запросом руками.
            val adres = RX_ADRES_OTKAZA.find(line)?.groupValues?.getOrNull(1).orEmpty()
            if (adres.isNotEmpty()) {
                val imya = runCatching { ImenaSaytov.imya(adres) }.getOrDefault("")
                if (imya.isNotEmpty()) {
                    oknoImena[imya] = (oknoImena[imya] ?: 0) + 1
                    runCatching {
                        rotator?.append(
                            "${stampFormat.format(Date(teper))} -- отказ: $imya → $adres ($kod)\n",
                        )
                    }
                }
                // Тот же отказ — записью с полями. Разбор по ней не зависит от того,
                // какими словами ядро описало беду (см. Zapisi).
                val vyhod = RX_VYHOD.find(line)?.groupValues?.getOrNull(1).orEmpty()
                val port = RX_PORT.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                runCatching { Zapisi.otkaz(imya, adres, port, kod, vyhod, 0) }
            }
        }
        if (teper - oknoNachalo < OKNO_SVODKI_MS) return
        zakrytOkno(teper)
    }

    private fun zakrytOkno(teper: Long) {
        val soed = oknoSoedineniy
        val otk = oknoOtkazov
        val kody = oknoKody.entries.joinToString(",") { "${it.key}=${it.value}" }
        // Топ имён: пяти хватает, чтобы увидеть «ломается именно Яндекс», а не гадать.
        val topImen = oknoImena.entries.sortedByDescending { it.value }.take(5)
        val imena = topImen.joinToString(",") { "${it.key}=${it.value}" }
        // Копии для записи с полями: сами карты ниже очищаются под новое окно.
        val kodyDlyaZapisi = LinkedHashMap(oknoKody)
        val imenaDlyaZapisi = LinkedHashMap<String, Int>().apply {
            topImen.forEach { put(it.key, it.value) }
        }
        oknoNachalo = teper
        oknoSoedineniy = 0
        oknoOtkazov = 0
        oknoKody.clear()
        oknoImena.clear()
        if (soed == 0 && otk == 0) return
        val dolya = if (soed > 0) otk * 100 / soed else 100
        runCatching {
            rotator?.append(
                "${stampFormat.format(Date(teper))} -- сводка за 10 мин: соединений $soed, " +
                    "отказов $otk ($dolya%)${if (kody.isEmpty()) "" else ", причины $kody"}" +
                    "${if (imena.isEmpty()) "" else ", имена $imena"}\n",
            )
        }
        // Та же сводка записью с полями: по ней сервер считает норму, и разбирать
        // текстовую строку регулярным выражением ему больше не нужно.
        runCatching {
            Zapisi.svodka(
                okno = (OKNO_SVODKI_MS / 1000L).toInt(),
                soed = soed,
                otkazy = otk,
                kody = kodyDlyaZapisi,
                imena = imenaDlyaZapisi,
            )
        }
        if (dolya >= DOLYA_TREVOGI && otk >= 10) podnyatTrevogu(teper, dolya, otk, "за окно")
    }

    /** Доля отказов в процентах; без соединений считаем, что отказывает всё. */
    internal fun dolyaOtkazov(otkazov: Int, soed: Int): Int = if (soed > 0) otkazov * 100 / soed else 100

    /**
     * Шторм ли это в коротком окне.
     *
     * Срабатывает ровно на пороге, а не на каждом отказе сверх него: одна тревога на окно.
     * Доля нужна, чтобы сотня отказов на десятках тысяч живых соединений тревогой не была.
     */
    internal fun shtorm(otkazov: Int, soed: Int): Boolean =
        otkazov == BYSTRO_POROG && dolyaOtkazov(otkazov, soed) >= BYSTRO_DOLYA

    /** Одна тревога на оба окна: короткое и десятиминутное делят общий предел частоты. */
    private fun podnyatTrevogu(teper: Long, dolya: Int, otk: Int, gde: String) {
        if (teper - trevogaBylaV <= TREVOGA_NE_CHASHCHE_MS) return
        trevogaBylaV = teper
        Log.w(TAG, "отказов $dolya% $gde — отправляю журнал, не дожидаясь ночи")
        runCatching { Zapisi.perehod("trevoga", "отказов $dolya% $gde, всего $otk") }
        sohranitOknoSyrya(teper, dolya, otk)
        LogUploadWork.otpravitSeychas("по тревоге")
    }

    /**
     * Последние строки ядра, чтобы при тревоге сохранить ОКНО вокруг события.
     *
     * Зачем. Поток ядра заполняет свои шесть мегабайт за считанные минуты и вытесняет
     * сам себя: к ночной отправке от беды не остаётся ни строки. Сводка говорит, что
     * беда была, а вот КАКАЯ — видно только в сырье, и новую болезнь, которой в каталоге
     * нет, распознать без него нельзя вовсе. Держим кольцом в памяти, на диск кладём
     * только когда действительно тревога.
     */
    private const val OKNO_SYRYA_STROK = 2000

    private val kolco = ArrayDeque<String>(OKNO_SYRYA_STROK)

    private fun zapomnit(line: String) {
        synchronized(kolco) {
            if (kolco.size >= OKNO_SYRYA_STROK) kolco.removeFirst()
            kolco.addLast(line)
        }
    }

    /** Сложить кольцо в отдельный файл: отправка забирает весь каталог, значит уедет само. */
    private fun sohranitOknoSyrya(teper: Long, dolya: Int, otkazov: Int) {
        val stroki = synchronized(kolco) { kolco.toList() }
        if (stroki.isEmpty()) return
        runCatching {
            val papka = AppLog.dir(Application.application)
            val imya = "kelevra-syrye-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date(teper)) + ".log"
            val fayl = File(papka, imya)
            fayl.writeText(
                "=== окно сырья: отказов $otkazov ($dolya%), строк ${stroki.size} ===\n" +
                    stroki.joinToString("\n") + "\n",
            )
            Log.i(TAG, "окно сырья сохранено: $imya, строк ${stroki.size}")
            // Больше двух окон не держим: третье вытесняет самое старое. Иначе череда
            // тревог на плохой сети забьёт хранилище телефона.
            val vse = papka.listFiles { f -> f.name.startsWith("kelevra-syrye-") }?.sortedBy { it.name }
                ?: return@runCatching
            vse.dropLast(2).forEach { runCatching { it.delete() } }
        }.onFailure { Log.w(TAG, "окно сырья не сохранилось: ${it.message}") }
    }

    private val handler = object : CommandClient.Handler {
        override fun appendLogs(message: List<LogEntry>) {
            val target = rotator ?: return
            val stamp = stampFormat.format(Date())
            val text = buildString {
                for (entry in message) {
                    val line = ansi.replace(entry.message, "").trimEnd()
                    if (line.isEmpty()) continue
                    runCatching { zapomnit(line) }
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
