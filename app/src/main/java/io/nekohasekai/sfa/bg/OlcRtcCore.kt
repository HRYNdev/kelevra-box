package io.nekohasekai.sfa.bg

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Обёртка над ядром olcRTC (`io.nekohasekai.mobile.Mobile` из объединённого libbox.aar).
 *
 * Ядро поднимает локальный SOCKS5 и уводит трафик через WebRTC-комнату. Для sing-box
 * это обычный socks-outbound на 127.0.0.1, поэтому ядро надо поднять ДО sing-box
 * и погасить ПОСЛЕ него.
 *
 * Почему рефлексия, а не прямой импорт: флейвор otherLegacy собирается с
 * libbox-legacy.aar, где пакета io.nekohasekai.mobile нет вообще (проверено:
 * 193 класса libbox, 0 классов mobile). Прямой импорт не дал бы этому флейвору
 * скомпилироваться, а сборка без olcrtc — валидный сценарий. Здесь же отсутствие
 * ядра деградирует в состояние [State.Unavailable], а не в NoClassDefFoundError.
 *
 * Параметры приходят снаружи ([Params]) — обёртка не читает Settings сама, потому что
 * позже те же значения приедут с сервера.
 */
object OlcRtcCore {
    private const val TAG = "OlcRtcCore"

    /** Тег, под которым в logcat идут собственные сообщения ядра. */
    private const val CORE_TAG = "olcrtc-core"

    /** Go int биндится в Java long — тип параметров нужен точный, иначе getMethod не найдёт. */
    private val LONG: Class<*> = java.lang.Long.TYPE

    private const val CLASS_MOBILE = "io.nekohasekai.mobile.Mobile"
    private const val CLASS_PROTECTOR = "io.nekohasekai.mobile.SocketProtector"
    private const val CLASS_LOG_WRITER = "io.nekohasekai.mobile.LogWriter"

    /**
     * Сколько ждём готовности одной попытки.
     *
     * Замеры 05.08 (эмулятор, боевая комната): удачные старты заняли 14.1 с, 13.3 с и
     * 3.2 с — при потолке в 15 с два запуска из шести упали в таймаут, хотя комната была
     * рабочая. Причина в фазе разрушения: серверная нога перезаходит в комнату раз в 10 с,
     * и попытка, попавшая в этот промежуток, не успевает. 25 с — это наблюдённый максимум
     * плюс полный цикл перезахода ноги.
     */
    const val DEFAULT_READY_TIMEOUT_MILLIS = 25_000

    /**
     * Сколько раз пробуем. Комната умирает через ~20 с после ухода участников, а нога
     * возвращается в неё за 10 с, поэтому вторая попытка почти всегда попадает уже
     * в собранную комнату. Третья — запас на случай, если нога сама в этот момент
     * перезапускалась.
     */
    const val DEFAULT_ATTEMPTS = 3

    /** Пауза между попытками: дать ноге дойти до следующего захода. */
    const val DEFAULT_RETRY_PAUSE_MILLIS = 5_000L

    /** Ядро слушает SOCKS только на петле: снаружи в него ходить некому. */
    private const val SOCKS_LISTEN_HOST = "127.0.0.1"

    /** Сколько ждём остановки ядра, чтобы не подвесить выключение сервиса. */
    private const val STOP_TIMEOUT_MILLIS = 5_000L

    /**
     * Живость управляющего потока. Дефолты ядра (10 с между пингами, 15 с на ответ,
     * 4 промаха подряд) сносят РАБОЧУЮ сессию примерно через 55 секунд молчания понгов,
     * а понг опаздывает всегда, когда канал занят данными: пинг стоит в общей очереди.
     *
     * 07.08.2026 это ловилось дважды на живом телефоне: комната отработала 14 минут
     * плотного трафика и легла по liveness, хотя данные в этот момент шли. Терпим
     * около трёх минут: настоящий обрыв всё равно виден по отвалу соединений, а вот
     * убивать живой канал из-за одного опоздавшего понга нельзя.
     */
    private const val LIVENESS_INTERVAL_MILLIS = 10_000L
    private const val LIVENESS_TIMEOUT_MILLIS = 30_000L
    private const val LIVENESS_FAILURES = 6L

