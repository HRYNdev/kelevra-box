package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Броня против молчащих резолверов — проверка по сценариям, а не по строчкам.
 *
 * Беда разобрана приборно 28.08.2026 по четырём независимым источникам (logcat телефона,
 * querylog AdGuard, syslog роутера, syslog репитера): телефон роумит между репитером и
 * основной точкой, старая точка держит его ассоциацию и запись в мосту до 480 секунд
 * после ухода, L2-путь протухает — и **DNS немой 4-7 минут при живом интернете**. За
 * сутки роутер зафиксировал 14 циклов ухода/возврата, провал DNS есть в 11 из 14 (во
 * всех, где разрыв длиннее ~40 с). Контрольный клиент .105 за те же сутки: 21374
 * запроса, ни одного провала. Android при этом смены сети не видит вовсе — один и тот же
 * NetworkAgent переживает четыре роутерных цикла подряд.
 *
 * Логика приложения была **исправна**: она честно не могла подтвердить дом и уходила в
 * туннель. Чинится поэтому не вердикт, а устойчивость к молчанию:
 *
 *  1. резолверы молчат → «не знаю», а не «не дома»;
 *  2. пока сеть та же и мы стоим на рабочем выходе — вердикт не меняется вовсе;
 *  3. слепому заходу не хватает одного подтверждения: нужны полные три.
 *
 * Проверяем это игрушечным автоматом [Avtomat] ниже: он собран из тех же чистых функций,
 * что стоят в живом заходе ([AutoMode.homeVerdict], [AutoMode.decide],
 * [AutoMode.holdsVerdict], [AutoModeGate]), и считает единственное, что важно человеку, —
 * **сколько раз дёрнулся туннель**.
 */
class AutoModeSilenceTest {

    // -------------------------------------------------- «не знаю» вместо «не дома»

    @Test
    fun `резолверы молчат, сеть та же, стоим на рабочем выходе — вердикт держим`() {
        assertTrue(
            AutoMode.holdsVerdict(
                silent = true,
                observed = Situation.Main,
                current = Situation.Home,
                sameNetwork = true,
                networkChanged = false,
                holdAgeMillis = 0L,
            ),
        )
    }

    @Test
    fun `резолвер ответил — всё как раньше, броня не вмешивается`() {
        // Главное условие хозяина: меняется ровно один случай — когда резолверы молчат.
        assertFalse(
            AutoMode.holdsVerdict(
                silent = false,
                observed = Situation.Main,
                current = Situation.Home,
                sameNetwork = true,
                networkChanged = false,
                holdAgeMillis = 0L,
            ),
        )
    }

    @Test
    fun `сеть доказанно сменилась — держаться за старый вердикт не за что`() {
        assertFalse(
            AutoMode.holdsVerdict(
                silent = true,
                observed = Situation.Main,
                current = Situation.Home,
                sameNetwork = true,
                networkChanged = true,
                holdAgeMillis = 0L,
            ),
        )
        // И то же самое, когда отпечаток сети просто другой: событие могло не прийти,
        // а сеть под нами уже не та, на которой обстановка объявлялась.
        assertFalse(
            AutoMode.holdsVerdict(
                silent = true,
                observed = Situation.Main,
                current = Situation.Home,
                sameNetwork = false,
                networkChanged = false,
                holdAgeMillis = 0L,
            ),
        )
    }

    @Test
    fun `стоим не на рабочем выходе — беречь нечего`() {
        // «Ничего не поднимается» и «неизвестно» держать нельзя: человек из них сам не
        // выйдет, и молчание резолвера заперло бы его там навсегда.
        for (current in listOf(Situation.Searching, Situation.Unknown, Situation.NoNetwork)) {
            assertFalse(
                "на $current держать нечего",
                AutoMode.holdsVerdict(
                    silent = true,
                    observed = Situation.Main,
                    current = current,
                    sameNetwork = true,
                    networkChanged = false,
                    holdAgeMillis = 0L,
                ),
            )
        }
        assertTrue(AutoMode.working(Situation.Home))
        assertTrue(AutoMode.working(Situation.Main))
        assertTrue(AutoMode.working(Situation.Room))
        assertFalse(AutoMode.working(Situation.Searching))
        assertFalse(AutoMode.working(Situation.NoNetwork))
        assertFalse(AutoMode.working(Situation.Unknown))
    }

