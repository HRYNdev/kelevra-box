package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.RoomAck
import io.nekohasekai.sfa.bg.AutoMode.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки чистой логики автовыбора: таблица решений, задвижка от дёрганья и разбор
 * конфига. Всё это работает без Android, поэтому проверяется на JVM, а не глазами по логу.
 */
class AutoModeLogicTest {

    // ------------------------------------------------------------ таблица решений

    @Test
    fun `дома выигрывает у всего остального`() {
        assertEquals(
            Situation.Home,
            AutoMode.decide(hasNetwork = true, home = true, main = false, room = false),
        )
    }

    @Test
    fun `работает основной канал — идём им`() {
        assertEquals(
            Situation.Main,
            AutoMode.decide(hasNetwork = true, home = false, main = true, room = false),
        )
    }

    @Test
    fun `основной не поднимается, комната стоит — уходим в комнату`() {
        assertEquals(
            Situation.Room,
            AutoMode.decide(hasNetwork = true, home = false, main = false, room = true),
        )
    }

    @Test
    fun `не поднимается ничего — честно ищем`() {
        assertEquals(
            Situation.Searching,
            AutoMode.decide(hasNetwork = true, home = false, main = false, room = false),
        )
    }

    @Test
    fun `сети нет — ждём, что бы ни показывали остальные пробы`() {
        assertEquals(
            Situation.NoNetwork,
            AutoMode.decide(hasNetwork = false, home = true, main = true, room = true),
        )
    }

    // ------------------------------------------------- честная проба основного канала

    @Test
    fun `порт отвечает и трафик идёт — канал работает`() {
        assertTrue(AutoMode.mainVerdict(portOpen = true, trafficFlows = true))
    }

    @Test
    fun `порт отвечает, а трафика нет — канал НЕ работает`() {
        // Ровно то, что делает ТСПУ: соединение встаёт, данные не идут. До честной пробы
        // автомат считал такой канал живым и молчал в самой важной обстановке.
        assertFalse(AutoMode.mainVerdict(portOpen = true, trafficFlows = false))
    }

    @Test
    fun `порт молчит и честной пробы не было — канал НЕ работает`() {
        assertFalse(AutoMode.mainVerdict(portOpen = false, trafficFlows = null))
    }

    @Test
    fun `порт молчит, но через канал прошёл запрос — канал работает`() {
        // Спор двух проб выигрывает та, что видела весь путь. Вопрос не выдуманный:
        // 11.08.2026 хозяин просидел в комнате 47 минут, пока ядро видело основной канал
        // живым (166-308 мс). Теперь в комнате мы спрашиваем канал целиком даже при
        // молчащем адресе узла — и ответ этой пробы обязан перевешивать рукопожатие,
        // иначе спрашивать незачем.
        assertTrue(AutoMode.mainVerdict(portOpen = false, trafficFlows = true))
    }

    @Test
    fun `честной пробы нет — верим порту, как раньше`() {
        assertTrue(AutoMode.mainVerdict(portOpen = true, trafficFlows = null))
        assertFalse(AutoMode.mainVerdict(portOpen = false, trafficFlows = null))
    }

    @Test
    fun `про канал не известно ничего — отказ не выдумываем`() {
        assertTrue(AutoMode.mainVerdict(portOpen = null, trafficFlows = null))
    }

    @Test
    fun `трафик идёт, хотя адресов узла в конфиге нет — канал работает`() {
        assertTrue(AutoMode.mainVerdict(portOpen = null, trafficFlows = true))
    }

    // --------------------------------------------------------------- вердикт «дома»

    @Test
    fun `подменные адреса есть, а трафик не идёт — это НЕ дом`() {
        // Ровно дыра, найденная на стенде белого списка 08.08.2026: домашний резолвер
        // отвечает как обычно (подменные адреса приходят), а наружу не проходит ничего.
        // До правки автомат на этом гасил туннель и писал «Дома, обход на роутере».
        assertFalse(
            AutoMode.homeVerdict(dnsSign = true, trafficConfirmed = false, hint = NetworkMode.Unknown),
        )
    }