    data class Params(
        val carrier: String,
        val roomId: String,
        val clientId: String,
        val keyHex: String,
        val transport: String,
        val socksPort: Int,
        val wbToken: String,
        val vp8Fps: Int,
        val vp8BatchSize: Int,
        val readyTimeoutMillis: Int = DEFAULT_READY_TIMEOUT_MILLIS,
        val attempts: Int = DEFAULT_ATTEMPTS,
        val retryPauseMillis: Long = DEFAULT_RETRY_PAUSE_MILLIS,
        val dnsServer: String = "",
        val socksUser: String = "",
        val socksPass: String = "",
    )

    sealed interface State {
        /** Ядро не поднимали. */
        data object Idle : State

        /** В этой сборке ядра olcrtc нет. */
        data object Unavailable : State

        data object Starting : State

        /** SOCKS5 поднят и транспорт подключён. */
        data object Ready : State

        data class Failed(val reason: String) : State
    }

    /**
     * Здоровье канала: прошли ли через комнату реальные байты.
     *
     * Отдельно от [State], потому что состояние отвечает только за факт старта.
     * `WaitReady` возвращается, когда собрался транспорт и поднялся SOCKS, а дальше
     * канал может умереть молча (05.08: `OpenStream failed: timeout` через 40 с после
     * «готов») — и экран продолжал бы врать «поднят».
     *
     * Ядро само здоровье живущей сессии не отдаёт: `IsRunning` показывает лишь то, что
     * горутина не вышла, а `Ping` и `Check` поднимают ОТДЕЛЬНОГО клиента на тот же порт
     * и заходят в комнату вторым участником — на работающей сессии их звать нельзя.
     * Поэтому спрашиваем то, что ядро реально отдаёт наружу: его SOCKS5.
     */
    sealed interface Health {
        /** Ещё не спрашивали. */
        data object Unknown : Health

        /** Через комнату прошёл запрос и вернулся ответ. */
        data class Live(val latencyMs: Long) : Health

        /** Ядро запущено, но данные не идут. */
        data class Dead(val reason: String) : Health
    }

    @Volatile
    var health: Health = Health.Unknown
        private set

    /** Когда последний раз проверяли (elapsedRealtime), 0 — никогда. */
    @Volatile
    var healthCheckedAt: Long = 0L
        private set

    @Volatile
    var state: State = State.Idle
        private set

    /** Последняя причина отказа — для показа человеку, без секретов. */
    @Volatile
    var lastError: String? = null
        private set

