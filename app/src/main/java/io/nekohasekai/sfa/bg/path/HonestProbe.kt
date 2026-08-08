package io.nekohasekai.sfa.bg.path

import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.bg.AutoModeExits
import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Одна проба на все пути.
 *
 * Раньше их было две почти одинаковых: [io.nekohasekai.sfa.bg.ProxyProbe] ходила через
 * локальный вход sing-box, а `OlcRtcCore.runProbe` — через SOCKS комнаты. Обе делали одно
 * и то же: SOCKS5 CONNECT по имени плюс один короткий HTTP-запрос. Теперь это одно место,
 * и разница между путями свелась к одному параметру — в какой локальный вход стучаться.
 *
 * Почему именно так меряем:
 *  - **SOCKS5, а не HTTP-прокси.** Сервер обязан ответить кодом на CONNECT ДО того, как
 *    пойдут данные, поэтому «вход не отвечает», «канал не открыл соединение» и «соединение
 *    открылось, а данные не идут» — три разных наблюдения, а не одно «не работает».
 *  - **Имя, а не адрес** (ATYP=domain): резолвит дальняя сторона, значит меряется путь
 *    через канал, а не локальный DNS.
 *  - **Ответ, а не рукопожатие.** ТСПУ душит транспорт: соединение встаёт, а байты не
 *    идут. Живым путь считается только когда пришла строка состояния HTTP.
 *
 * Скрытность. Пробы идут по очереди (никаких параллельных запросов пачкой), без TLS,
 * и к одному имени не чаще раза в две секунды: цели берутся по кругу из короткого списка
 * обычных для Android проверок связи — такой запрос не отличается от того, что телефон
 * и так делает сам.
 *
 * Железное правило. Нет закреплённого входа — [Verdict.Unmeasurable], и никакой ветки,
 * которая превратила бы это в «живой». Непроверенный путь наверх уходит непроверенным.
 */
object HonestProbe {
    private const val TAG = "HonestProbe"

    /**
     * Нижняя граница ожидания ответа. 12 секунд не запас «на всякий случай»: замер 07.08
     * на живом канале под нагрузкой дал служебный отклик 2-9 секунд (bufferbloat), и на
     * шести секундах проба объявила бы смерть работающему каналу.
     */
    const val MIN_TIMEOUT_MILLIS = 12_000

    /** Вход на петле: не ответил за две секунды — его там нет. */
    private const val ENTRY_TIMEOUT_MILLIS = 2_000

    /** К одному имени — не чаще раза в это время. */
    private const val QUIET_MILLIS = 2_000L

    /** Куда стучимся. Обычные проверки связи: маленький ответ, чистый HTTP, ничей интерес. */
    data class Target(val host: String, val port: Int, val path: String) {
        override fun toString() = "$host$path"
    }

    /**
     * Цель для диагностики: отдаёт адрес, которым нас видит внешний мир. В обычную
     * ротацию не входит — спрашивать её каждую минуту незачем, это лишняя примета.
     */
    val EGRESS_TARGET = Target("ifconfig.me", 80, "/ip")

    private val TARGETS = listOf(
        Target("connectivitycheck.gstatic.com", 80, "/generate_204"),
        Target("detectportal.firefox.com", 80, "/success.txt"),
        Target("captive.apple.com", 80, "/hotspot-detect.html"),
        Target("www.gstatic.com", 80, "/generate_204"),
    )

    enum class Verdict {
        /** Запрос ушёл и ответ вернулся: путь несёт трафик. */
        Live,

        /** Путь трафик не несёт. */
        Dead,

        /** Мерить нечем. Это НЕ «живой» и НЕ «мёртвый». */
        Unmeasurable,
    }

    /**
     * Что показал замер. Собирается только фабриками ниже — чтобы «не проверено с
     * задержкой 0 мс, считаем живым» нельзя было составить в принципе.
     */
    data class Measurement private constructor(
        val verdict: Verdict,
        val latencyMs: Long?,
        val reason: String,
        val facts: String,
    ) {
        /** Единственный способ спросить «работает ли». Unmeasurable сюда не попадает никогда. */
        val live: Boolean get() = verdict == Verdict.Live

        /** Замер вообще состоялся. false — путь нечем спросить, наверх идёт «не проверено». */
        val measured: Boolean get() = verdict != Verdict.Unmeasurable

        override fun toString(): String = when (verdict) {
            Verdict.Live -> "жив, ответ за $latencyMs мс ($facts)"
            Verdict.Dead -> "мёртв — $reason ($facts)"
            Verdict.Unmeasurable -> "не проверен — $reason"
        }

        companion object {
            fun live(latencyMs: Long, facts: String) = Measurement(Verdict.Live, latencyMs, "", facts)

            fun dead(reason: String, facts: String) = Measurement(Verdict.Dead, null, reason, facts)

            /** Нечем мерить: входа нет, патч не лёг, ядро не поднято. */
            fun unmeasurable(reason: String) = Measurement(Verdict.Unmeasurable, null, reason, "")
        }
    }

    /** Пробы идут по одной: параллельная пачка запросов и заметна, и мешает сама себе. */
    private val turn = ReentrantLock()

    /** Когда последний раз трогали имя. Под [turn]. */
    private val lastTouched = HashMap<String, Long>()
    private var nextTarget = 0

