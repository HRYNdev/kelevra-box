package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пробный подъём комнаты, ложный «основной мёртв» по одному пику и замер режима в потоке
 * захода (журнал телефона 16.10.2026, улица).
 */
class RoomTrialPauseTest {

    private fun report(s: NetworkSignals, at: Long = 1_000L) =
        NetworkModeReport(NetworkModeDecision.decide(s), s, atMillis = at, tookMillis = 0, note = "")

    // ------------------------------------------------------------ пауза

    @Test
    fun `пауза удваивается от минуты до десяти и дальше не растёт`() {
        var p = AutoMode.nextTrialPause(60_000L)
        assertEquals(120_000L, p)
        repeat(6) { p = AutoMode.nextTrialPause(p) }
        assertEquals(10 * 60_000L, p)
    }

    @Test
    fun `основной мёртв — пауза не растёт, сколько бы комната ни не вставала`() {
        var p = 60_000L
        repeat(8) { p = AutoMode.nextTrialPause(p, mainKnownDead = true) }
        assertEquals(60_000L, p)
        // Выросшая до мёртвого основного пауза тоже возвращается к начальной.
        assertEquals(60_000L, AutoMode.nextTrialPause(10 * 60_000L, mainKnownDead = true))
        assertEquals(120_000L, AutoMode.nextTrialPause(60_000L, mainKnownDead = false))
    }

    @Test
    fun `свежая подсказка белого списка — комнату просим после первого провала`() {
        assertEquals(1, AutoMode.roomTrialAfter(whitelistHint = true))
        assertEquals(AutoMode.ROOM_TRIAL_AFTER, AutoMode.roomTrialAfter(whitelistHint = false))
        assertEquals(AutoMode.ROOM_TRIAL_AFTER, AutoMode.roomTrialAfter())
    }

    // ------------------------------------------------------------ сеть для комнаты

    private val silent = NetworkSignals(
        physicalNetwork = true, tcpUnlisted = ProbeOutcome.Silence, tcpAllowed = ProbeOutcome.Silence,
    )

    @Test
    fun `замера нет — просить можно`() {
        assertTrue(AutoMode.networkAllowsRoom(null, 2_000L))
    }

    @Test
    fun `только что молчало всё, даже разрешённый адрес — комнату не просим`() {
        assertFalse(AutoMode.networkAllowsRoom(report(silent), 2_000L))
    }

    @Test
    fun `тишина старше нескольких секунд ничего не запрещает`() {
        val now = 1_000L + AutoMode.ROOM_GATE_FRESH_MILLIS
        assertTrue(AutoMode.networkAllowsRoom(report(silent), now))
    }

    @Test
    fun `только что физической сети нет — комнату не просим`() {
        assertFalse(AutoMode.networkAllowsRoom(report(NetworkSignals(physicalNetwork = false)), 2_000L))
    }

    @Test
    fun `белый список — комната как раз единственная дорога, просить можно`() {
        val s = NetworkSignals(
            physicalNetwork = true, tcpUnlisted = ProbeOutcome.Silence, tcpAllowed = ProbeOutcome.Ok,
        )
        assertTrue(AutoMode.networkAllowsRoom(report(s), 2_000L))
    }

    // ------------------------------------------------------------ порт молчит

    @Test
    fun `первое молчание порта канал мёртвым не объявляет`() {
        assertFalse(AutoMode.portSilenceConfirmed(firstSilentAt = 0L, now = 50_000L))
    }

    @Test
    fun `второе молчание раньше десяти секунд — ещё один пик`() {
        assertFalse(AutoMode.portSilenceConfirmed(firstSilentAt = 40_000L, now = 49_999L))
    }

    @Test
    fun `молчание держится десять секунд и больше — подтверждено`() {
        assertTrue(AutoMode.portSilenceConfirmed(firstSilentAt = 40_000L, now = 50_000L))
    }

    @Test
    fun `первый пик без закреплённого входа и не на основном — пробу не делаем`() {
        assertFalse(AutoMode.firstSilenceMayProbe(mainPinned = false, standsOnMain = false))
        assertTrue(AutoMode.firstSilenceMayProbe(mainPinned = true, standsOnMain = false))
        assertTrue(AutoMode.firstSilenceMayProbe(mainPinned = false, standsOnMain = true))
    }

    // ------------------------------------------------------------ замер режима

    @Test
    fun `повод только провал основного — замер уходит в фон`() {
        assertTrue(AutoMode.detectorInBackground(homeMismatch = false, suspect = false))
    }

    @Test
    fun `дом без трафика или массовые отказы — ответ нужен этому же заходу`() {
        assertFalse(AutoMode.detectorInBackground(homeMismatch = true, suspect = false))
        assertFalse(AutoMode.detectorInBackground(homeMismatch = false, suspect = true))
    }

    // ------------------------------------------------------------ вывод из tun

    @Test
    fun `поиск пути вывод из tun не держит, комната — держит`() {
        assertFalse(AutoMode.roomSoughtForTunIn(auto = true, situation = AutoMode.Situation.Searching, manualRoom = false))
        assertFalse(AutoMode.roomSoughtForTunIn(auto = true, situation = AutoMode.Situation.Main, manualRoom = false))
        assertTrue(AutoMode.roomSoughtForTunIn(auto = true, situation = AutoMode.Situation.Room, manualRoom = false))
        assertTrue(AutoMode.roomSoughtForTunIn(auto = false, situation = AutoMode.Situation.Main, manualRoom = true))
    }
}