    @Test
    fun `подменные адреса и живой трафик — дом`() {
        assertTrue(
            AutoMode.homeVerdict(dnsSign = true, trafficConfirmed = true, hint = NetworkMode.Unknown),
        )
    }

    @Test
    fun `трафик идёт, но подменных адресов нет — это чужая сеть, а не дом`() {
        // Трафик сам по себе домом не делает: наружу ходит и обычная сеть без обхода.
        assertFalse(
            AutoMode.homeVerdict(dnsSign = false, trafficConfirmed = true, hint = NetworkMode.Unknown),
        )
    }

    @Test
    fun `трафик не проверяли — дом не объявляем`() {
        assertFalse(
            AutoMode.homeVerdict(dnsSign = true, trafficConfirmed = null, hint = NetworkMode.Unknown),
        )
    }

    @Test
    fun `подсказка «белый список» отменяет дом при любых признаках`() {
        for (traffic in listOf(true, false, null)) {
            assertFalse(
                "белый список бьёт всё: трафик=$traffic",
                AutoMode.homeVerdict(dnsSign = true, trafficConfirmed = traffic, hint = NetworkMode.Whitelist),
            )
        }
    }

    @Test
    fun `подсказки «норма» и «DPI» дому не мешают`() {
        for (hint in listOf(NetworkMode.Normal, NetworkMode.DpiBlacklist, NetworkMode.NoNetwork)) {
            assertTrue(
                "дом стоит на своих признаках, подсказка $hint его не отменяет",
                AutoMode.homeVerdict(dnsSign = true, trafficConfirmed = true, hint = hint),
            )
        }
    }

    // ------------------------------------------------------- где дом возможен вообще

    @Test
    fun `дома можно быть за своим роутером — по вайфаю или кабелю`() {
        assertTrue(HomeSign.reachable(wifi = true, ethernet = false, cellular = false))
        assertTrue(HomeSign.reachable(wifi = false, ethernet = true, cellular = false))
    }

    @Test
    fun `в соте дома нет — резолвер спрашивать незачем`() {
        // Четыре запроса к резолверу стоили до 2.5 секунд на каждом заходе и столько же
        // перед самым стартом сервиса, где их ждал человек. Ответ при этом известен
        // заранее: наш роутер соту не раздаёт.
        assertFalse(HomeSign.reachable(wifi = false, ethernet = false, cellular = true))
    }

    @Test
    fun `сота вместе с вайфаем — это ещё не сота`() {
        // Возвращение домой: мобильная сеть висит рядом с вайфаем до полуминуты, и оба
        // транспорта видны разом. Отказать тут значит проспать сам приход домой.
        assertTrue(HomeSign.reachable(wifi = true, ethernet = false, cellular = true))
    }

    @Test
    fun `транспорт неизвестен — отказа не выдумываем`() {
        assertTrue(HomeSign.reachable(wifi = false, ethernet = false, cellular = false))
    }

    // ------------------------------------------------------- когда закрывать DNS-сводку

    @Test
    fun `совпадений набрано и контроль настоящий — признак есть, ждать нечего`() {
        assertEquals(
            true,
            HomeSign.verdict(hits = 2, misses = 0, domains = 3, needed = 2, control = HomeSign.Control.Real),
        )
    }

    @Test
    fun `контроль подменён — это не наш роутер, дальше не смотрим`() {
        // Резолвер, который подменяет всё подряд, домом не делает. Ответ готов сразу,
        // даже когда совпадений ещё нет.
        assertEquals(
            false,
            HomeSign.verdict(hits = 0, misses = 0, domains = 3, needed = 2, control = HomeSign.Control.Fake),
        )
        assertEquals(
            false,
            HomeSign.verdict(hits = 3, misses = 0, domains = 3, needed = 2, control = HomeSign.Control.Fake),
        )
    }