    @Test
    fun `молчание навсегда не замораживает автомат навсегда`() {
        // Предохранитель, а не настройка: он с запасом больше самого долгого замеренного
        // провала (13 минут 28.08.2026), чтобы в этой беде не срабатывать вовсе.
        assertTrue(
            "срок брони обязан перекрывать замеренные провалы DNS",
            AutoMode.SILENCE_HOLD_MILLIS > 13 * 60_000L,
        )
        assertFalse(
            AutoMode.holdsVerdict(
                silent = true,
                observed = Situation.Main,
                current = Situation.Home,
                sameNetwork = true,
                networkChanged = false,
                holdAgeMillis = AutoMode.SILENCE_HOLD_MILLIS,
            ),
        )
    }

    // ----------------------------------------- дыра с одним подтверждением вместо трёх

    @Test
    fun `слепому заходу не хватает одного подтверждения — нужны полные три`() {
        // Дыра-аналог десктопной: поблажки задвижки выданы под «наблюдение свежее и
        // потому ценное». Заход, который ничего не узнал, свежим быть не может.
        val gate = AutoModeGate(AutoMode.CONFIRMATIONS)
        gate.reset(Situation.Home)

        // Раньше это одно наблюдение переключало обстановку сразу.
        assertFalse(gate.offer(Situation.Main, trust = true, blind = true))
        assertFalse(gate.offer(Situation.Main, trust = true, blind = true))
        assertEquals(Situation.Home, gate.current)
        assertTrue(gate.offer(Situation.Main, trust = true, blind = true))
        assertEquals(Situation.Main, gate.current)
    }

    @Test
    fun `слепой заход не получает поблажки и внутри серии перепроверок`() {
        val gate = AutoModeGate(AutoMode.CONFIRMATIONS)
        gate.reset(Situation.Home)

        assertFalse(gate.offer(Situation.Main, hurried = true, blind = true))
        assertFalse(gate.offer(Situation.Main, hurried = true, blind = true))
        assertTrue(gate.offer(Situation.Main, hurried = true, blind = true))
    }

    @Test
    fun `зрячий заход поблажки получает ровно как раньше`() {
        // Проверка «ничего не сломали»: без слепоты поведение задвижки прежнее.
        val trusted = AutoModeGate(AutoMode.CONFIRMATIONS)
        trusted.reset(Situation.Home)
        assertTrue(trusted.offer(Situation.Main, trust = true))

        val hurried = AutoModeGate(AutoMode.CONFIRMATIONS)
        hurried.reset(Situation.Home)
        assertFalse(hurried.offer(Situation.Main, hurried = true))
        assertTrue(hurried.offer(Situation.Main, hurried = true))

        // И «сети нет» по-прежнему не ждёт подтверждений даже вслепую: действие всё
        // равно «ничего не делать».
        val gone = AutoModeGate(AutoMode.CONFIRMATIONS)
        gone.reset(Situation.Home)
        assertTrue(gone.offer(Situation.NoNetwork, blind = true))
    }

    // --------------------------------------------------------- сценарии целиком

