package io.nekohasekai.sfa.bg

import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Второй носитель комнаты: OpenFlux через звонок WB Chat 1:1.
 *
 * Тот же бинарь `libopenflux.so`, что у Телемоста ([TelemostCore]), но свой процесс, своё
 * состояние и свой рабочий каталог (`filesDir/openflux-wb`): Телемост и WB Chat работают
 * одновременно на разных портах и друг о друге не знают. Ядро поднимает локальный SOCKS5,
 * который в профиле sing-box — отдельный socks-выход (тег с сервера) внутри ручного
 * селектора. Автомат про этот выход не знает: его выбирает человек.
 *
 * Ядро само завершается (код 3), когда звонок кончился или оборвался, — это норма:
 * перезапускает его [WbChatWatchdog]. Остановка — SIGTERM (на нём ядро кладёт трубку WB),
 * 3 с ожидания, потом SIGKILL (только с API 26; раньше `destroy()` и так шлёт SIGKILL).
 *
 * Аргументы (токен, куки) в журнал не пишутся. Ядро их само не печатает, но строки его
 * вывода всё равно проходят через [redact] — на случай, если однажды напечатает.
 *
 * Process.isAlive/waitFor(timeout)/destroyForcibly — API 26, а minSdk 24 (у flavor other
 * 21): живость только через exitValue(), см. коммит b1c4d196.
 */
object WbChatCore {
    private const val TAG = "WbChatCore"

    /** Тег, под которым в logcat идут собственные строки ядра WB Chat. */
    private const val CORE_TAG = "openflux-wbchat"

    const val NOSITEL = "wbchat"

    private const val BINARY = "libopenflux.so"

    const val READY_TIMEOUT_MILLIS = 60_000

    private const val STOP_TIMEOUT_MILLIS = 3_000L

    /** Код выхода ядра, когда звонок закончился или оборвался. */
    const val EXIT_CALL_ENDED = 3

    /**
     * Звонок поднят: бонд — `[bond] N flows paired; starting KCP`; без бонда (v1, один
     * датаканал) — `[...] DC open`.
     */
    private val READY_MARKERS = listOf("flows paired; starting KCP", "] DC open")

    sealed class State {
        data object Idle : State()

        data object Starting : State()

        data object Ready : State()

        data class Failed(val reason: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** Последняя причина отказа или выхода ядра словами; пусто — не было. */
    @Volatile
    var lastError: String = ""
        private set

    /** Последняя строка ядра — для причины отказа. */
    @Volatile
    var lastLine: String = ""
        private set

    /** Когда ядро последний раз стало готовым (elapsedRealtime); 0 — не было. */
    @Volatile
    var readyAt: Long = 0L
        private set

    /** Параметры, с которыми поднят нынешний процесс: сменились на сервере — пора перезапускать. */
    @Volatile
    var runningParams: WbChatParams.Params? = null
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var readySeen = false

    /** Секреты нынешнего запуска — только чтобы вычищать их из строк ядра. */
    @Volatile
    private var secrets: List<String> = emptyList()

    private val lock = Any()

    private fun binary(): File? = runCatching {
        File(Application.application.applicationInfo.nativeLibraryDir, BINARY)
    }.getOrNull()?.takeIf { it.isFile }

    val isAvailable: Boolean get() = binary() != null

    fun isRunning(): Boolean = process?.let(::alive) == true

    /** Код выхода последнего процесса, если он уже вышел; `null` — жив или не было. */
    fun exitCode(): Int? {
        val p = process ?: return null
        return try {
            p.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }
    }

    private fun alive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /**
     * Поднимает ядро и ждёт, пока звонок встанет и SOCKS начнёт принимать. Блокирующий
     * вызов, до [READY_TIMEOUT_MILLIS]. Прерывание потока гасит ядро и пробрасывается.
     *
     * @return `null` — поднят; иначе причина отказа словами.
     */
    fun start(params: WbChatParams.Params): String? {
        stop()
        val bin = binary() ?: return fail("ядро собрано без WB Chat (нет $BINARY)")
        val dir = File(Application.application.filesDir, "openflux-wb").apply { mkdirs() }
        val keyFile = File(dir, "key.txt")
        val args = mutableListOf(
            bin.absolutePath,
            "--role=client",
            "--inbound=socks5",
            "-s", "127.0.0.1:${params.socksPort}",
            "-t", NOSITEL,
            "--wbchat-peer", params.peer,
            "--wbchat-token", params.token,
            "--wbchat-cookies", params.cookies,
        )
        if (params.key.isNotBlank()) {
            writeSecret(keyFile, params.key)
            args += listOf("--encryption-key-file", keyFile.absolutePath)
        } else {
            keyFile.delete()
        }

        val builder = ProcessBuilder(args).directory(dir).redirectErrorStream(true)
        if (params.bond > 0) builder.environment()["WBCHAT_BOND"] = params.bond.toString()
        // Ядро само резолвит имя цели и шлёт выходу только IPv4. Системный DNS телефона
        // идёт через наш sing-box и отдаёт fakeip 198.18.x — для выхода это пустой адрес,
        // по имени не открывалось ничего (замер 15.10: спидтест). Свой резолвер мимо tun
        // ([WbChatConfigPatch.TUN_EXCLUDE]) отдаёт настоящие адреса.
        builder.environment()["OPENFLUX_DNS"] = WbChatConfigPatch.DNS

        val p: Process
        synchronized(lock) {
            readySeen = false
            lastLine = ""
            secrets = listOf(params.token, params.cookies, params.key).filter { it.length >= 8 }
            state = State.Starting
            Log.i(
                TAG,
                "старт: socks=127.0.0.1:${params.socksPort} собеседник=${params.peer} бонд=${params.bond} " +
                    "ключ=${if (params.key.isBlank()) "нет" else "есть"}",
            )
            p = try {
                builder.start()
            } catch (e: Exception) {
                return fail("ядро WB Chat не запустилось: ${e.message ?: e.javaClass.simpleName}")
            }
            process = p
            runningParams = params
        }
        Thread({ pump(p) }, "openflux-wb-log").apply { isDaemon = true }.start()

        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + READY_TIMEOUT_MILLIS
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                // Кто-то остановил ядро, пока мы ждали: это не отказ, состояние уже «не запущен».
                if (process !== p) return "остановлено во время подъёма"
                if (!alive(p)) {
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    return fail("ядро WB Chat вышло при подъёме (код $code): $lastLine")
                }
                if (readySeen && portOpen(params.socksPort)) {
                    readyAt = SystemClock.elapsedRealtime()
                    lastError = ""
                    state = State.Ready
                    Log.i(TAG, "готов за ${readyAt - startedAt} мс")
                    return null
                }
                Thread.sleep(250)
            }
        } catch (e: InterruptedException) {
            stop()
            throw e
        }
        val why = if (!readySeen) "звонок не встал" else "SOCKS не открылся"
        stop()
        return fail("WB Chat не собрался за ${READY_TIMEOUT_MILLIS / 1000} с: $why")
    }

