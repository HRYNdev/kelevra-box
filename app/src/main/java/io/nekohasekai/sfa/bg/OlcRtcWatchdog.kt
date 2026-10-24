package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Pult

import android.os.SystemClock
import io.nekohasekai.sfa.bg.path.HonestProbe
import io.nekohasekai.sfa.bg.path.RoomNote
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
 * Ждём [FAILURES_BEFORE_RESTART] отказов подряд. Свежая комната, через которую ещё ничего не
 * прошло, получает окно «встаёт» ([GRACE_MILLIS]) и после него [FAILURES_UNPROVEN] отказов —
 * правило целиком в [condemnsRoom].
 *
 * Почему подъём не повторяется бесконечно часто: если комната умерла надолго (ногу
 * выключили, сервер убрал выход), частый цикл «подняться → упасть» жжёт батарею и трафик.
 * После [MAX_RESTARTS] подъёмов подряд присмотр переходит на редкие попытки раз в
 * [RARE_RETRY_MILLIS] — но не отходит насовсем, пока комната нужна. Раньше он отходил,
 * а после неудачного подъёма и вовсе переставал что-либо делать: ядро оставалось в
 * состоянии отказа, и присмотр пропускал ход за ходом. Нога комнаты пропадала на
 * несколько минут, возвращалась — а телефон больше двадцати минут не пытался войти,
 * пока приложение не перезапустили. Счётчик обнуляется, когда канал продержался живым
 * [HEALTHY_RESET_MILLIS] — в длинной сессии редкие обрывы лечатся частыми попытками.
 *
 * Что делать на каждом ходу, решает чистая функция [nextStep] — без Android, с тестами.
 */
object OlcRtcWatchdog {
    private const val TAG = "OlcRtcWatchdog"

    /** Как часто спрашиваем канал. Тот же ритм, что был у экрана. */
    const val CHECK_INTERVAL_MILLIS = 5_000L

    /** Столько отказов подряд считаем смертью комнаты, а не её пересборкой. */
    const val FAILURES_BEFORE_RESTART = 3

    /** Столько подъёмов подряд идут по нарастающей паузе; дальше — редкие попытки. */
    const val MAX_RESTARTS = 5

    /**
     * Пауза между редкими попытками, когда частые кончились.
     *
     * Две минуты — это цена ожидания для человека, у которого нога комнаты вернулась:
     * дольше он будет сидеть без комнаты, хотя она уже встаёт. Чаще — лишний вход в
     * чужой видеозвонок, который заведомо не встанет, а вход этот виден снаружи.
     */
    const val RARE_RETRY_MILLIS = 120_000L

    /**
     * Паузы перед подъёмом, по номеру попытки. Первая почти сразу: нога возвращается
     * в комнату за 10 с, и к моменту, когда мы досчитали три отказа, она обычно уже там.
     * Дальше растёт, чтобы не долбить мёртвую комнату.
     */
    private val BACKOFF_MILLIS = longArrayOf(2_000, 10_000, 30_000, 60_000, 120_000)

    /** Сколько канал должен продержаться живым, чтобы забыть прошлые подъёмы. */
    private const val HEALTHY_RESET_MILLIS = 120_000L

    /**
     * Окно «встаёт»: столько от Ready комнату не перезапускаем и выход с неё не уводим, пока
     * через неё не прошла ни одна проба.
     *
     * Под белым списком нога заходит в свежую комнату не сразу, и первые пробы врут подряд.
     * Раньше три отказа за 15 с после Ready давали перезапуск, а перезапуск уводил выход на
     * основной канал — мёртвый как раз под белым списком (24.10.2026). Окно не держит только
     * то, что подъёмом заведомо не встанет: ядро вышло само и локальный вход не отвечает.
     */
    const val GRACE_MILLIS = 180_000L

    /**
     * Столько отказов без единой удачи считаем смертью комнаты, когда окно вышло. Вдвое
     * больше обычного: живой комната ещё ни разу не была, и три отказа здесь — не обрыв.
     */
    const val FAILURES_UNPROVEN = 6

