package io.nekohasekai.sfa.bg

import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.database.Settings

/**
 * Присмотр за живым каналом olcRTC.
 *
 * Зачем. Комната умирает молча: ядро остаётся «запущенным» (`IsRunning` = true), а байты
 * через неё уже не ходят — 05.08 наблюдалось `OpenStream failed: timeout` через 40 с после
 * успешного старта. Экран это показывал честно, но сам канал так и лежал до тех пор, пока
 * человек не переподключится руками. Здесь тот же сигнал живости используется как повод
 * поднять ядро заново.
 *
 * Сигнал. [OlcRtcCore.probe] — реальный SOCKS5 CONNECT через комнату раз в
 * [CHECK_INTERVAL_MILLIS]. Ядро своего здоровья не отдаёт, а `Ping`/`Check` звать нельзя:
 * они поднимают отдельного клиента на тот же порт и заходят в комнату вторым участником.
 * Владелец проверки один — этот присмотр; экран только читает результат, иначе два
 * параллельных запроса и двойной счёт отказов.
 *
 * Почему не сразу после первого отказа: серверная нога перезаходит в комнату раз в 10 с,
 * и одиночный отказ — это чаще всего попадание в её пересборку, которая проходит сама.
 * Ждём [FAILURES_BEFORE_RESTART] отказов подряд.
 *
 * Почему с потолком: если комната умерла насовсем (ногу выключили, сервер убрал выход),
 * бесконечный цикл «подняться → упасть» жжёт батарею и трафик. После [MAX_RESTARTS]
 * подряд присмотр отходит и оставляет честное состояние. Счётчик обнуляется, когда канал
 * продержался живым [HEALTHY_RESET_MILLIS] — в длинной сессии редкие обрывы лечатся всегда.
 */
object OlcRtcWatchdog {
    private const val TAG = "OlcRtcWatchdog"

    /** Как часто спрашиваем канал. Тот же ритм, что был у экрана. */
    const val CHECK_INTERVAL_MILLIS = 5_000L

    /** Столько отказов подряд считаем смертью комнаты, а не её пересборкой. */
    const val FAILURES_BEFORE_RESTART = 3

    /** Потолок подъёмов подряд, без промежутка здоровья. */
    const val MAX_RESTARTS = 5

    /**
     * Паузы перед подъёмом, по номеру попытки. Первая почти сразу: нога возвращается
     * в комнату за 10 с, и к моменту, когда мы досчитали три отказа, она обычно уже там.
     * Дальше растёт, чтобы не долбить мёртвую комнату.
     */
    private val BACKOFF_MILLIS = longArrayOf(2_000, 10_000, 30_000, 60_000, 120_000)

    /** Сколько канал должен продержаться живым, чтобы забыть прошлые подъёмы. */
    private const val HEALTHY_RESET_MILLIS = 120_000L

    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var thread: Thread? = null

    /** Сколько раз подняли ядро в этой сессии. Для экрана и для лога. */
    @Volatile
    var restarts: Int = 0
        private set

    /** Присмотр упёрся в потолок и больше не поднимает. */
    @Volatile
    var gaveUp: Boolean = false
        private set

    /** Что делает присмотр прямо сейчас — словами, для экрана. */
    @Volatile
    var note: String = ""
        private set

    private var protector: ((Int) -> Boolean)? = null
    private var requireProtector: Boolean = false

