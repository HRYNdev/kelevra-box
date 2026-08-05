package io.nekohasekai.sfa.bg

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
    fun `порт молчит — дальше не смотрим`() {
        assertFalse(AutoMode.mainVerdict(portOpen = false, trafficFlows = null))
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
}