    /**
     * Меряет путь через закреплённый за ним локальный вход.
     *
     * @param entry вход, который в конфиге привязан правилом маршрута ровно к этому пути
     *   ([io.nekohasekai.sfa.bg.ProbeInboundPatch]). `null` — путь мерить нечем.
     * @param label как путь называется в логе.
     * @param timeoutMillis ожидание ответа; поднимается до [MIN_TIMEOUT_MILLIS], если просят меньше.
     * @param target куда стучаться. `null` — берём следующую цель по кругу.
     * @param collectBody забрать первую строку тела в [Measurement.facts]. Для диагностики
     *   (какой адрес видит внешний мир), в обычных пробах не нужен.
     */
    fun measure(
        entry: AutoModeExits.Endpoint?,
        label: String,
        timeoutMillis: Int = MIN_TIMEOUT_MILLIS,
        target: Target? = null,
        collectBody: Boolean = false,
    ): Measurement {
        if (entry == null) {
            return Measurement.unmeasurable("за путём «$label» не закреплён локальный вход")
        }
        if (entry.port !in 1..65535) {
            return Measurement.unmeasurable("у пути «$label» негодный порт входа ${entry.port}")
        }
        val wait = maxOf(timeoutMillis, MIN_TIMEOUT_MILLIS)
        return turn.withLock {
            val goal = target ?: nextTargetInTurn()
            quietDown(goal.host)
            val result = request(entry, goal, wait, collectBody)
            lastTouched[goal.host] = SystemClock.elapsedRealtime()
            Log.i(TAG, "«$label» через ${entry.host}:${entry.port} → $result")
            result
        }
    }

    /** Следующая цель по кругу: одно и то же имя каждые полминуты — само по себе примета. */
    private fun nextTargetInTurn(): Target {
        val goal = TARGETS[nextTarget % TARGETS.size]
        nextTarget = (nextTarget + 1) % TARGETS.size
        return goal
    }

    /** Держит паузу, если это имя трогали только что. */
    private fun quietDown(host: String) {
        val last = lastTouched[host] ?: return
        val since = SystemClock.elapsedRealtime() - last
        if (since in 0 until QUIET_MILLIS) {
            runCatching { Thread.sleep(QUIET_MILLIS - since) }
        }
    }

    private fun request(
        entry: AutoModeExits.Endpoint,
        goal: Target,
        timeoutMillis: Int,
        collectBody: Boolean,
    ): Measurement {
        val facts = "вход ${entry.host}:${entry.port}, цель $goal"
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(entry.host, entry.port), ENTRY_TIMEOUT_MILLIS)
                val out = socket.getOutputStream()
                val input = DataInputStream(socket.getInputStream())

                // приветствие: версия 5, один метод «без авторизации»
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val greeting = ByteArray(2).also { input.readFully(it) }
                if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                    return Measurement.dead("вход не принял приветствие SOCKS5", facts)
                }

                val host = goal.host.toByteArray()
                out.write(
                    byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host +
                        byteArrayOf((goal.port shr 8).toByte(), (goal.port and 0xff).toByte()),
                )
                out.flush()
                val reply = ByteArray(4).also { input.readFully(it) }
                if (reply[1].toInt() != 0x00) {
                    // Сюда попадает «выход есть, но соединение через него не встало»:
                    // sing-box отвечает отказом, когда исходящий не смог дозвониться.
                    return Measurement.dead("путь не открыл соединение (код ${reply[1].toInt()})", facts)
                }
                when (reply[3].toInt()) {
                    0x01 -> input.skipBytes(4 + 2)
                    0x03 -> input.skipBytes(input.readUnsignedByte() + 2)
                    0x04 -> input.skipBytes(16 + 2)
                    else -> return Measurement.dead("непонятный ответ входа", facts)
                }

                out.write(
                    (
                        "GET ${goal.path} HTTP/1.1\r\nHost: ${goal.host}\r\n" +
                            "User-Agent: kelevra\r\nConnection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.flush()

                // Вот здесь и ловится «порт жив, трафика нет»: соединение установлено,
                // запрос ушёл, а строки состояния не приходит.
                val statusLine = input.readLine().orEmpty()
                if (!statusLine.startsWith("HTTP/1.")) {
                    return Measurement.dead(
                        if (statusLine.isBlank()) "ответа нет" else "ответ не похож на HTTP",
                        facts,
                    )
                }
                val latency = SystemClock.elapsedRealtime() - startedAt
                val extra = if (collectBody) ", тело: ${firstBodyLine(input)}" else ""
                Measurement.live(latency, facts + ", ответ «${statusLine.trim()}»" + extra)
            }
        } catch (t: Throwable) {
            Measurement.dead(
                when (t) {
                    is SocketTimeoutException -> "ответа не дождались за $timeoutMillis мс"
                    is ConnectException -> "локальный вход не отвечает"
                    else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                },
                facts,
            )
        }
    }

    /** Первая непустая строка после заголовков. Только для диагностики. */
    private fun firstBodyLine(input: DataInputStream): String = runCatching {
        while (true) {
            val line = input.readLine() ?: return "нет"
            if (line.isBlank()) break
        }
        generateSequence { input.readLine() }.firstOrNull { it.isNotBlank() }?.take(64) ?: "пусто"
    }.getOrElse { "не прочитано" }
}