    /**
     * Включает присмотр. Звать после того, как ядро реально поднялось.
     *
     * @param protector тот же `VpnService.protect(fd)`, что отдавали на первом старте:
     *   при подъёме сокеты создаются заново и защищать их надо так же.
     */
    fun start(protector: ((Int) -> Boolean)?, requireProtector: Boolean) {
        synchronized(lock) {
            stop()
            this.protector = protector
            this.requireProtector = requireProtector
            restarts = 0
            gaveUp = false
            note = ""
            active = true
            thread = Thread(::loop, "olcrtc-watchdog").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "присмотр включён: проверка раз в $CHECK_INTERVAL_MILLIS мс")
        }
    }

    /**
     * Гасит присмотр. Обязательно ДО [OlcRtcCore.stop], иначе обычная остановка сервиса
     * подерётся с подъёмом: присмотр увидит мёртвый канал ровно в момент выключения
     * и полезет поднимать ядро обратно.
     *
     * Ждём выхода недолго: поток может сидеть внутри `OlcRtcCore.start`, который держит
     * до полутора минут. Прерывания хватает, чтобы он не тронул ядро после нас — все
     * шаги подъёма сверяются с [active].
     */
    fun stop() {
        synchronized(lock) {
            if (!active && thread == null) return
            active = false
            val t = thread
            thread = null
            t?.interrupt()
            t?.join(1_000)
            note = ""
            Log.i(TAG, "присмотр выключен (подъёмов за сессию: $restarts)")
        }
    }

    private fun loop() {
        var failures = 0
        var lastRestartAt = 0L

        while (active) {
            if (!sleepQuietly(CHECK_INTERVAL_MILLIS)) return

            // Тумблер выключили посреди сессии: работающий канал не рвём, но и
            // поднимать его больше не наше дело.
            if (!Settings.olcrtcEnabled) {
                Log.i(TAG, "комната выключена тумблером — присмотр отходит")
                note = ""
                active = false
                return
            }

            // Ядро не в рабочем состоянии не по нашей вине (например, его гасят) —
            // не проверяем и не поднимаем.
            if (OlcRtcCore.state !is OlcRtcCore.State.Ready) continue

            val port = OlcRtcParams.socksPort
            val health = OlcRtcCore.probe(port)
            if (!active) return

            if (health is OlcRtcCore.Health.Live) {
                failures = 0
                if (restarts > 0 && lastRestartAt > 0 &&
                    SystemClock.elapsedRealtime() - lastRestartAt > HEALTHY_RESET_MILLIS
                ) {
                    Log.i(TAG, "канал держится дольше ${HEALTHY_RESET_MILLIS / 1000} с — счётчик подъёмов обнулён")
                    restarts = 0
                    lastRestartAt = 0L
                    note = ""
                }
                continue
            }

            failures++
            val reason = (health as? OlcRtcCore.Health.Dead)?.reason ?: "неизвестно"
            if (failures < FAILURES_BEFORE_RESTART) {
                Log.i(TAG, "канал не отвечает ($reason), отказ $failures из $FAILURES_BEFORE_RESTART")
                continue
            }

            if (restarts >= MAX_RESTARTS) {
                gaveUp = true
                note = "подняли $MAX_RESTARTS раз подряд, канал не встал — больше не пробуем"
                Log.w(TAG, note)
                active = false
                return
            }

            if (!restartCore(reason)) return
            failures = 0
            lastRestartAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Гасит ядро и поднимает заново.
     *
     * Параметры берём свежие: за время сессии сервер мог отдать другую комнату,
     * и подниматься в старую бессмысленно.
     *
     * @return false, если по дороге нас выключили — тогда из цикла надо просто выйти.
     */
    private fun restartCore(reason: String): Boolean {
        restarts++
        val pause = BACKOFF_MILLIS[(restarts - 1).coerceAtMost(BACKOFF_MILLIS.size - 1)]
        note = "канал упал ($reason), поднимаю заново — попытка $restarts из $MAX_RESTARTS"
        Log.w(TAG, "$note, пауза $pause мс")

        runCatching { OlcRtcCore.stop() }
            .onFailure { Log.w(TAG, "остановка перед подъёмом сорвалась: ${it.message}") }
        if (!active) return false
        if (!sleepQuietly(pause)) return false

        val params = OlcRtcParams.resolve()
        val result = runCatching {
            OlcRtcCore.start(params, protector, requireProtector)
        }.getOrElse { OlcRtcCore.State.Failed(it.message ?: it.javaClass.simpleName) }
        if (!active) return false

        when (result) {
            is OlcRtcCore.State.Ready -> {
                note = "канал поднят заново (попытка $restarts)"
                Log.i(TAG, "$note: SOCKS5 на 127.0.0.1:${params.socksPort}")
                // Сразу спрашиваем: «поднят» без прошедших байтов — это ещё не канал.
                OlcRtcCore.probe(params.socksPort)
            }

            else -> {
                note = "подъём $restarts из $MAX_RESTARTS не удался: ${OlcRtcCore.lastError}"
                Log.w(TAG, note)
            }
        }
        return active
    }

    /** @return false, если поток прервали — значит нас выключают. */
    private fun sleepQuietly(millis: Long): Boolean {
        if (millis <= 0) return active
        return try {
            Thread.sleep(millis)
            active
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
