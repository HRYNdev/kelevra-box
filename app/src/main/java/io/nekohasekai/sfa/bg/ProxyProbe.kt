package io.nekohasekai.sfa.bg

import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Проба «через этот канал реально ходит трафик».
 *
 * Зачем отдельно от TCP-connect. Соединение до адреса узла доказывает ровно одно:
 * SYN дошёл и на него ответили SYN/ACK. ТСПУ душит не установку соединения, а сам
 * транспорт: рукопожатие проходит, а дальше поток встаёт — байты либо не доходят,
 * либо приходят с задержкой в десятки секунд. Для TCP-connect такой канал выглядит
 * живым, и автомат промолчал бы ровно в той обстановке, ради которой он написан.
 *
 * Поэтому спрашиваем не порт, а путь целиком: заходим в локальный прокси самого
 * sing-box (тот, что конфиг подставляет системе через `platform.http_proxy`) и просим
 * его сходить наружу. Один такой запрос прогоняет всю цепочку — маршруты sing-box,
 * выбранный выход, TLS до узла, сам транспорт, выход в интернет и ответ обратно.
 * Если транспорт задушен, ответа не будет, и это видно.
 *
 * Имя цели отдаём прокси как имя (SOCKS5 ATYP=domain): резолвит его дальняя сторона,
 * значит проба меряет путь через канал, а не локальный DNS.
 *
 * Вход `mixed` понимает и SOCKS5, и HTTP; выбираем SOCKS5, потому что он не оставляет
 * места двусмысленности — сервер обязан ответить кодом на CONNECT до того, как пойдут
 * данные, и «прокси принял, но канал мёртв» отличается от «прокси не отвечает».
 */
object ProxyProbe {
    private const val TAG = "ProxyProbe"

    sealed interface Result {
        /** Запрос ушёл и ответ вернулся: канал несёт трафик. */
        data class Live(val latencyMs: Long) : Result

        /** Канал трафик не несёт. [reason] — словами, для лога. */
        data class Dead(val reason: String) : Result
    }

    /** Сколько ждём локальный прокси: он на петле, дольше секунды ждать нечего. */
    private const val CONNECT_TIMEOUT_MILLIS = 2_000

    fun through(
        proxy: AutoModeExits.Endpoint,
        targetHost: String,
        targetPort: Int,
        timeoutMillis: Int,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(proxy.host, proxy.port), CONNECT_TIMEOUT_MILLIS)
                val out = socket.getOutputStream()
                val input = DataInputStream(socket.getInputStream())

                // приветствие: версия 5, один метод «без авторизации»
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val greeting = ByteArray(2).also { input.readFully(it) }
                if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                    return Result.Dead("локальный прокси не принял приветствие")
                }

                val host = targetHost.toByteArray()
                out.write(
                    byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host +
                        byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xff).toByte()),
                )
                out.flush()
                val reply = ByteArray(4).also { input.readFully(it) }
                if (reply[1].toInt() != 0x00) {
                    // Сюда попадает и «выход есть, но соединение через него не встало»:
                    // sing-box отвечает отказом, когда исходящий не смог дозвониться.
                    return Result.Dead("канал не открыл соединение (код ${reply[1].toInt()})")
                }
                when (reply[3].toInt()) {
                    0x01 -> input.skipBytes(4 + 2)
                    0x03 -> input.skipBytes(input.readUnsignedByte() + 2)
                    0x04 -> input.skipBytes(16 + 2)
                    else -> return Result.Dead("непонятный ответ локального прокси")
                }

                out.write(
                    (
                        "GET / HTTP/1.1\r\nHost: $targetHost\r\n" +
                            "User-Agent: kelevra\r\nConnection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.flush()

                // Вот здесь и ловится «порт жив, трафика нет»: соединение установлено,
                // запрос ушёл, а строки состояния не приходит.
                val statusLine = input.readLine().orEmpty()
                if (!statusLine.startsWith("HTTP/1.")) {
                    return Result.Dead(if (statusLine.isBlank()) "ответа нет" else "ответ не похож на HTTP")
                }
                Result.Live(SystemClock.elapsedRealtime() - startedAt)
            }
        } catch (t: Throwable) {
            Result.Dead(
                when (t) {
                    is SocketTimeoutException -> "ответа не дождались"
                    is ConnectException -> "локальный прокси не отвечает"
                    else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                },
            )
        }
    }

    fun log(where: String, result: Result) {
        when (result) {
            is Result.Live -> Log.i(TAG, "$where: трафик идёт, ответ за ${result.latencyMs} мс")
            is Result.Dead -> Log.i(TAG, "$where: трафика нет — ${result.reason}")
        }
    }
}
