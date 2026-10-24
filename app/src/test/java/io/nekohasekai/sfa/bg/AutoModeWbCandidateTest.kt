package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import io.nekohasekai.sfa.bg.path.PathChip
import io.nekohasekai.sfa.bg.path.PathDef
import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathStatus
import io.nekohasekai.sfa.bg.path.PathWords
import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultReestr
import io.nekohasekai.sfa.bg.pult.PultSostoyanie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Комната WB в автомате (решение Вовы 24.10.2026): под белыми списками основной выход —
 * «Комната WB», Телемост — запасной. Порядок: дома → основной → комната WB → Телемост → поиск.
 *
 * Здесь — чистые части: таблица решений, кандидатство по ключу пульта, когда пробовать и когда
 * отпускать WB, разбор раскладки и слова на экране. Сам заход с ожиданием пробы требует Android.
 */
class AutoModeWbCandidateTest {

    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    // ------------------------------------------------------------ порядок выходов

    @Test
    fun `комната WB раньше Телемоста`() {
        assertEquals(Situation.Wb, AutoMode.decide(hasNetwork = true, home = false, main = false, room = true, wb = true))
        assertEquals(Situation.Wb, AutoMode.decide(hasNetwork = true, home = false, main = false, room = false, wb = true))
    }

    @Test
    fun `дом и основной канал главнее комнаты WB`() {
        assertEquals(Situation.Home, AutoMode.decide(hasNetwork = true, home = true, main = true, room = true, wb = true))
        assertEquals(Situation.Main, AutoMode.decide(hasNetwork = true, home = false, main = true, room = true, wb = true))
    }

    @Test
    fun `без сети комната WB ничего не меняет`() {
        assertEquals(Situation.NoNetwork, AutoMode.decide(hasNetwork = false, home = false, main = false, room = false, wb = true))
    }

    @Test
    fun `WB не везёт — всё как раньше`() {
        assertEquals(Situation.Room, AutoMode.decide(hasNetwork = true, home = false, main = false, room = true, wb = false))
        assertEquals(Situation.Searching, AutoMode.decide(hasNetwork = true, home = false, main = false, room = false))
    }

    @Test
    fun `на комнате WB стоять можно, Телемост ей не нужен`() {
        assertTrue(AutoMode.working(Situation.Wb))
        // Присмотр Телемоста её не держит и не поднимает заново.
        assertFalse(AutoMode.roomNeededIn(auto = true, situation = Situation.Wb, manualRoom = false))
        // И приложение ради неё из tun не выводится: её адреса уводит мимо tun её же правка.
        assertFalse(AutoMode.roomSoughtForTunIn(auto = true, situation = Situation.Wb, manualRoom = false))
    }

    // ------------------------------------------------------------ кандидат и ключ пульта

    @Test
    fun `ключ пульта по умолчанию выключен и описан в реестре`() {
        assertFalse(Pult.bool("avtomat.wb_kandidat"))
        assertFalse(AutoMode.wbKandidat())
        val k = PultReestr.klyuch("avtomat.wb_kandidat")
        assertNotNull(k)
        assertEquals(PultReestr.Tip.LOGICH, k!!.tip)
        assertEquals(false, k.umolch)
    }

    @Test
    fun `ключ пульта включает кандидата`() {
        Pult.dlyaTesta(PultSostoyanie(nastroyki = mapOf("avtomat.wb_kandidat" to true)))
        assertTrue(AutoMode.wbKandidat())
    }

    @Test
    fun `комната WB нужна автомату, только когда всё три — ключ, автомат сам, его просьба`() {
        assertTrue(AutoMode.wbWantedIn(kandidat = true, auto = true, want = true))
        assertFalse("ключ выключен", AutoMode.wbWantedIn(kandidat = false, auto = true, want = true))
        assertFalse("выбор руками", AutoMode.wbWantedIn(kandidat = true, auto = false, want = true))
        assertFalse("автомат не просил", AutoMode.wbWantedIn(kandidat = true, auto = true, want = false))
    }

    // ------------------------------------------------------------ когда пробовать и отпускать

    @Test
    fun `живой считается только комната WB с пройденной пробой`() {
        assertTrue(AutoMode.wbAliveIn(up = true, status = PathStatus.Alive))
        assertFalse("ядро вышло — запись протухла", AutoMode.wbAliveIn(up = false, status = PathStatus.Alive))
        assertFalse("встало, но проба не прошла", AutoMode.wbAliveIn(up = true, status = PathStatus.Raising))
        assertFalse(AutoMode.wbAliveIn(up = true, status = PathStatus.Dead))
    }

    @Test
    fun `новая проба WB — не раньше паузы`() {
        assertTrue(AutoMode.wbTrialMayStart(triedAt = 0L, now = 5_000L, pause = 60_000L))
        assertFalse(AutoMode.wbTrialMayStart(triedAt = 10_000L, now = 69_999L, pause = 60_000L))
        assertTrue(AutoMode.wbTrialMayStart(triedAt = 10_000L, now = 70_000L, pause = 60_000L))
    }

    @Test
    fun `WB не ожила — держим до Телемоста, но вдвоём не дольше минуты`() {
        val failed = 100_000L
        // Окно ожидания ещё не выходило — отпускать нечего.
        assertFalse(AutoMode.wbReleaseDue(failedAt = 0L, now = failed + 120_000L, roomLive = true, wbLive = false))
        // Телемост повёз — WB больше не нужна.
        assertTrue(AutoMode.wbReleaseDue(failedAt = failed, now = failed + 1_000L, roomLive = true, wbLive = false))
        // Телемост ещё встаёт — держим WB.
        assertFalse(AutoMode.wbReleaseDue(failedAt = failed, now = failed + 59_999L, roomLive = false, wbLive = false))
        // Оба звонка разом дольше минуты — отпускаем.
        assertTrue(AutoMode.wbReleaseDue(failedAt = failed, now = failed + AutoMode.WB_OVERLAP_MAX_MILLIS, roomLive = false, wbLive = false))
        // WB ожила позже срока — она главнее, не отпускаем.
        assertFalse(AutoMode.wbReleaseDue(failedAt = failed, now = failed + 120_000L, roomLive = true, wbLive = true))
    }

