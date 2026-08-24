package io.nekohasekai.sfa.bg.path

import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.bg.AutoModeExits
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Сколько путь реально тянет, а не за сколько отвечает.
 *
 * Зачем отдельно от [HonestProbe]. Та отвечает на вопрос «идёт ли через путь трафик»:
 * ушёл запрос, пришла строка состояния — жив. Против ТСПУ этого хватало, пока он резал
 * соединения целиком. Но троттлинг устроен иначе: соединение встаёт, ответ приходит
 * быстро, а полоса задушена. Проба на `generate_204` весит двести байт и проскакивает
 * сквозь такое, не заметив: путь объявляется живым с задержкой в четверть секунды,
 * а у человека в это время ничего не грузится.
 *
 * Поэтому здесь качается настоящий объём и меряется скорость. Цель — обычный публичный
 * файл Google на том же домене, что уже в ротации [HonestProbe]: сто с лишним килобайт,
 * чистый HTTP, ничей интерес. Загрузка такого файла не отличается от того, что телефон
 * делает сам.
 *
 * Замер дорогой (сотня килобайт через туннель), поэтому зовётся редко и только для пути,
 * который прямо сейчас несёт трафик: ответ на вопрос «этот путь ещё годен» нужен именно
 * про него, а не про запасные.
 */
object SpeedProbe {
    private const val TAG = "SpeedProbe"

    /** Публичный файл Google, 109 КБ на 24.08.2026. Домен тот же, что в обычных пробах. */
    private val TARGET = HonestProbe.Target("www.gstatic.com", 80, "/ipranges/cloud.json")

    /** Вход на петле: не ответил за две секунды — его там нет. */
    private const val ENTRY_TIMEOUT_MILLIS = 2_000

    /** Ждём каждую порцию. Задушенный канал отдаёт медленно, но отдаёт. */
    private const val READ_TIMEOUT_MILLIS = 8_000

    /** Дольше этого замер не тянем: ответ «медленно» уже получен. */
    const val BUDGET_MILLIS = 15_000L

    /** Хватит для вывода о полосе; дальше качать незачем. */
    const val ENOUGH_BYTES = 96 * 1024

    /**
     * Ниже этого считаем путь задушенным.
     *
     * 64 КБ/с это примерно полмегабита. Живой канал через туннель на мобильной сети
     * даёт кратно больше даже в плохую погоду, а троттлинг ТСПУ прижимает как раз
     * к сотням килобит. Порог намеренно низкий: цель поймать «ничего не грузится»,
     * а не придираться к медленному вечеру.
     */
    const val SQUEEZED_BYTES_PER_SEC = 64L * 1024

    /** Не чаще этого: замер платный по трафику. */
    const val COOLDOWN_MILLIS = 10 * 60 * 1000L

    sealed class Speed {
        /** Скачали [bytes] за [millis]. */
        data class Measured(val bytes: Int, val millis: Long) : Speed() {
            val bytesPerSecond: Long get() = if (millis <= 0) 0 else bytes * 1000L / millis

            override fun toString() = "${bytesPerSecond / 1024} КБ/с ($bytes Б за $millis мс)"
        }

        /** Путь не отдал данные вовсе. Это отдельно от «медленно». */
        data class Failed(val reason: String) : Speed() {
            override fun toString() = "не отдал данные — $reason"
        }

        /** Мерить нечем: входа нет, ядро не поднято, рано мерить снова. */
        data class Unmeasurable(val reason: String) : Speed() {
            override fun toString() = "не мерили — $reason"
        }
    }

    /** Задушен ли путь. Отдельная чистая функция: по ней и решают, и её же проверяют тестом. */
    fun squeezed(speed: Speed, floor: Long = SQUEEZED_BYTES_PER_SEC): Boolean =
        speed is Speed.Measured && speed.bytesPerSecond < floor

    private var lastRunAt = 0L

    /** Прошёл ли срок с прошлого замера. */
    fun due(now: Long = SystemClock.elapsedRealtime(), cooldown: Long = COOLDOWN_MILLIS): Boolean =
        lastRunAt == 0L || now - lastRunAt >= cooldown

    fun measure(entry: AutoModeExits.Endpoint?, label: String): Speed {
        if (entry == null) return Speed.Unmeasurable("за путём «$label» не закреплён локальный вход")
        if (entry.port !in 1..65535) return Speed.Unmeasurable("у пути «$label» негодный порт ${entry.port}")
        if (!due()) return Speed.Unmeasurable("замер скорости делали меньше ${COOLDOWN_MILLIS / 60000} мин назад")
        lastRunAt = SystemClock.elapsedRealtime()
        val speed = runCatching { download(entry) }.getOrElse {
            Speed.Failed(it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName)
        }
        Log.i(TAG, "«$label» через ${entry.host}:${entry.port} → $speed")
        return speed
    }

    private fun download(entry: AutoModeExits.Endpoint): Speed = Socket().use { socket ->
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MILLIS
        socket.connect(InetSocketAddress(entry.host, entry.port), ENTRY_TIMEOUT_MILLIS)
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = ByteArray(2).also { input.readFully(it) }
        if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
            return Speed.Failed("вход не принял приветствие SOCKS5")
        }
        val host = TARGET.host.toByteArray()
        out.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host +
                byteArrayOf((TARGET.port shr 8).toByte(), (TARGET.port and 0xff).toByte()),
        )
        out.flush()
        val reply = ByteArray(4).also { input.readFully(it) }
        if (reply[1].toInt() != 0x00) return Speed.Failed("путь не открыл соединение (код ${reply[1].toInt()})")
        when (reply[3].toInt()) {
            0x01 -> input.skipBytes(4 + 2)
            0x03 -> input.skipBytes(input.readUnsignedByte() + 2)
            0x04 -> input.skipBytes(16 + 2)
            else -> return Speed.Failed("непонятный ответ входа")
        }

        out.write(
            (
                "GET ${TARGET.path} HTTP/1.1\r\nHost: ${TARGET.host}\r\n" +
                    "User-Agent: kelevra\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()

        val status = input.readLine().orEmpty()
        if (!status.startsWith("HTTP/1.")) {
            return Speed.Failed(if (status.isBlank()) "ответа нет" else "ответ не похож на HTTP")
        }
        // Заголовки до пустой строки: их вес в скорость не считаем, меряем само тело.
        while (true) {
            val line = input.readLine() ?: return Speed.Failed("ответ оборвался на заголовках")
            if (line.isBlank()) break
        }

        val buffer = ByteArray(16 * 1024)
        var read = 0
        val startedAt = SystemClock.elapsedRealtime()
        while (read < ENOUGH_BYTES && SystemClock.elapsedRealtime() - startedAt < BUDGET_MILLIS) {
            val n = input.read(buffer)
            if (n <= 0) break
            read += n
        }
        val spent = SystemClock.elapsedRealtime() - startedAt
        if (read == 0) return Speed.Failed("тело не пришло вовсе")
        Speed.Measured(read, spent)
    }
}
