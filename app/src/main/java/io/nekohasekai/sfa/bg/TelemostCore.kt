package io.nekohasekai.sfa.bg

import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Второй носитель комнаты: OpenFlux через Яндекс Телемост.
 *
 * Клиент OpenFlux заходит гостем в конференцию, где сидит выход (LXC 211), и поднимает
 * локальный SOCKS5 — ровно то же, что ядро olcRTC. Поэтому вся остальная машинка комнаты
 * (выход «Комната» на 127.0.0.1:порт, правки конфига, присмотр, автомат) не знает, чем
 * везут байты: ветвление живёт в [OlcRtcCore], здесь только процесс.
 *
 * Почему отдельный процесс, а не библиотека в libbox.aar: две gomobile-библиотеки в
 * одном APK не уживаются (у обеих свой пакет `go`), а пересборка libbox вместе с
 * OpenFlux — это CI и общий граф зависимостей pion. Бинарь собран под GOOS=android
 * (bionic-резолвер, системные корневые сертификаты) из тега v0.5.1 и лежит в jniLibs
 * как `libopenflux.so`: только из nativeLibraryDir Android 10+ даёт исполнять файлы
 * приложения (`useLegacyPackaging = true` его туда распаковывает).
 *
 * Петля через свой tun. protect(fd) у чужого процесса не позвать. Держится это тем же,
 * чем держится и olcRTC после входа: пока комната нужна, приложение выведено из tun
 * целиком ([TunnelFacts.selfOutsideTun]), а дочерний процесс живёт под тем же uid —
 * правило VPN per-uid его тоже не трогает. Первый подъём идёт до openTun.
 */
object TelemostCore {
    private const val TAG = "TelemostCore"

    /** Тег, под которым в logcat идут собственные строки OpenFlux. */
    private const val CORE_TAG = "openflux-core"

    const val NOSITEL = "telemost"

    private const val BINARY = "libopenflux.so"

    /**
     * Потолок одной попытки. Замер 11.10.2026 на эмуляторе: гость в конференции и все три
     * полосы спарены с выходом за 2 с. Запас — на вход в Телемост с сотовой и на выход,
     * который сам в этот момент перезаходит.
     */
    const val DEFAULT_READY_TIMEOUT_MILLIS = 45_000

    const val DEFAULT_STRIPES = 3
    const val DEFAULT_STRIPES_MAX = 6

    private const val STOP_TIMEOUT_MILLIS = 3_000L

    /** Полоса нашла выход: `[Telemost] lane 0 -> watching "a4464901"`. Пустые кавычки — выход ушёл. */
    private val pairedRegex = Regex("""\[Telemost] lane \d+ -> watching "([^"]*)"""")

    /** Шум TURN-клиента раз в две минуты, к делу не относится. */
    private val noise = listOf("Fail to refresh permissions")

    @Volatile
    private var process: Process? = null

    /** Сколько полос сейчас видят выход. */
    @Volatile
    private var pairedLanes = 0

    private val lanes = HashMap<String, Boolean>()

    /** Последняя строка ядра — для причины отказа. */
    @Volatile
    var lastLine: String = ""
        private set

    private fun binary(): File? = runCatching {
        File(Application.application.applicationInfo.nativeLibraryDir, BINARY)
    }.getOrNull()?.takeIf { it.isFile }

    /** Есть ли клиент OpenFlux в этой сборке. */
    val isAvailable: Boolean get() = binary() != null

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Поднимает клиента и ждёт, пока хотя бы одна полоса найдёт выход и SOCKS начнёт
     * принимать. Блокирующий вызов.
     *
     * @return `null` — поднят; иначе причина отказа словами.
     */
    fun start(
        url: String,
        key: String,
        socksPort: Int,
        stripes: Int,
        stripesMax: Int,
        readyTimeoutMillis: Int = DEFAULT_READY_TIMEOUT_MILLIS,
    ): String? {
        stop()
        val bin = binary() ?: return "ядро собрано без Телемоста (нет $BINARY)"
        val dir = File(Application.application.filesDir, "openflux").apply { mkdirs() }
        val keyFile = File(dir, "key.txt")
        val args = mutableListOf(
            bin.absolutePath,
            "--role=client",
            "--inbound=socks5",
            "-s", "127.0.0.1:$socksPort",
            "-t", NOSITEL,
            "-u", url,
            "--cookie-store=" + File(dir, "cookies-telemost.json").absolutePath,
        )
        if (key.isNotBlank()) {
            // Ключ пары с выходом: без него выход с ключом с нами говорить не станет.
            keyFile.writeText(key)
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            args += "--encryption-key-file=" + keyFile.absolutePath
        } else {
            keyFile.delete()
        }

        val builder = ProcessBuilder(args).directory(dir).redirectErrorStream(true)
        builder.environment()["TELEMOST_STRIPES"] = stripes.coerceAtLeast(1).toString()
        builder.environment()["TELEMOST_STRIPES_MAX"] = stripesMax.coerceAtLeast(stripes).toString()

        synchronized(lanes) { lanes.clear() }
        pairedLanes = 0
        lastLine = ""
        Log.i(
            TAG,
            "старт: socks=127.0.0.1:$socksPort полос $stripes/$stripesMax " +
                "конференция=${shortUrl(url)} ключ=${if (key.isBlank()) "нет" else "есть"}",
        )
        val p = builder.start()
        process = p
        Thread({ pump(p) }, "openflux-log").apply { isDaemon = true }.start()

        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + readyTimeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!p.isAlive) {
                process = null
                return "ядро Телемоста вышло (код ${runCatching { p.exitValue() }.getOrDefault(-1)}): $lastLine"
            }
            if (pairedLanes > 0 && portOpen(socksPort)) {
                Log.i(TAG, "готов за ${SystemClock.elapsedRealtime() - startedAt} мс: полос с выходом $pairedLanes")
                return null
            }
            Thread.sleep(250)
        }
        val why = if (pairedLanes == 0) "выход в конференции не найден" else "SOCKS не открылся"
        stop()
        return "Телемост не собрался за ${readyTimeoutMillis / 1000} с: $why"
    }

    fun stop() {
        val p = process ?: return
        process = null
        Log.i(TAG, "остановка")
        p.destroy()
        val done = runCatching { p.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            Log.w(TAG, "не вышел за $STOP_TIMEOUT_MILLIS мс, добиваю")
            p.destroyForcibly()
        }
        pairedLanes = 0
    }

    private fun pump(p: Process) {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.trimEnd()
                if (line.isBlank() || line == ")") return@forEachLine
                pairedRegex.find(line)?.let { m ->
                    val lane = line.substringAfter("lane ").substringBefore(' ')
                    synchronized(lanes) {
                        lanes[lane] = m.groupValues[1].isNotEmpty()
                        pairedLanes = lanes.values.count { it }
                    }
                }
                if (noise.none { it in line }) {
                    lastLine = line
                    Log.i(CORE_TAG, redact(line))
                }
            }
        }
        Log.i(TAG, "ядро Телемоста закрыло вывод")
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    private val linkRegex = Regex("""(telemost\.yandex\.ru/j/)(\d{4})\d+""")

    /** Номер конференции целиком в журнал не нужен: по нему в неё может зайти кто угодно. */
    private fun redact(line: String): String = line.replace(linkRegex) { it.groupValues[1] + it.groupValues[2] + "…" }

    private fun shortUrl(url: String): String = redact(url)
}
