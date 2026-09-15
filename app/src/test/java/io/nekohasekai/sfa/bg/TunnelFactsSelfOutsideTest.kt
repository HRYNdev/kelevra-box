package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelFactsSelfOutsideTest {

    private fun outside(
        vpn: Boolean = true,
        roomWanted: Boolean = false,
        roomAhead: Boolean = false,
        roomReady: Boolean = false,
        roomSought: Boolean = false,
    ) = TunnelFacts.selfOutsideTun(vpn, roomWanted, roomAhead, roomReady, roomSought)

    @Test
    fun `подъём из погашенного ради комнаты — список приложений до и после входа один`() {
        // Пересборка 1: туннель поднимают, комнату ещё не просили, автомат ещё «дома».
        val naPodyome = outside(roomAhead = true)
        // Пересборка 2: комната вошла, просьба стоит.
        val posleVhoda = outside(roomWanted = true, roomReady = true)
        assertTrue(naPodyome)
        assertEquals(naPodyome, posleVhoda)
    }

    @Test
    fun `без подсказки о комнате первая пересборка шла бы без вывода — VPN-сеть пересоздавалась`() {
        val naPodyome = outside()
        val posleVhoda = outside(roomWanted = true, roomReady = true)
        assertNotEquals(naPodyome, posleVhoda)
    }

    @Test
    fun `комната поднимается при уже работающем туннеле — вывод держится с просьбы`() {
        assertTrue(outside(roomWanted = true))
        assertTrue(outside(roomReady = true))
    }

    @Test
    fun `автомат ищет путь или стоит на комнате — вывод держится и между попытками`() {
        assertTrue(outside(roomSought = true))
    }

    @Test
    fun `основной канал без комнаты — приложение в tun`() {
        assertFalse(outside())
    }

    @Test
    fun `режим без tun — выводить не из чего`() {
        assertFalse(outside(vpn = false, roomWanted = true, roomAhead = true, roomReady = true, roomSought = true))
    }

    @Test
    fun `комната не нужна и не поднимается, ядро с выводом — возвращаем в tun`() {
        assertTrue(TunnelFacts.returnSelfToTun(coreSelfOutside = true, selfOutsideNow = false, roomBusy = false))
    }

    @Test
    fun `комната поднимается — ядро посреди входа не трогаем`() {
        assertFalse(TunnelFacts.returnSelfToTun(coreSelfOutside = true, selfOutsideNow = false, roomBusy = true))
    }

    @Test
    fun `комнату ещё ищут — вывод оставляем, следующий вход без пересоздания сети`() {
        assertFalse(TunnelFacts.returnSelfToTun(coreSelfOutside = true, selfOutsideNow = true, roomBusy = false))
    }

    @Test
    fun `ядро и так с приложением в tun — пересобирать нечего`() {
        assertFalse(TunnelFacts.returnSelfToTun(coreSelfOutside = false, selfOutsideNow = false, roomBusy = false))
    }
}