    @Test
    fun `до нужного числа совпадений уже не добрать — ответ готов, не дожидаясь остальных`() {
        // Ровно мобильная сеть: два домена вернули настоящие адреса, третий ещё думает,
        // но признака не будет при любом его ответе.
        assertEquals(
            false,
            HomeSign.verdict(hits = 0, misses = 2, domains = 3, needed = 2, control = HomeSign.Control.Waiting),
        )
    }

    @Test
    fun `совпадений хватает, а контроль молчит — ждём его, но недолго`() {
        // Пока контроль не ответил, вердикта нет: он единственный, кто может признак
        // отменить. Ждать его весь бюджет незачем — на то и свой короткий срок.
        assertEquals(
            null,
            HomeSign.verdict(hits = 2, misses = 0, domains = 3, needed = 2, control = HomeSign.Control.Waiting),
        )
        assertTrue("срок контроля обязан быть короче общего бюджета", HomeSign.CONTROL_BUDGET_MILLIS < 2_500L)
    }

    @Test
    fun `совпадений пока мало, но добрать ещё можно — ждём`() {
        assertEquals(
            null,
            HomeSign.verdict(hits = 1, misses = 1, domains = 3, needed = 2, control = HomeSign.Control.Real),
        )
    }

    @Test
    fun `ждать больше нечего — молчащий контроль засчитывается настоящим`() {
        // Так было и до правки: неотвеченный контроль считался настоящим адресом,
        // только платили за это полным бюджетом. Вердикт не поменялся, поменялось время.
        assertTrue(HomeSign.settle(hits = 2, needed = 2, control = HomeSign.Control.Waiting))
        assertFalse(HomeSign.settle(hits = 1, needed = 2, control = HomeSign.Control.Waiting))
        assertFalse(HomeSign.settle(hits = 3, needed = 2, control = HomeSign.Control.Fake))
    }

    // ------------------------------------------------------- сколько живёт признак дома

    @Test
    fun `слепая сводка признак не отменяет`() {
        // Ровно то, из-за чего дома включался туннель: на свежем вайфае сводка мигает
        // 3 из 3 → 0 из 3 → 3 из 3 (замер 10.08.2026). На «0 из 3» автомат объявлял
        // обычную сеть и поднимал туннель у себя же дома.
        assertTrue(
            HomeSign.stands(seenNow = false, ageMillis = 3_000L, refuted = false),
        )
    }

    @Test
    fun `признак видели только что — стоит без всякой памяти`() {
        assertTrue(HomeSign.stands(seenNow = true, ageMillis = null, refuted = false))
    }

    @Test
    fun `опровержение делом бьёт память, но не свежее наблюдение`() {
        // Под белым списком дома резолвер честно отдаёт подменные адреса — врать про
        // признак незачем, дом всё равно не объявится: вердикт требует трафика.
        assertFalse(HomeSign.stands(seenNow = false, ageMillis = 3_000L, refuted = true))
        assertTrue(HomeSign.stands(seenNow = true, ageMillis = 3_000L, refuted = true))
    }

    @Test
    fun `признак постарел — держаться за него больше не на чем`() {
        assertFalse(
            HomeSign.stands(seenNow = false, ageMillis = HomeSign.MEMORY_MILLIS + 1, refuted = false),
        )
    }

    @Test
    fun `на этой сети признака не видели — памяти нет`() {
        assertFalse(HomeSign.stands(seenNow = false, ageMillis = null, refuted = false))
    }

    @Test
    fun `память признака переживает самый длинный шаг серии перепроверок`() {
        // Иначе серия сама себя и подведёт: между шагами признак успевал бы протухнуть,
        // и слепая сводка снова объявляла бы обычную сеть.
        assertTrue(
            "признак обязан пережить шаг серии вместе с самим заходом",
            HomeSign.MEMORY_MILLIS > AutoMode.BURST_STEPS.max(),
        )
    }

    // ------------------------------------------------- когда звать определитель режима

