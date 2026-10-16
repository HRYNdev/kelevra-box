package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подъём комнаты не должен жить дольше нужды в ней (журнал телефона 16.10.2026, улица:
 * попытки 2 и 3 шли после остановки сервиса) и не должен повторять безнадёжное.
 */
class RoomRaiseCancelTest {

    @Test
    fun `сервис остановили — подъём не нужен`() {
        assertFalse(TunnelFacts.roomRaiseStillWanted(true, false, false, raising = true, roomWanted = true))
    }

    @Test
    fun `просьбу отозвали — подъём не нужен`() {
        assertFalse(TunnelFacts.roomRaiseStillWanted(false, false, false, raising = true, roomWanted = false))
    }

    @Test
    fun `туннель погасили посреди подъёма — не нужен`() {
        assertFalse(TunnelFacts.roomRaiseStillWanted(false, true, false, raising = true, roomWanted = true))
    }

    @Test
    fun `туннель поднимают ради комнаты — флаг погашения подъём не отменяет`() {
        assertTrue(TunnelFacts.roomRaiseStillWanted(false, true, true, raising = false, roomWanted = true))
    }

    @Test
    fun `холодный старт с комнатой, выбранной человеком — подъём нужен`() {
        assertTrue(TunnelFacts.roomRaiseStillWanted(false, false, false, raising = false, roomWanted = false))
    }

    @Test
    fun `пауза между попытками обрывается, как только комната не нужна`() {
        var calls = 0
        val full = OlcRtcCore.sleepWhileNeeded(5_000L, { ++calls < 3 }, stepMillis = 5L)
        assertFalse(full)
        assertTrue("спали дольше нужного: $calls проверок", calls <= 3)
    }

    @Test
    fun `пауза, пока комната нужна, досыпается до конца`() {
        assertTrue(OlcRtcCore.sleepWhileNeeded(30L, { true }, stepMillis = 5L))
    }

    @Test
    fun `выхода в конференции нет — повторять попытку не надо`() {
        assertTrue(OlcRtcCore.isPermanent("Телемост не собрался за 45 с: ${TelemostCore.NO_EXIT_REASON}"))
    }

    @Test
    fun `отмена не повторяется, а обычный сбой SOCKS — повторяется`() {
        assertTrue(OlcRtcCore.isPermanent(OlcRtcCore.CANCELLED_REASON))
        assertFalse(OlcRtcCore.isPermanent("Телемост не собрался за 45 с: SOCKS не открылся"))
    }
}
