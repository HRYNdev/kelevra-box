package io.nekohasekai.sfa.bg.path

import android.net.Network
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.bg.AutoModeExits
import java.io.DataInputStream
import java.io.OutputStream
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
 *
 * Путь без посредника. Дома локального входа нет вообще — туннель погашен, обход делает
 * роутер, — а вопрос «уходит ли наружу трафик» стоит ровно тот же. Для него есть
 * [measureDirect]: тот же запрос и тот же разбор ответа, только соединение идёт прямо
 * по физической сети.
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

    /**
     * Ожидание ответа для прямой пробы — той, что идёт мимо всякого туннеля
     * ([measureDirect]).
     *
     * Нижняя граница [MIN_TIMEOUT_MILLIS] сюда не относится: те 12 секунд взяты под
     * служебный отклик перегруженного канала через SOCKS, а здесь между нами и целью
     * нет ничего, кроме самой сети. Путь, который в такой обстановке думает дольше
     * шести секунд, всё равно не тот путь, на котором стоит объявлять «дома».
     */
    const val DIRECT_TIMEOUT_MILLIS = 6_000

    /** Прямое соединение до цели: столько ждём ответа на SYN. */
    private const val DIRECT_CONNECT_TIMEOUT_MILLIS = 4_000

    /** К одному имени — не чаще раза в это время. */
    private const val QUIET_MILLIS = 2_000L

    /**
     * Сколько ждём адрес цели своим резолвером, прежде чем спросить систему.
     *
     * Срок короткий намеренно: это не проба, а подготовка к ней, и весь замер ограничен
     * [DIRECT_TIMEOUT_MILLIS]. Домашний роутер отвечает за десятки миллисекунд.
     */
    private const val NAME_BUDGET_MILLIS = 1_500L

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

    /**
     * Меряет путь **напрямую**: без SOCKS, без туннеля, обычным сокетом — защищённым от
     * нашего tun и привязанным к физической сети.
     *
     * Нужна ровно для одного случая — дома. Там локального входа нет вовсе (туннель
     * погашен, потому что обход делает роутер), а вопрос стоит тот же самый: уходит ли
     * через этот путь трафик наружу на самом деле. Раньше на него отвечал один DNS, и
     * это была дыра: домашний резолвер отдаёт подменные адреса и в сети, где наружу не
     * проходит вообще ничего, — признак «дома» получался, а связи не было.
     *
     * Мимо своего туннеля проба идёт не привязкой к сети: правило per-uid для VPN стоит
     * выше неё, и привязанный сокет всё равно уходил в наш же tun — при поднятом туннеле
     * проба мерила туннель и подтверждала дом сама себе. Уводит сокет [ProbeSocket]
     * (`VpnService.protect`), привязка к сети идёт следом. Имя резолвится через
     * `network.getAllByName`, то есть системным резолвером сети: он ходит мимо туннеля
     * и без всякой защиты.
     *
     * @param network физическая сеть, по которой мерим. VPN-сеть сюда передавать нельзя:
     *   получится замер собственного туннеля, а не обстановки.
     */
    fun measureDirect(
        network: Network,
        label: String,
        timeoutMillis: Int = DIRECT_TIMEOUT_MILLIS,
        target: Target? = null,
    ): Measurement = turn.withLock {
        val goal = target ?: nextTargetInTurn()
        quietDown(goal.host)
        val result = directRequest(network, goal, timeoutMillis)
        lastTouched[goal.host] = SystemClock.elapsedRealtime()
        Log.i(TAG, "«$label» напрямую по сети → $result")
        result
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

                exchange(out, input, goal, facts, startedAt, collectBody)
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

    /**
     * То же самое, но без посредника: соединяемся с целью сами по указанной сети.
     *
     * Разница с [request] ровно в двух строчках — откуда берётся адрес и куда идёт
     * `connect`. Всё, что после соединения, общее: тот же запрос, тот же разбор ответа,
     * та же граница между «ответа нет» и «ответ не тот».
     *
     * В факты пишем, была ли защита от своего tun: незащищённый замер при поднятом
     * туннеле меряет туннель, и по логу это должно быть видно сразу, а не выясняться
     * разбором.
     */
    private fun directRequest(network: Network, goal: Target, timeoutMillis: Int): Measurement {
        val facts = "напрямую (${if (ProbeSocket.protecting) "мимо своего tun" else "без защиты от tun"}), цель $goal"
        val startedAt = SystemClock.elapsedRealtime()
        // Имя цели спрашиваем СВОИМ путём, мимо своего же tun ([NetDns]), и только потом,
        // если он не сработал, — системой. Причина ровно та же, по которой защищён сокет:
        // при поднятом туннеле системный резолвер отвечает за наше ядро, а не за сеть
        // вокруг. Проверено в эмуляторе 14.08.2026 при поднятом туннеле: `getAllByName`
        // не отдавал адрес вовсе, замер не состоялся ни разу, и дом не мог подтвердиться
        // трафиком, пока туннель поднят, — то есть осечка вердикта запирала сама себя.
        val address = when (val own = NetDns.resolve(network, goal.host, NAME_BUDGET_MILLIS)) {
            is NetDns.Outcome.Answered -> own.addresses.firstOrNull()
            is NetDns.Outcome.Silent -> null
        } ?: runCatching { network.getAllByName(goal.host).firstOrNull() }.getOrNull()
        // Имя не резолвится — это НЕ «путь мёртв». На свежем вайфае резолвер молчит первые
        // секунды всегда, и прежнее «мёртв» отсюда стирало память о доме и уводило человека
        // в туннель на пять минут (жалоба 14.08.2026). Замер просто не состоялся.
            ?: return Measurement.unmeasurable("адрес цели не узнать: сеть не ответила на запрос имени ($facts)")
        return try {
            ProbeSocket.open { network.bindSocket(it) }.use { socket ->
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(address, goal.port), DIRECT_CONNECT_TIMEOUT_MILLIS)
                exchange(
                    socket.getOutputStream(),
                    DataInputStream(socket.getInputStream()),
                    goal,
                    "$facts ($address)",
                    startedAt,
                    collectBody = false,
                )
            }
        } catch (t: Throwable) {
            Measurement.dead(
                when (t) {
                    // Под белым списком это и происходит: SYN уходит, ответа нет вовсе.
                    is SocketTimeoutException -> "ответа не дождались за $timeoutMillis мс"
                    is ConnectException -> "соединение не встало: ${t.message.orEmpty()}"
                    else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                },
                facts,
            )
        }
    }

    /**
     * Запрос и ответ по уже открытому соединению — общая часть обеих проб.
     *
     * Вот здесь и ловится «порт жив, трафика нет»: соединение установлено, запрос ушёл,
     * а строки состояния не приходит.
     */
    private fun exchange(
        out: OutputStream,
        input: DataInputStream,
        goal: Target,
        facts: String,
        startedAt: Long,
        collectBody: Boolean,
    ): Measurement {
        out.write(
            (
                "GET ${goal.path} HTTP/1.1\r\nHost: ${goal.host}\r\n" +
                    "User-Agent: kelevra\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()

        val statusLine = input.readLine().orEmpty()
        if (!statusLine.startsWith("HTTP/1.")) {
            return Measurement.dead(
                if (statusLine.isBlank()) "ответа нет" else "ответ не похож на HTTP",
                facts,
            )
        }
        val latency = SystemClock.elapsedRealtime() - startedAt
        val extra = if (collectBody) ", тело: ${firstBodyLine(input)}" else ""
        return Measurement.live(latency, facts + ", ответ «${statusLine.trim()}»" + extra)
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