    @Test
    fun `резолвер ответил чужой сетью — «не дома», как раньше`() {
        val avtomat = Avtomat(network = HOME_NET, start = Situation.Home)

        // Настоящие адреса вместо подменных — это ответ по существу, а не молчание.
        // Заходов нужно пять, и это прежнее поведение, а не следствие правки: одна
        // сводка признак дома не отменяет, он живёт [HomeSign.MEMORY_MILLIS] = 45 с
        // (два захода по 20 с), и только потом задвижка набирает свои три подтверждения.
        repeat(5) {
            avtomat.round(dns = HomeSign.Sign.No, traffic = true, mainWorks = true, elapsed = 20_000L)
        }

        assertEquals(Situation.Main, avtomat.situation)
        assertTrue("туннель обязан подняться", avtomat.tunnelUp)
        assertEquals(1, avtomat.toggles)
    }

    @Test
    fun `резолвер ответил домашней сетью — «дома», как раньше`() {
        val avtomat = Avtomat(network = HOME_NET, start = Situation.Main, tunnelUp = true)

        repeat(AutoMode.CONFIRMATIONS) {
            avtomat.round(dns = HomeSign.Sign.Yes, traffic = true, mainWorks = true, elapsed = 12_000L)
        }

        assertEquals(Situation.Home, avtomat.situation)
        assertFalse("дома туннель гасим", avtomat.tunnelUp)
        assertEquals(1, avtomat.toggles)
    }

    @Test
    fun `роуминг 28 августа — дома, пять минут молчания, снова дома, туннель не дёрнулся`() {
        val avtomat = Avtomat(network = HOME_NET, start = Situation.Home)

        // 22:47..23:03 — дома, всё честно: подменные адреса и живой трафик.
        avtomat.round(dns = HomeSign.Sign.Yes, traffic = true, mainWorks = true, elapsed = 60_000L)
        assertEquals(Situation.Home, avtomat.situation)

        // 23:03:55 — DNS умер. Пять минут молчания при живом интернете: резолверы не
        // отвечают ни на один домен, прямой замер не состоится (имя цели не узнать),
        // а основной канал при этом поднимается — по адресу, без имени.
        //
        // Заходов за эти пять минут много: приглядка дома будит полный заход раз в
        // 40-70 секунд, а пока задвижка ждала подтверждений — раз в 12. Берём худший
        // случай и гоняем каждые 12 секунд.
        repeat(25) {
            avtomat.round(dns = HomeSign.Sign.Unknown, traffic = null, mainWorks = true, elapsed = 12_000L)
            assertEquals("во время молчания вердикт меняться не имеет права", Situation.Home, avtomat.situation)
        }

        // 23:10:42 — DNS ожил, роутер снова отвечает подменными адресами.
        avtomat.round(dns = HomeSign.Sign.Yes, traffic = true, mainWorks = true, elapsed = 12_000L)

        assertEquals(Situation.Home, avtomat.situation)
        assertFalse(avtomat.tunnelUp)
        assertEquals("туннель не должен дёрнуться ни разу", 0, avtomat.toggles)
    }

    @Test
    fun `так это выглядело до брони — тот же сценарий поднимал туннель`() {
        // Контрольный прогон: та же последовательность через задвижку без брони. Нужен,
        // чтобы тест выше проверял поведение, а не сам себя: без правки он падает.
        val gate = AutoModeGate(AutoMode.CONFIRMATIONS)
        gate.reset(Situation.Home)
        var switched = false
        repeat(AutoMode.CONFIRMATIONS) {
            // Молчание сворачивалось в «дома нет» → «работает основной канал».
            if (gate.offer(Situation.Main)) switched = true
        }
        assertTrue("до правки молчание уводило в туннель", switched)
        assertEquals(Situation.Main, gate.current)
    }