    @Test
    fun `сроки — как в решении`() {
        assertEquals(30_000L, AutoMode.WB_TRIAL_WAIT_MILLIS)
        assertTrue(AutoMode.WB_OVERLAP_MAX_MILLIS <= 60_000L)
        assertEquals(5_000L, WbChatWatchdog.PROBE_INTERVAL_MILLIS)
    }

    @Test
    fun `пока ищем путь, выход уводится в комнату WB только по пройденной пробе`() {
        assertEquals("Комната WB", AutoMode.searchingTarget("Комната WB", selected = "Нидерланды", roomUp = true, roomLive = true))
        assertNull(AutoMode.searchingTarget("Комната WB", selected = "Нидерланды", roomUp = true, roomLive = false))
        assertNull(AutoMode.searchingTarget(null, selected = "Нидерланды", roomUp = true, roomLive = true))
    }

    // ------------------------------------------------------------ разбор раскладки

    private val roomPort = 8808
    private val wbPort = 8809

    private fun config(members: String = "\"Нидерланды\", \"Финляндия\", \"Комната\", \"Комната WB\"") = """
        {
          "outbounds": [
            {"type": "selector", "tag": "Соединение", "outbounds": [$members]},
            {"type": "urltest", "tag": "Нидерланды", "outbounds": ["nl-1"]},
            {"type": "urltest", "tag": "Финляндия", "outbounds": ["fi-1"]},
            {"type": "vless", "tag": "nl-1", "server": "nl.example", "server_port": 443},
            {"type": "vless", "tag": "fi-1", "server": "fi.example", "server_port": 443},
            {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": $roomPort},
            {"type": "socks", "tag": "Комната WB", "server": "127.0.0.1", "server_port": $wbPort},
            {"type": "direct", "tag": "direct"}
          ]
        }
    """.trimIndent()

    @Test
    fun `комната WB опознаётся по порту своего ядра`() {
        val layout = AutoModeExits.parse(config(), roomPort, wbPort)
        assertEquals("Комната WB", layout.wb)
        assertEquals("Комната", layout.room)
        assertEquals("Нидерланды", layout.main)
    }

    @Test
    fun `комната WB не попадает ни в основной, ни в очередь узлов, ни в мерные пути`() {
        // Сервер положил WB первой — основным она всё равно не становится.
        val layout = AutoModeExits.parse(config("\"Комната WB\", \"Нидерланды\", \"Финляндия\", \"Комната\""), roomPort, wbPort)
        assertEquals("Комната WB", layout.wb)
        assertEquals("Нидерланды", layout.main)
        assertEquals(listOf("Нидерланды", "Финляндия"), layout.nodes)
        assertFalse(layout.measurable.contains("Комната WB"))
        assertFalse(layout.nodeEndpoints.containsKey("Комната WB"))
        // На запасном узле раскладка помнит ту же комнату WB.
        assertEquals("Комната WB", layout.forNode("Финляндия").wb)
    }

    @Test
    fun `порт WB не передали или он чужой — комнаты WB нет`() {
        assertNull(AutoModeExits.parse(config(), roomPort).wb)
        assertNull(AutoModeExits.parse(config(), roomPort, 9999).wb)
        // Порт совпал с комнатным — это комната Телемоста, а не WB.
        assertNull(AutoModeExits.parse(config(), roomPort, roomPort).wb)
        assertEquals("Комната", AutoModeExits.parse(config(), roomPort, roomPort).room)
    }

    @Test
    fun `WB вне селектора — автомату её не выбрать`() {
        assertNull(AutoModeExits.parse(config("\"Нидерланды\", \"Комната\""), roomPort, wbPort).wb)
    }

    // ------------------------------------------------------------ реестр и слова

    @Test
    fun `в реестре комната WB между основным каналом и Телемостом`() {
        assertTrue(PathDef.RANK_MAIN < PathDef.RANK_WB)
        assertTrue(PathDef.RANK_WB < PathDef.RANK_ROOM)
        PathRegistry.reset()
        PathRegistry.bindExits(main = "Нидерланды", room = "Комната", wb = "Комната WB")
        val snap = PathRegistry.snapshot.value
        assertEquals(PathId.WB, snap.byExit("Комната WB")?.def?.id)
        PathRegistry.reset()
    }

    @Test
    fun `стоим на комнате WB — шторка и капсула так и говорят`() {
        PathRegistry.reset()
        PathRegistry.raising(PathId.WB)
        assertEquals("Подъём комнаты WB", PathWords.headline(PathRegistry.snapshot.value, PathId.WB, auto = true, manualExit = null))
        PathRegistry.alive(PathId.WB, latencyMs = 300)
        val snap = PathRegistry.snapshot.value
        assertEquals("Комната WB", PathWords.headline(snap, PathId.WB, auto = true, manualExit = null))
        assertEquals(PathChip.ROOM, PathChip.of(snap, PathId.WB, auto = true, tunnelLive = true, manualExit = null) { null }.text)
        PathRegistry.dead(PathId.WB, "звонок кончился")
        assertEquals("Комната WB не отвечает", PathWords.headline(PathRegistry.snapshot.value, PathId.WB, auto = true, manualExit = null))
        PathRegistry.reset()
    }
}