    /**
     * Мертва ли комната по пробам. Чистая функция — правило «окно, потом шесть без удачи,
     * после первой удачи три подряд» проверяется тестом.
     *
     * @param sinceReadyMillis сколько прошло с Ready, от которого считаем окно.
     * @param everLive с этого Ready хоть одна проба прошла.
     * @param failures отказов подряд (без удачи — это и все отказы с Ready).
     * @param refusedInARow из них подряд — «локальный вход не отвечает»: сокс ядра не слушает,
     *   ждать нечего, правило прежнее.
     */
    internal fun condemnsRoom(
        sinceReadyMillis: Long,
        everLive: Boolean,
        failures: Int,
        refusedInARow: Int,
        failuresBeforeRestart: Int = Pult.int("komnata.storozh.failures_before_restart", FAILURES_BEFORE_RESTART),
        graceMillis: Long = Pult.long("komnata.storozh.grace_millis", GRACE_MILLIS),
        failuresUnproven: Int = Pult.int("komnata.storozh.failures_unproven", FAILURES_UNPROVEN),
    ): Boolean = when {
        refusedInARow >= failuresBeforeRestart -> true
        everLive -> failures >= failuresBeforeRestart
        sinceReadyMillis < graceMillis -> false
        else -> failures >= failuresUnproven
    }

    /** Что присмотру делать на этом ходу. */
    internal enum class Step {
        /** Ничего: комната не нужна, её поднимает кто-то другой, её погасили нарочно или пауза не вышла. */
        Wait,

        /** Ядро стоит — спросить канал. */
        Probe,

        /** Прошлый подъём не удался и пауза вышла — поднимать снова. */
        Raise,
    }