    @Test
    fun `настоящий уход из дома молчание не задерживает`() {
        // Проверка «не сломали обратное»: ушли с домашнего вайфая на чужой. Событие
        // смены сети приходит, отпечаток другой — броня не держит ничего.
        val avtomat = Avtomat(network = HOME_NET, start = Situation.Home)
        avtomat.round(dns = HomeSign.Sign.Yes, traffic = true, mainWorks = true, elapsed = 60_000L)

        avtomat.round(
            network = "wifi|8.8.8.8",
            dns = HomeSign.Sign.Unknown,
            traffic = null,
            mainWorks = true,
            networkChanged = true,
            elapsed = 1_000L,
        )
        // Заход слепой, поэтому поблажки за смену сети нет — но и держать нечего:
        // задвижка честно набирает подтверждения и уводит в туннель.
        avtomat.round(network = "wifi|8.8.8.8", dns = HomeSign.Sign.Unknown, traffic = null, mainWorks = true, elapsed = 12_000L)
        avtomat.round(network = "wifi|8.8.8.8", dns = HomeSign.Sign.Unknown, traffic = null, mainWorks = true, elapsed = 12_000L)

        assertEquals(Situation.Main, avtomat.situation)
        assertTrue(avtomat.tunnelUp)
    }

    // ------------------------------------------------------------ игрушечный автомат

    private companion object {
        /** Отпечаток домашней сети: транспорт и резолверы, как в [AutoMode] `networkKey`. */
        const val HOME_NET = "wifi|192.168.1.192"
    }

    /**
     * Заход автомата, собранный из тех же чистых функций, что стоят в живом коде.
     *
     * Android тут нет ни строчки: сеть — это строка-отпечаток, время — счётчик, туннель —
     * булев флаг. Считаем то, что чувствует человек: [toggles] — сколько раз дёрнулся
     * туннель.
     */
    private class Avtomat(
        network: String,
        start: Situation,
        tunnelUp: Boolean = start != Situation.Home,
    ) {
        private val gate = AutoModeGate(AutoMode.CONFIRMATIONS)
        private var situationNetwork: String? = network
        private var homeSignAt: Long? = 0L
        private var holdAt: Long? = null
        private var now = 0L

        var tunnelUp: Boolean = tunnelUp
            private set

        var toggles = 0
            private set

        val situation: Situation get() = gate.current

        init {
            gate.reset(start)
        }

        /**
         * @param dns что сказала сводка DNS этого захода.
         * @param traffic прошёл ли наружу прямой запрос; `null` — замер не состоялся
         *   (имя цели не узнать, ровно это и бывает при молчащем резолвере).
         * @param mainWorks поднимается ли основной канал. По адресу, без имени —
         *   поэтому при мёртвом DNS он честно работает.
         */
        fun round(
            dns: HomeSign.Sign,
            traffic: Boolean?,
            mainWorks: Boolean,
            network: String = HOME_NET,
            networkChanged: Boolean = false,
            elapsed: Long = 12_000L,
        ) {
            now += elapsed
            if (networkChanged) {
                // Смена сети стирает память о признаке дома — так же, как в живом коде.
                homeSignAt = null
            }
            if (dns == HomeSign.Sign.Yes) homeSignAt = now
            if (traffic == false) homeSignAt = null

            val dnsHome = HomeSign.stands(
                seenNow = dns,
                ageMillis = homeSignAt?.let { now - it },
                refuted = traffic == false,
            )
            val home = AutoMode.homeVerdict(dnsHome, traffic, NetworkMode.Normal)
            val observed = AutoMode.decide(hasNetwork = true, home = home, main = mainWorks, room = false)
            val silent = dns == HomeSign.Sign.Unknown

            if (AutoMode.holdsVerdict(
                    silent = silent,
                    observed = observed,
                    current = gate.current,
                    sameNetwork = network == situationNetwork,
                    networkChanged = networkChanged,
                    holdAgeMillis = holdAt?.let { now - it } ?: 0L,
                )
            ) {
                if (holdAt == null) holdAt = now
                return
            }
            holdAt = null

            val changed = gate.offer(observed, trust = networkChanged, blind = silent)
            if (changed) {
                situationNetwork = network
                setTunnel(gate.current != Situation.Home)
            } else if (observed == gate.current) {
                situationNetwork = network
            }
        }

        private fun setTunnel(up: Boolean) {
            if (up == tunnelUp) return
            tunnelUp = up
            toggles++
        }
    }
}
