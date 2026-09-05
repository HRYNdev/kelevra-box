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

    private val handler = object : CommandClient.Handler {
        override fun appendLogs(message: List<LogEntry>) {
            val target = rotator ?: return
            val stamp = stampFormat.format(Date())
            val text = buildString {
                for (entry in message) {
                    val line = ansi.replace(entry.message, "").trimEnd()
                    if (line.isEmpty()) continue
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