    /** Ядро было готово, а теперь вышло само: записать причину и вернуть код выхода. */
    fun noteExited(): Int? {
        val code = exitCode() ?: return null
        val why = if (code == EXIT_CALL_ENDED) "звонок закончился (код $code)" else "ядро WB Chat вышло (код $code): $lastLine"
        synchronized(lock) {
            process = null
            runningParams = null
            lastError = why
            state = State.Failed(why)
        }
        Log.i(TAG, why)
        return code
    }

    fun stop() {
        val p = synchronized(lock) {
            val current = process
            process = null
            runningParams = null
            if (state !is State.Failed) state = State.Idle
            current
        } ?: return
        Log.i(TAG, "остановка")
        // SIGTERM: на нём ядро кладёт трубку WB, иначе на той стороне висит звонок.
        p.destroy()
        val until = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MILLIS
        while (alive(p) && SystemClock.elapsedRealtime() < until) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                // Остановку доводим до конца даже из прерванного потока; флаг вернём.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (alive(p)) {
            Log.w(TAG, "не вышел за $STOP_TIMEOUT_MILLIS мс, добиваю")
            // до API 26 destroy() в libcore Android и так шлёт SIGKILL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly()
        }
    }

    /** Состояние словами для экрана: «готов / поднимается / ошибка: …». */
    fun statusText(): String = when (val s = state) {
        State.Idle -> "не запущен"
        State.Starting -> "поднимается"
        State.Ready -> if (isRunning()) "готов" else "поднимается"
        is State.Failed -> "ошибка: ${s.reason}"
    }

    /** Сбросить состояние «ошибка» в «не запущен» — когда выход больше не нужен. */
    fun markIdle() {
        synchronized(lock) {
            if (process == null) state = State.Idle
        }
    }

    private fun fail(reason: String): String {
        synchronized(lock) {
            lastError = reason
            state = State.Failed(reason)
        }
        Log.w(TAG, reason)
        return reason
    }

    private fun writeSecret(file: File, value: String) {
        // Права 600: сперва пустой файл без чужого доступа, потом содержимое.
        file.delete()
        file.createNewFile()
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.writeText(value)
    }

    private fun pump(p: Process) {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = redact(raw.trimEnd())
                if (line.isBlank()) return@forEachLine
                if (READY_MARKERS.any { it in line }) readySeen = true
                lastLine = line
                Log.i(CORE_TAG, line)
            }
        }
        Log.i(TAG, "ядро WB Chat закрыло вывод")
    }

    private fun redact(line: String): String {
        var out = line
        for (secret in secrets) {
            if (secret in out) out = out.replace(secret, "…")
        }
        return out
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)
}