    private val mobileClass: Class<*>? by lazy {
        // Class.forName прогоняет статический инициализатор gomobile (загрузка libbox.so),
        // поэтому ловим Throwable: UnsatisfiedLinkError — не Exception.
        try {
            Class.forName(CLASS_MOBILE, true, OlcRtcCore::class.java.classLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "класс $CLASS_MOBILE недоступен: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Есть ли ядро olcrtc в этой сборке. */
    val isAvailable: Boolean get() = mobileClass != null

    /**
     * Поднимает ядро и дожидается готовности.
     *
     * @param protector `VpnService.protect(fd)`. Ядро зовёт его на каждый свой сокет,
     *   иначе трафик самого туннеля уйдёт в наш же tun и получится петля.
     * @param requireProtector в режиме VPN обязателен: без него стартовать нельзя.
     *   Инвариант держится здесь, а не на стороне вызова, чтобы его нельзя было забыть.
     * @return терминальное состояние: [State.Ready] либо причина отказа.
     */
    fun start(
        params: Params,
        protector: ((Int) -> Boolean)?,
        requireProtector: Boolean,
    ): State {
        val attempts = params.attempts.coerceAtLeast(1)
        var last: State = State.Idle
        for (attempt in 1..attempts) {
            last = startOnce(params, protector, requireProtector, attempt, attempts)
            if (last is State.Ready) return last
            // Причина не в комнате, а в самой сборке или параметрах — повтор ничего не даст.
            if (last is State.Unavailable || last is State.Failed && isPermanent(last.reason)) return last
            if (attempt < attempts) {
                Log.i(TAG, "попытка $attempt из $attempts не удалась (${lastError}), ждём ${params.retryPauseMillis} мс")
                runCatching { Thread.sleep(params.retryPauseMillis) }
            }
        }
        return last
    }

    /** Отказы, которые повтором не лечатся: чинить надо параметры или сборку. */
    private fun isPermanent(reason: String): Boolean =
        reason.startsWith("не задан") || reason.startsWith("порт SOCKS") ||
            reason.startsWith("ядро собрано без") || reason.startsWith("нет protect")

    private fun startOnce(
        params: Params,
        protector: ((Int) -> Boolean)?,
        requireProtector: Boolean,
        attempt: Int,
        attempts: Int,
    ): State {
        val mobile = mobileClass
        if (mobile == null) {
            return failed("ядро собрано без olcRTC (нет $CLASS_MOBILE)")
        }
        if (requireProtector && protector == null) {
            // Осознанный отказ: без protect(fd) при поднятом tun получится петля.
            return failed("нет protect(fd) для сокетов olcRTC — старт отменён, иначе петля через свой же tun")
        }
        validate(params)?.let { return failed(it) }

        state = State.Starting
        lastError = null
        return try {
            if (invokeIsRunning(mobile)) {
                Log.w(TAG, "ядро уже запущено, гасим прошлый запуск")
                invokeStop(mobile)
            }

            installLogWriter(mobile)
            installProtector(mobile, protector)

            invoke(mobile, "setProviders")
            invoke(mobile, "setTransport", arrayOf(String::class.java), params.transport)
            invoke(mobile, "setSocksListenHost", arrayOf(String::class.java), SOCKS_LISTEN_HOST)
            invoke(mobile, "setWBToken", arrayOf(String::class.java), params.wbToken)
            if (params.dnsServer.isNotBlank()) {
                invoke(mobile, "setDNS", arrayOf(String::class.java), params.dnsServer)
            }
            invoke(
                mobile,
                "setVP8Options",
                arrayOf(LONG, LONG),
                params.vp8Fps.toLong(),
                params.vp8BatchSize.toLong(),
            )
            invoke(
                mobile,
                "setLivenessOptions",
                arrayOf(LONG, LONG, LONG),
                LIVENESS_INTERVAL_MILLIS,
                LIVENESS_TIMEOUT_MILLIS,
                LIVENESS_FAILURES,
            )

            Log.i(
                TAG,
                "старт (попытка $attempt из $attempts, потолок ${params.readyTimeoutMillis} мс): " +
                    "carrier=${params.carrier} transport=${params.transport} " +
                    "socks=$SOCKS_LISTEN_HOST:${params.socksPort} room=${short(params.roomId)} " +
                    "client=${short(params.clientId)} key=${secretState(params.keyHex)} " +
                    "wbToken=${secretState(params.wbToken)} protect=${if (protector != null) "есть" else "нет"}",
            )

            val startedAt = android.os.SystemClock.elapsedRealtime()
            invoke(
                mobile,
                "start",
                arrayOf(
                    String::class.java, String::class.java, String::class.java, String::class.java,
                    LONG, String::class.java, String::class.java,
                ),
                params.carrier,
                params.roomId,
                params.clientId,
                params.keyHex,
                params.socksPort.toLong(),
                params.socksUser,
                params.socksPass,
            )

            // Start только запускает горутину; настоящие ошибки (нет комнаты, не пустили
            // по токену, не собрался транспорт) прилетают отсюда.
            invoke(mobile, "waitReady", arrayOf(LONG), params.readyTimeoutMillis.toLong())

            state = State.Ready
            health = Health.Unknown
            Log.i(
                TAG,
                "готов за ${android.os.SystemClock.elapsedRealtime() - startedAt} мс " +
                    "(попытка $attempt): SOCKS5 на $SOCKS_LISTEN_HOST:${params.socksPort}",
            )
            State.Ready
        } catch (t: Throwable) {
            val reason = reasonOf(t)
            // Не оставляем половину поднятого ядра: гасим то, что успело завестись.
            runCatching { invokeStop(mobile) }
            failed(reason)
        }
    }

    /**
     * Гасит ядро. Внутренний Stop блокируется до выхода клиентской горутины,
     * поэтому ждём его в отдельном потоке с потолком по времени: подвесить
     * остановку сервиса важнее, чем дождаться чистого выхода.
     */
    fun stop() {
        val mobile = mobileClass ?: return
        health = Health.Unknown
        healthCheckedAt = 0L
        if (!invokeIsRunning(mobile)) {
            state = State.Idle
            return
        }
        Log.i(TAG, "остановка ядра")
        val stopper = Thread({ runCatching { invokeStop(mobile) } }, "olcrtc-stop")
        stopper.isDaemon = true
        stopper.start()
        stopper.join(STOP_TIMEOUT_MILLIS)
        if (stopper.isAlive) {
            Log.w(TAG, "ядро не остановилось за ${STOP_TIMEOUT_MILLIS} мс, продолжаем без него")
        } else {
            Log.i(TAG, "ядро остановлено")
        }
        state = State.Idle
    }

    fun isRunning(): Boolean {
        val mobile = mobileClass ?: return false
        return invokeIsRunning(mobile)
    }

    /** Куда ходим, чтобы убедиться что канал живой: маленький ответ, обычный HTTP. */
    private const val PROBE_HOST = "ifconfig.me"
    private const val PROBE_PORT = 80
    private const val PROBE_PATH = "/ip"
    private const val PROBE_TIMEOUT_MILLIS = 8_000

    /**
     * Гонит через поднятый SOCKS5 живой запрос и меряет ответ. Блокирующий вызов,
     * звать только с фонового потока.
     *
     * Ходим руками по SOCKS5 с адресом-именем (ATYP=domain): так имя резолвит дальняя
     * сторона, и проверка меряет ровно путь через комнату, а не локальный DNS.
     */
    fun probe(socksPort: Int): Health {
        val mobile = mobileClass
        val result = when {
            mobile == null -> Health.Dead("ядра olcRTC в сборке нет")
            !invokeIsRunning(mobile) -> Health.Dead("ядро не запущено")
            else -> runProbe(socksPort)
        }
        health = result
        healthCheckedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "проверка канала: " + when (result) {
                is Health.Live -> "данные идут, ${result.latencyMs} мс"
                is Health.Dead -> "данных нет — ${result.reason}"
                Health.Unknown -> "неизвестно"
            },
        )
        return result
    }

    private fun runProbe(socksPort: Int): Health = try {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        java.net.Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            socket.connect(java.net.InetSocketAddress(SOCKS_LISTEN_HOST, socksPort), 2_000)
            val out = socket.getOutputStream()
            val input = java.io.DataInputStream(socket.getInputStream())

            // greeting: версия 5, один метод «без авторизации»
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2).also { input.readFully(it) }
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                return Health.Dead("SOCKS5 не принял приветствие")
            }

            val host = PROBE_HOST.toByteArray()
            out.write(
                byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host +
                    byteArrayOf((PROBE_PORT shr 8).toByte(), (PROBE_PORT and 0xff).toByte()),
            )
            out.flush()
            val reply = ByteArray(4).also { input.readFully(it) }
            if (reply[1].toInt() != 0x00) {
                return Health.Dead("комната не открыла соединение (код ${reply[1].toInt()})")
            }
            // хвост ответа: адрес привязки, длина зависит от типа
            when (reply[3].toInt()) {
                0x01 -> input.skipBytes(4 + 2)
                0x03 -> input.skipBytes(input.readUnsignedByte() + 2)
                0x04 -> input.skipBytes(16 + 2)
                else -> return Health.Dead("непонятный ответ SOCKS5")
            }

            out.write(
                (
                    "GET $PROBE_PATH HTTP/1.1\r\nHost: $PROBE_HOST\r\n" +
                        "User-Agent: kelevra\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
            val statusLine = input.readLine().orEmpty()
            if (!statusLine.startsWith("HTTP/1.")) {
                return Health.Dead("ответа нет")
            }
            Health.Live(android.os.SystemClock.elapsedRealtime() - startedAt)
        }
    } catch (t: Throwable) {
        Health.Dead(
            when (t) {
                is java.net.SocketTimeoutException -> "ответа не дождались"
                is java.net.ConnectException -> "SOCKS5 не отвечает"
                else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            },
        )
    }

    private fun validate(params: Params): String? = when {
        params.carrier.isBlank() -> "не задан carrier"
        params.roomId.isBlank() -> "не задан room id"
        params.clientId.isBlank() -> "не задан client id"
        params.keyHex.isBlank() -> "не задан ключ"
        params.socksPort !in 1..65535 -> "порт SOCKS вне диапазона: ${params.socksPort}"
        else -> null
    }

    private fun failed(reason: String): State {
        val newState = if (mobileClass == null) State.Unavailable else State.Failed(reason)
        state = newState
        lastError = reason
        Log.w(TAG, "olcRTC не поднят: $reason")
        return newState
    }

    private fun reasonOf(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    /**
     * Ядро печатает свои сообщения само, и в них попадают секреты: wbstream на гостевом
     * входе выкладывает в лог выданный JWT целиком («reuse it via auth.token»). В logcat
     * такому не место, поэтому вырезаем перед записью.
     */
    private val jwtRegex = Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]+""")
    private val tokenFieldRegex = Regex("""(?i)((?:auth\.)?(?:access[_-]?token|room[_-]?token|token)"?\s*[:=]\s*"?)[^",\s}]+""")
    private val longHexRegex = Regex("""\b[0-9a-fA-F]{32,}\b""")

    private fun redact(line: String): String = line
        .replace(jwtRegex, "<токен скрыт>")
        .replace(tokenFieldRegex) { it.groupValues[1] + "<скрыт>" }
        .replace(longHexRegex, "<ключ скрыт>")

    /** Секреты в лог не попадают: печатаем только факт наличия и длину. */
    private fun secretState(value: String): String = if (value.isBlank()) "пуст" else "задан (${value.length} симв.)"

    /** Room/client id не секрет, но и целиком в логе не нужны. */
    private fun short(value: String): String = if (value.length <= 8) value else value.take(8) + "…"

    private fun installLogWriter(mobile: Class<*>) {
        val writerClass = loadInterface(CLASS_LOG_WRITER) ?: return
        val writer = Proxy.newProxyInstance(
            writerClass.classLoader,
            arrayOf(writerClass),
        ) { _, method, args ->
            when (method.name) {
                "writeLog" -> {
                    val line = (args?.getOrNull(0) as? String)?.trimEnd()
                    if (!line.isNullOrBlank()) Log.i(CORE_TAG, redact(line))
                    null
                }

                "toString" -> "OlcRtcCore.logWriter"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }
        invoke(mobile, "setLogWriter", arrayOf(writerClass), writer)
    }

    private fun installProtector(mobile: Class<*>, protector: ((Int) -> Boolean)?) {
        val protectorClass = loadInterface(CLASS_PROTECTOR) ?: return
        if (protector == null) {
            invoke(mobile, "setProtector", arrayOf(protectorClass), *arrayOfNulls<Any?>(1))
            return
        }
        val proxy = Proxy.newProxyInstance(
            protectorClass.classLoader,
            arrayOf(protectorClass),
        ) { _, method, args ->
            when (method.name) {
                // Go int → Java long. Ошибку тут глотаем в false: ядро само откажется
                // от незащищённого сокета, это лучше падения в чужом потоке.
                "protect" -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt()
                    val ok = fd != null && runCatching { protector(fd) }.getOrElse {
                        Log.w(TAG, "protect(fd) сорвался: ${it.message}")
                        false
                    }
                    if (!ok) Log.w(TAG, "protect(fd=$fd) вернул false — сокет не защищён от своего tun")
                    ok
                }

                "toString" -> "OlcRtcCore.protector"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }
        invoke(mobile, "setProtector", arrayOf(protectorClass), proxy)
    }

    private fun loadInterface(name: String): Class<*>? = try {
        Class.forName(name, true, OlcRtcCore::class.java.classLoader)
    } catch (t: Throwable) {
        Log.w(TAG, "интерфейс $name недоступен: ${t.message}")
        null
    }

    private fun invokeIsRunning(mobile: Class<*>): Boolean = runCatching {
        method(mobile, "isRunning", emptyArray()).invoke(null) as? Boolean
    }.getOrNull() ?: false

    private fun invokeStop(mobile: Class<*>) {
        method(mobile, "stop", emptyArray()).invoke(null)
    }

    private fun invoke(mobile: Class<*>, name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        method(mobile, name, types).invoke(null, *args)

    private fun method(mobile: Class<*>, name: String, types: Array<Class<*>>): Method = mobile.getMethod(name, *types)
}