    @Test
    fun `сеть настраивается — одного несовпадения по дому мало для замера`() {
        // Свежий вайфай честно не пропускает наружу первый запрос, и на нём «дом по DNS
        // без трафика» — обычное дело, а не белый список. Замер режима стоит до полутора
        // десятков секунд прямо в заходе, и в эти секунды он покупает пустой ответ.
        assertFalse(AutoMode.brokenEnough(homeMismatch = true, mainFailed = false, settling = true))
    }

    @Test
    fun `провалившийся канал — повод и пока сеть настраивается`() {
        // Под белым списком основной канал проваливается тем же заходом, поэтому
        // определитель отстаёт на один шаг серии, а не пропускается.
        assertTrue(AutoMode.brokenEnough(homeMismatch = false, mainFailed = true, settling = true))
        assertTrue(AutoMode.brokenEnough(homeMismatch = true, mainFailed = true, settling = true))
    }

    @Test
    fun `сеть устоялась — правило прежнее`() {
        assertTrue(AutoMode.brokenEnough(homeMismatch = true, mainFailed = false, settling = false))
        assertTrue(AutoMode.brokenEnough(homeMismatch = false, mainFailed = true, settling = false))
        assertFalse(AutoMode.brokenEnough(homeMismatch = false, mainFailed = false, settling = false))
    }

    @Test
    fun `пока всё сходится — определитель не зовём вовсе`() {
        assertFalse(AutoMode.askDetector(broken = false, cachedAgeMillis = null))
        assertFalse(AutoMode.askDetector(broken = false, cachedAgeMillis = 10 * 60_000L))
    }

    @Test
    fun `сломалось и на этой сети не мерили — зовём`() {
        assertTrue(AutoMode.askDetector(broken = true, cachedAgeMillis = null))
    }

    @Test
    fun `свежая подсказка есть — второй раз не зовём`() {
        assertFalse(AutoMode.askDetector(broken = true, cachedAgeMillis = 60_000L))
    }

    @Test
    fun `подсказка протухла — спрашиваем заново`() {
        // Иначе снятие ограничения на той же сети мы бы не заметили никогда: события
        // смены сети при этом не будет, а основной канал помечается мёртвым без пробы.
        assertTrue(AutoMode.askDetector(broken = true, cachedAgeMillis = 6 * 60_000L))
    }

    @Test
    fun `узел отвечает — определитель уже нечего добавить`() {
        // Единственный вердикт определителя, который на что-то влияет, — «белый список».
        // Ответивший узел его опровергает: под белым списком он недостижим. Значит замер
        // (до двух соединений с TLS) купил бы ответ, который у нас и так есть.
        assertFalse(AutoMode.askDetector(broken = true, cachedAgeMillis = null, nodeAnswers = true))
        assertFalse(AutoMode.askDetector(broken = true, cachedAgeMillis = 6 * 60_000L, nodeAnswers = true))
    }

    @Test
    fun `узел молчит — довода нет, всё как раньше`() {
        // Проверка на то, что новый довод не подменил собой старое правило: пока узел
        // не отозвался, решает по-прежнему возраст подсказки.
        assertTrue(AutoMode.askDetector(broken = true, cachedAgeMillis = null, nodeAnswers = false))
        assertFalse(AutoMode.askDetector(broken = true, cachedAgeMillis = 60_000L, nodeAnswers = false))
        assertFalse(AutoMode.askDetector(broken = false, cachedAgeMillis = null, nodeAnswers = true))
    }

    // ------------------------------------------------------------ задвижка

    @Test
    fun `одиночный провал не переключает`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        assertFalse(gate.offer(Situation.Searching))
        assertEquals(Situation.Main, gate.current)
        assertTrue("после расхождения ритм должен ускориться", gate.pending)

        // Флуктуация прошла сама — счётчик обнуляется.
        assertFalse(gate.offer(Situation.Main))
        assertFalse(gate.pending)