    /**
     * Пауза перед подъёмом номер [attempt] (считая с единицы): частые — по нарастающей,
     * после [MAX_RESTARTS] — редкие.
     */
    internal fun pauseBeforeRaise(attempt: Int): Long =
        if (attempt > Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)) {
            Pult.long("komnata.storozh.rare_retry_millis", RARE_RETRY_MILLIS)
        } else {
            BACKOFF_MILLIS[(attempt - 1).coerceIn(0, BACKOFF_MILLIS.size - 1)]
        }

    /**
     * Решение хода. Вынесено отдельно и без единого обращения к Android: утверждение
     * «неудачный подъём не замораживает присмотр, а нарочно погашенное ядро не поднимается»
     * должно проверяться тестом, а не пересказом.
     *
     * @param state состояние ядра прямо сейчас.
     * @param roomNeeded комната нужна: туннель не погашен, сервис не останавливают, автомат
     *   стоит на комнате или ищет путь (или комнату выбрал человек).
     * @param raisingElsewhere комнату прямо сейчас поднимает сервис своим потоком — второй
     *   подъём поверх рвал бы первый: `OlcRtcCore.start` гасит чужой запуск.
     * @param raisesInARow сколько подъёмов подряд уже было без промежутка здоровья.
     * @param sinceLastRaiseMillis сколько прошло с конца последнего подъёма (или с момента,
     *   когда присмотр впервые увидел чужую неудачу).
     * @param permanent отказ повтором не лечится быстро (носитель отверг токен): частыми
     *   попытками долбиться незачем, остаются только редкие.
     */
    internal fun nextStep(
        state: OlcRtcCore.State,
        roomNeeded: Boolean,
        raisingElsewhere: Boolean,
        raisesInARow: Int,
        sinceLastRaiseMillis: Long,
        permanent: Boolean = false,
    ): Step = when {
        // Стоящее ядро спрашиваем всегда, как и раньше: по этой пробе живёт вердикт
        // автомата о комнате ([OlcRtcCore.health]), в том числе во время пробного подъёма.
        state is OlcRtcCore.State.Ready -> Step.Probe
        state is OlcRtcCore.State.Starting -> Step.Wait
        !roomNeeded -> Step.Wait
        raisingElsewhere -> Step.Wait
        // Idle — ядро погасили нарочно (остановка сервиса, уход домой, комната больше не
        // нужна); Unavailable — olcRTC в этой сборке нет. Подъёмом не лечится ни то, ни другое.
        state !is OlcRtcCore.State.Failed -> Step.Wait
        sinceLastRaiseMillis >= (if (permanent) Pult.long("komnata.storozh.rare_retry_millis", RARE_RETRY_MILLIS) else pauseBeforeRaise(raisesInARow + 1)) -> Step.Raise
        else -> Step.Wait
    }

    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var thread: Thread? = null

    /** Сколько раз подняли ядро в этой сессии. Для экрана и для лога. */
    @Volatile
    var restarts: Int = 0
        private set

    /** Частые подъёмы кончились, присмотр перешёл на редкие попытки. */
    @Volatile
    var gaveUp: Boolean = false
        private set

    /** Что делает присмотр прямо сейчас — словами, для экрана. */
    @Volatile
    var note: String = ""
        private set

    /**
     * Сколько отказов подряд насчитал присмотр. Ноль — канал отвечает.
     *
     * Наружу нужен ровно для одного вопроса: «это приговор или комната ещё моргает».
     * Первая же проба только что поднятой комнаты врёт чаще всего — нога заходит в неё
     * до десяти секунд, — и решать по ней нельзя (прогон 09.08.2026: автомат погасил
     * комнату через 31 секунду после подъёма по первому отказу и заплатил вторым
     * подъёмом). Владелец счёта один, здесь.
     */
    @Volatile
    var deadInARow: Int = 0
        private set

    /**
     * Приговор по пробам ([condemnsRoom]) на последнем ходу. Держим готовым, а не считаем
     * в [condemned]: правило зависит от окна «встаёт», а часы — забота хода присмотра.
     */
    @Volatile
    private var judgedDead: Boolean = false

    /**
     * Присмотр вынес приговор: канал не отвечает столько раз подряд, что это уже не
     * пересборка на той стороне, — либо частые подъёмы кончились. В окне «встаёт»
     * ([GRACE_MILLIS]) приговора нет: автомат по нему гасит комнату.
     */
    val condemned: Boolean get() = gaveUp || judgedDead

    private var protector: ((Int) -> Boolean)? = null
    private var requireProtector: Boolean = false

    @Volatile
    private var roomNeeded: () -> Boolean = { true }

    @Volatile
    private var raisingElsewhere: () -> Boolean = { false }

    /**
     * Включает присмотр. Звать после того, как ядро реально поднялось.
     *
     * @param protector тот же `VpnService.protect(fd)`, что отдавали на первом старте:
     *   при подъёме сокеты создаются заново и защищать их надо так же.
     * @param roomNeeded нужна ли комната сейчас — без этого после неудачного подъёма
     *   присмотр не отличил бы «ждём ногу» от «комната больше никому не нужна».
     * @param raisingElsewhere сервис прямо сейчас поднимает комнату сам.
     */
    fun start(
        protector: ((Int) -> Boolean)?,
        requireProtector: Boolean,
        roomNeeded: () -> Boolean = { true },
        raisingElsewhere: () -> Boolean = { false },
    ) {
        synchronized(lock) {
            stop()
            this.protector = protector
            this.requireProtector = requireProtector
            this.roomNeeded = roomNeeded
            this.raisingElsewhere = raisingElsewhere
            restarts = 0
            gaveUp = false
            deadInARow = 0
            judgedDead = false
            // Зовут, когда ядро уже поднялось: окно «встаёт» считаем отсюда.
            readyAt = SystemClock.elapsedRealtime()
            everLive = false
            note = ""
            active = true
            thread = Thread(::loop, "olcrtc-watchdog").apply {
                isDaemon = true
                start()
            }
            KLog.i(TAG, "присмотр включён: проверка раз в ${Pult.long("komnata.storozh.check_interval_millis", CHECK_INTERVAL_MILLIS)} мс")
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
            KLog.i(TAG, "присмотр выключен (подъёмов за сессию: $restarts)")
        }
    }

    /** Как часто в паузе между пробами спрашивать само ядро, живо ли оно. */
    private const val SOCKS_WATCH_STEP_MILLIS = 1_000L

    /** Про выход ядра уже сказали — не повторять каждую секунду, пока проба не отработает. */
    @Volatile
    private var socksLostReported = false

    /** С какого Ready считаем окно «встаёт» ([SystemClock.elapsedRealtime]); 0 — ядро не стоит. */
    @Volatile
    private var readyAt = 0L

    /** С этого Ready через комнату прошла хоть одна проба. */
    @Volatile
    private var everLive = false

    /**
     * Присмотр прямо сейчас гасит и поднимает ядро. Сервис по нему не запускает второй
     * подъём поверх: `OlcRtcCore.start` гасит чужой запуск, и два подъёма рвали друг друга.
     */
    @Volatile
    var restarting = false
        private set

    /**
     * Пауза между пробами — с присмотром за самим ядром.
     *
     * Горутина olcRTC может выйти сама: сокс закрывается, а состояние остаётся Ready.
     * Раньше это находила только проба через пять секунд и три отказа подряд — около
     * пятнадцати секунд, за которые в мёртвый порт уходили тысячи соединений. Теперь
     * выход уводится в пределах секунды, а проба дальше решает, поднимать ли заново.
     */
    private fun sleepWatchingSocks(): Boolean {
        var left = Pult.long("komnata.storozh.check_interval_millis", CHECK_INTERVAL_MILLIS)
        while (left > 0) {
            val step = minOf(SOCKS_WATCH_STEP_MILLIS, left)
            if (!sleepQuietly(step)) return false
            left -= step
            if (OlcRtcCore.state is OlcRtcCore.State.Ready && !OlcRtcCore.isRunning()) {
                if (!socksLostReported) {
                    socksLostReported = true
                    KLog.w(TAG, "ядро комнаты вышло само — увожу выход, не дожидаясь пробы")
                    AutoMode.roomLost("ядро комнаты вышло само")
                }
                return true
            }
        }
        return true
    }

    private fun loop() {
        var failures = 0
        var refused = 0
        var lastRestartAt = 0L
        // Когда кончился последний подъём, удачный или нет. От него отмеряется пауза до
        // следующей попытки, пока ядро лежит в отказе.
        var raiseEndedAt = 0L

        while (active) {
            if (!sleepWatchingSocks()) return

            // Тумблер выключили посреди сессии: работающий канал не рвём, но и
            // поднимать его больше не наше дело.
            if (!Settings.olcrtcEnabled || !OlcRtcParams.komnataRazreshenaPultom) {
                KLog.i(TAG, "комната выключена тумблером или пультом — присмотр отходит")
                note = ""
                active = false
                return
            }

            val state = OlcRtcCore.state
            val now = SystemClock.elapsedRealtime()
            // Отказ мог оставить и чужой подъём (сервис по просьбе автомата). Паузу тогда
            // отмеряем от момента, когда мы его увидели, а не поднимаем в ту же секунду.
            if (state is OlcRtcCore.State.Failed && raiseEndedAt == 0L) raiseEndedAt = now
            // Окно «встаёт» живёт от Ready до Ready: ядро легло — следующее Ready (наше или
            // поднятое сервисом) начнёт его заново.
            if (state !is OlcRtcCore.State.Ready) {
                readyAt = 0L
                everLive = false
            } else if (readyAt == 0L) {
                readyAt = now
            }
            val step = nextStep(
                state = state,
                roomNeeded = runCatching { roomNeeded() }.getOrDefault(false),
                raisingElsewhere = runCatching { raisingElsewhere() }.getOrDefault(false),
                raisesInARow = restarts,
                sinceLastRaiseMillis = now - raiseEndedAt,
                permanent = (state as? OlcRtcCore.State.Failed)?.reason?.let(OlcRtcCore::tokenOtvergnut) == true,
            )

            when (step) {
                Step.Wait -> continue

                Step.Raise -> {
                    if (restarts >= Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS) && !gaveUp) {
                        gaveUp = true
                        KLog.w(
                            TAG,
                            "подняли ${Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)} раз подряд, канал не встал — дальше пробую раз в " +
                                "${Pult.long("komnata.storozh.rare_retry_millis", RARE_RETRY_MILLIS) / 1000} с, пока комната нужна",
                        )
                    }
                    // Ядро уже лежит, гасить нечего и ждать внутри подъёма тоже: пауза
                    // выдержана здесь, ходами присмотра.
                    if (!restartCore(OlcRtcCore.lastError ?: "подъём не удался", pauseMillis = 0L, channelWasUp = false)) return
                    raiseEndedAt = SystemClock.elapsedRealtime()
                    lastRestartAt = raiseEndedAt
                    failures = 0
                    refused = 0
                    deadInARow = 0
                    judgedDead = false
                    continue
                }

                Step.Probe -> Unit
            }

            val port = OlcRtcParams.socksPort
            val health = OlcRtcCore.probe(port)
            if (!active) return
            // Померил — записал. Раньше присмотр держал вердикт при себе, а реестр про
            // комнату обновлял только круг автомата: обрыв на 83 секунды (прогон 08.08.2026)
            // присмотр вылечил сам, а человек всё это время читал «Подключено».
            RoomNote.note(OlcRtcCore.state, health)

            if (health is OlcRtcCore.Health.Live) {
                failures = 0
                refused = 0
                deadInARow = 0
                judgedDead = false
                everLive = true
                socksLostReported = false
                gaveUp = false
                if (restarts > 0 && lastRestartAt > 0 &&
                    SystemClock.elapsedRealtime() - lastRestartAt > Pult.long("komnata.storozh.healthy_reset_millis", HEALTHY_RESET_MILLIS)
                ) {
                    KLog.i(TAG, "канал держится дольше ${Pult.long("komnata.storozh.healthy_reset_millis", HEALTHY_RESET_MILLIS) / 1000} с — счётчик подъёмов обнулён")
                    restarts = 0
                    lastRestartAt = 0L
                    note = ""
                }
                continue
            }

            failures++
            deadInARow = failures
            val reason = (health as? OlcRtcCore.Health.Dead)?.reason ?: "неизвестно"
            refused = if (reason == HonestProbe.ENTRY_REFUSED) refused + 1 else 0
            val sinceReady = if (readyAt == 0L) 0L else SystemClock.elapsedRealtime() - readyAt
            judgedDead = condemnsRoom(sinceReady, everLive, failures, refused)
            if (!judgedDead) {
                val porog = if (everLive || refused > 0) {
                    Pult.int("komnata.storozh.failures_before_restart", FAILURES_BEFORE_RESTART)
                } else {
                    Pult.int("komnata.storozh.failures_unproven", FAILURES_UNPROVEN)
                }
                val okno = if (!everLive && refused == 0 && sinceReady < Pult.long("komnata.storozh.grace_millis", GRACE_MILLIS)) {
                    ", комната ещё встаёт (${sinceReady / 1000} из ${Pult.long("komnata.storozh.grace_millis", GRACE_MILLIS) / 1000} с) — не трогаю"
                } else {
                    ""
                }
                KLog.i(TAG, "канал не отвечает ($reason), отказ $failures из $porog$okno")
                continue
            }

            if (restarts >= Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)) {
                gaveUp = true
                if (!runCatching { roomNeeded() }.getOrDefault(false)) {
                    // Комната никому не нужна — редкие попытки держать незачем, отходим,
                    // как и раньше.
                    note = "подняли ${Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)} раз подряд, канал не встал, комната не нужна — больше не пробуем"
                    KLog.w(TAG, note)
                    // Выход мог вернуться на неё заходом автомата, пока ядро числилось
                    // поднятым: уводим, иначе он там и останется.
                    AutoMode.roomLost("присмотр сдался")
                    active = false
                    return
                }
                KLog.w(TAG, "подняли ${Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)} раз подряд, канал не встал — дальше пробую раз в ${Pult.long("komnata.storozh.rare_retry_millis", RARE_RETRY_MILLIS) / 1000} с")
            }

            if (!restartCore(reason, pauseBeforeRaise(restarts + 1), channelWasUp = true)) return
            raiseEndedAt = SystemClock.elapsedRealtime()
            lastRestartAt = raiseEndedAt
            failures = 0
            refused = 0
            deadInARow = 0
            judgedDead = false
        }
    }

    /**
     * Гасит ядро (если оно стоит) и поднимает заново.
     *
     * Параметры берём свежие: за время сессии сервер мог отдать другую комнату,
     * и подниматься в старую бессмысленно.
     *
     * @return false, если по дороге нас выключили — тогда из цикла надо просто выйти.
     */
    private fun restartCore(reason: String, pauseMillis: Long, channelWasUp: Boolean): Boolean = try {
        restartCore0(reason, pauseMillis, channelWasUp)
    } finally {
        restarting = false
    }

    private fun restartCore0(reason: String, pauseMillis: Long, channelWasUp: Boolean): Boolean {
        // Сперва занимаем подъём, потом смотрим на сервис. Сервис делает то же самое в
        // обратную сторону (свой флаг, потом наш), поэтому хотя бы один из двух видит
        // другого, и два подъёма разом не начинаются; хуже — оба уступят, и следующий ход
        // попробует снова.
        restarting = true
        if (runCatching { raisingElsewhere() }.getOrDefault(false)) {
            KLog.i(TAG, "комнату сейчас поднимает сервис — свой подъём не запускаю")
            return active
        }

        restarts++
        note = if (restarts > Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)) {
            "канал не встаёт ($reason) — редкая попытка, раз в ${Pult.long("komnata.storozh.rare_retry_millis", RARE_RETRY_MILLIS) / 1000} с (подъём $restarts)"
        } else {
            "канал упал ($reason), поднимаю заново — попытка $restarts из ${Pult.int("komnata.storozh.max_restarts", MAX_RESTARTS)}"
        }
        KLog.w(TAG, "$note, пауза $pauseMillis мс")

        if (channelWasUp) {
            // Сначала уводим выход, потом гасим. Раньше порядок был обратный, и всё время
            // подъёма — пауза плюс до трёх попыток по 25 секунд — трафик получал отказ за
            // отказом в погашенный сокс. Куда уводить, решает [AutoMode.roomLostTarget]: при
            // мёртвом основном — на WB, если он жив, иначе никуда, но не на основной.
            AutoMode.roomLost("присмотр поднимает комнату заново: $reason")
            runCatching { OlcRtcCore.stop() }
                .onFailure { KLog.w(TAG, "остановка перед подъёмом сорвалась: ${it.message}") }
        }
        // Ядро погашено нарочно и сейчас встанет заново. По одному только состоянию это
        // не отличить от «комнату не поднимали», а для человека разница вся: «поднимаю
        // комнату» вместо «не проверяли».
        RoomNote.raising()
        if (!active) return false
        if (!sleepQuietly(pauseMillis)) return false

        val result = runCatching {
            val params = OlcRtcParams.resolve()
            OlcRtcCore.start(
                params, protector, requireProtector,
                stillNeeded = { active && runCatching { roomNeeded() }.getOrDefault(false) },
            )
        }.getOrElse { OlcRtcCore.State.Failed(it.message ?: it.javaClass.simpleName) }
        if (!active) return false

        when (result) {
            is OlcRtcCore.State.Ready -> {
                socksLostReported = false
                note = "канал поднят заново (попытка $restarts)"
                val port = OlcRtcParams.socksPort
                KLog.i(TAG, "$note: SOCKS5 на 127.0.0.1:$port")
                // Сразу спрашиваем: «поднят» без прошедших байтов — это ещё не канал.
                val health = OlcRtcCore.probe(port)
                RoomNote.note(OlcRtcCore.state, health)
                // Новое Ready — новое окно «встаёт».
                readyAt = SystemClock.elapsedRealtime()
                everLive = health is OlcRtcCore.Health.Live
                if (health is OlcRtcCore.Health.Live) gaveUp = false
                // Выход при подъёме увели с комнаты. Ждать очередного шага ритма автомата
                // (в комнате — до трёх минут), чтобы вернуть его, незачем.
                AutoMode.onRoomRaised("присмотр поднял комнату, попытка $restarts")
            }

            else -> {
                val next = pauseBeforeRaise(restarts + 1)
                note = "подъём $restarts не удался: ${OlcRtcCore.lastError} — следующая попытка через ${next / 1000} с"
                KLog.w(TAG, note)
                RoomNote.note()
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