        // И следующий одиночный провал снова не переключает: счёт начался заново.
        assertFalse(gate.offer(Situation.Searching))
        assertEquals(Situation.Main, gate.current)
    }

    @Test
    fun `три провала подряд переключают`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        assertFalse(gate.offer(Situation.Room))
        assertFalse(gate.offer(Situation.Room))
        assertTrue(gate.offer(Situation.Room))
        assertEquals(Situation.Room, gate.current)
        assertFalse(gate.pending)
    }

    @Test
    fun `разные наблюдения не складываются в подтверждение`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        assertFalse(gate.offer(Situation.Room))
        assertFalse(gate.offer(Situation.Searching))
        assertFalse(gate.offer(Situation.Room))
        assertEquals("два разных «не то» — это не три одинаковых", Situation.Main, gate.current)
    }

    @Test
    fun `смена сети переключает с первого наблюдения`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        assertTrue(gate.offer(Situation.Home, trust = true))
        assertEquals(Situation.Home, gate.current)
    }

    @Test
    fun `пропажа сети не ждёт подтверждений`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        assertTrue(gate.offer(Situation.NoNetwork))
        assertEquals(Situation.NoNetwork, gate.current)
    }

    @Test
    fun `возврат из комнаты на основной канал требует тех же трёх подтверждений`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Room)

        assertFalse(gate.offer(Situation.Main))
        assertFalse(gate.offer(Situation.Main))
        assertTrue(gate.offer(Situation.Main))
        assertEquals(Situation.Main, gate.current)
    }

    @Test
    fun `внутри серии после смены сети подтверждений нужно на одно меньше`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        // Первое наблюдение — то самое, на котором сеть и сменилась: принимается сразу.
        // Здесь проверяем следующие за ним: они уже не «доказанная смена», но и держаться
        // за старый вердикт всеми тремя заходами незачем — сеть под ним другая.
        assertFalse(gate.offer(Situation.Home, hurried = true))
        assertTrue(gate.offer(Situation.Home, hurried = true))
        assertEquals(Situation.Home, gate.current)
    }

    @Test
    fun `серия ускоряет перепроверку, но метаться не разрешает`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Main)

        // Одиночного наблюдения мало и внутри серии тоже.
        assertFalse(gate.offer(Situation.Room, hurried = true))
        assertEquals(Situation.Main, gate.current)
        // И разные наблюдения по-прежнему не складываются.
        assertFalse(gate.offer(Situation.Searching, hurried = true))
        assertEquals(Situation.Main, gate.current)
    }

    // ------------------------------------------------- серия проверок после смены сети

    @Test
    fun `серия начинается сразу и первый шаг короткий`() {
        val burst = AutoModeBurst(AutoMode.BURST_STEPS)
        assertFalse(burst.active)

        burst.restart()
        assertTrue(burst.active)
        // Заход, на котором сеть сменилась, «ничего не поменял» по определению —
        // обрывать серию на нём нельзя, иначе перепроверки не будет вовсе.
        assertEquals(1_000L, burst.next(settled = true))
    }

    @Test
    fun `устоялось — серия обрывается, лишних проб нет`() {
        val burst = AutoModeBurst(AutoMode.BURST_STEPS)
        burst.restart()

        assertEquals(1_000L, burst.next(settled = true))
        assertEquals(null, burst.next(settled = true))
        assertFalse(burst.active)
    }

    @Test
    fun `пока не устоялось — серия идёт до конца с растущими паузами`() {
        val burst = AutoModeBurst(AutoMode.BURST_STEPS)
        burst.restart()

        val seen = generateSequence { burst.next(settled = false) }.toList()
        assertEquals(AutoMode.BURST_STEPS.toList(), seen)
        assertFalse("серия обязана кончаться сама", burst.active)
    }

    @Test
    fun `новая смена сети начинает серию заново`() {
        val burst = AutoModeBurst(AutoMode.BURST_STEPS)
        burst.restart()
        burst.next(settled = false)
        burst.next(settled = false)

        burst.restart()
        assertEquals(1_000L, burst.next(settled = false))
    }

    @Test
    fun `серия успевает раньше обычного ритма`() {
        // Смысл серии в том, чтобы возвращение домой замечалось секундами, а не
        // следующим плановым заходом. Если она растянется до тех же пяти минут,
        // от неё не останется ничего.
        val steps = AutoMode.BURST_STEPS.toList()
        assertEquals("паузы обязаны расти", steps.sorted(), steps)
        assertTrue("первая проверка почти сразу", steps.first() <= 1_000L)
        assertTrue("вся серия короче одного планового захода", steps.sum() < 60_000L)
    }

    // ------------------------------------------------------------ разбор конфига

    /** Ровно та раскладка, которую отдаёт сервер: селектор с комнатой и узлом внутри. */
    private val config = """
        {
          "inbounds": [
            {
              "tag": "tun-in", "type": "tun",
              "platform": {"http_proxy": {"server": "127.0.0.1", "enabled": true, "server_port": 2412}}
            },
            {"tag": "mixed-in", "type": "mixed", "users": [], "listen": "127.0.0.1", "listen_port": 2412}
          ],
          "outbounds": [
            {"type": "selector", "tag": "Соединение", "outbounds": ["Нидерланды", "Комната"]},
            {"type": "urltest", "tag": "Нидерланды", "outbounds": ["Нидерланды · прямой", "Нидерланды · запасной"]},
            {"type": "direct", "tag": "direct"},
            {"type": "vless", "tag": "Нидерланды · прямой", "server": "nodekv.example", "server_port": 443},
            {"type": "vless", "tag": "Нидерланды · запасной", "server": "nodekv.example", "server_port": 2053},
            {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": 8808}
          ]
        }
    """.trimIndent()

    @Test
    fun `в конфиге находятся селектор, комната, основной выход и адреса узлов`() {
        val layout = AutoModeExits.parse(config, socksPort = 8808)

        assertEquals("Соединение", layout.chooser)
        assertEquals("Комната", layout.room)
        assertEquals("Нидерланды", layout.main)
        assertTrue(layout.canSwitch)
        assertEquals(
            listOf(
                AutoModeExits.Endpoint("nodekv.example", 443),
                AutoModeExits.Endpoint("nodekv.example", 2053),
            ),
            layout.mainEndpoints,
        )
    }

    @Test
    fun `комната опознаётся по порту ядра, а не по имени`() {
        // Ядро подняло SOCKS на другом порту — значит этот socks не наш.
        val layout = AutoModeExits.parse(config, socksPort = 9999)
        assertEquals(null, layout.room)
        assertFalse(layout.canSwitch)
    }

    @Test
    fun `битый конфиг не роняет автомат`() {
        val layout = AutoModeExits.parse("не json вовсе", socksPort = 8808)
        assertEquals(AutoModeExits.Layout.EMPTY, layout)
        assertFalse(layout.canSwitch)
    }

    // -------------------------------------------- локальный вход для честной пробы

    @Test
    fun `локальный прокси берётся из того, что конфиг подставляет системе`() {
        val layout = AutoModeExits.parse(config, socksPort = 8808)
        assertEquals(AutoModeExits.Endpoint("127.0.0.1", 2412), layout.localProxy)
    }

    @Test
    fun `без объявления системного прокси годится обычный локальный вход`() {
        val plain = """
            {
              "inbounds": [
                {"tag": "tun-in", "type": "tun"},
                {"tag": "mixed-in", "type": "mixed", "listen": "127.0.0.1", "listen_port": 2080}
              ],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        assertEquals(AutoModeExits.Endpoint("127.0.0.1", 2080), AutoModeExits.parse(plain, 8808).localProxy)
    }

    @Test
    fun `вход самой комнаты за локальный прокси не принимаем`() {
        // socks на петле с портом ядра — это выход В комнату, а не путь наружу.
        val onlyRoom = """
            {
              "inbounds": [
                {"tag": "room-in", "type": "socks", "listen": "127.0.0.1", "listen_port": 8808}
              ],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        assertEquals(null, AutoModeExits.parse(onlyRoom, 8808).localProxy)
    }

    @Test
    fun `вход с паролем пробе не годится`() {
        val guarded = """
            {
              "inbounds": [
                {
                  "tag": "mixed-in", "type": "mixed", "listen": "127.0.0.1", "listen_port": 2080,
                  "users": [{"username": "u", "password": "p"}]
                }
              ],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        assertEquals(null, AutoModeExits.parse(guarded, 8808).localProxy)
    }

    @Test
    fun `входов нет вообще — честной пробы просто не будет`() {
        val noInbounds = """{"outbounds": [{"type": "direct", "tag": "direct"}]}"""
        assertEquals(null, AutoModeExits.parse(noInbounds, 8808).localProxy)
    }

    @Test
    fun `пробный подъём комнаты отстаёт от задвижки ровно на один заход`() {
        // Иначе одиночная просадка основного канала будила бы видеозвонок, а следом
        // его же и гасила — те самые качели.
        assertEquals(AutoMode.CONFIRMATIONS - 1, AutoMode.ROOM_TRIAL_AFTER)
        assertTrue("подъём обязан быть раньше решения", AutoMode.ROOM_TRIAL_AFTER < AutoMode.CONFIRMATIONS)
        assertTrue("одиночного провала мало", AutoMode.ROOM_TRIAL_AFTER > 1)
    }

    // ------------------------------------------------------------ ответ про комнату

    @Test
    fun `ядро пересобрано только на Changed`() {
        assertTrue(AutoMode.RoomAck.Changed.changed)
        RoomAck.values().filter { it != AutoMode.RoomAck.Changed }.forEach {
            assertFalse("$it ядро не пересобирал", it.changed)
        }
    }

    @Test
    fun `не спрашивали — значит и штрафовать не за что`() {
        // Ровно эти два ответа стоили 120 секунд простоя (замер 08.08.2026): сервис
        // отказывал, не начав, а автомат считал отказ неудачей комнаты и удваивал паузу.
        assertTrue("туннеля нет — попытки не было", AutoMode.RoomAck.NoTunnel.untried)
        assertTrue("комнаты нет вовсе — попытки не было", AutoMode.RoomAck.Unavailable.untried)
    }

    @Test
    fun `попытка была — ответ про неё честный`() {
        assertFalse("подъём идёт — это попытка", AutoMode.RoomAck.Raising.untried)
        assertFalse("не встала — это попытка", AutoMode.RoomAck.Failed.untried)
        assertFalse(AutoMode.RoomAck.Changed.untried)
        assertFalse(AutoMode.RoomAck.Unchanged.untried)
    }

    // ------------------------------------------------------------ приговор комнате

    @Test
    fun `свежий присмотр комнату не осуждает`() {
        // Ноль отказов — гасить нечего. Иначе автомат ронял бы комнату сразу после подъёма.
        assertFalse(OlcRtcWatchdog.condemned)
    }

    @Test
    fun `приговор требует тех же трёх отказов, что и подъём заново`() {
        // Одна цифра на оба решения: если бы автомат гасил раньше, чем присмотр лечит,
        // лечение не случалось бы никогда — комнату сносили бы до первой же попытки.
        assertEquals(3, OlcRtcWatchdog.FAILURES_BEFORE_RESTART)
        assertTrue("одиночного отказа мало", OlcRtcWatchdog.FAILURES_BEFORE_RESTART > 1)
    }

    @Test
    fun `подъём в фоне ядро ещё не пересобрал`() {
        // Наверх он возвращается сразу, а пересборка случится потом и разбудит заход
        // отдельно — иначе автомат стёр бы память о выборе раньше, чем выбор сбился.
        assertFalse(AutoMode.RoomAck.Raising.changed)
    }
}
