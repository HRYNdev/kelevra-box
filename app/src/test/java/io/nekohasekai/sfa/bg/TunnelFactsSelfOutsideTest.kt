package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
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

    private fun stale(
        coreRoom: Boolean,
        coreSelfOutside: Boolean,
        wantRoom: Boolean,
        wantSelfOutside: Boolean,
        roomBusy: Boolean = false,
    ) = TunnelFacts.coreConfigStale(coreRoom, coreSelfOutside, wantRoom, wantSelfOutside, roomBusy)

    @Test
    fun `подъём туннеля ради комнаты выводит приложение сразу, до входа`() {
        assertTrue(outside(roomAhead = true))
    }

    @Test
    fun `комната поднята или поднимается — вывод держится`() {
        assertTrue(outside(roomWanted = true))
        assertTrue(outside(roomReady = true))
    }

    @Test
    fun `автомат ищет путь — вывод держится и между попытками`() {
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
    fun `подъём из погашенного ради комнаты — ядро собрано под неё, до входа пересборки нет`() {
        // Туннель подняли с подсказкой «следом комната»: конфиг под комнату и вывод уже на месте.
        assertFalse(stale(coreRoom = true, coreSelfOutside = true, wantRoom = true, wantSelfOutside = true))
    }

    @Test
    fun `комната при уже работающем туннеле — пересборка нужна, и она одна, до входа`() {
        assertTrue(stale(coreRoom = false, coreSelfOutside = false, wantRoom = true, wantSelfOutside = true))
    }

    @Test
    fun `комната не встала — ядро возвращаем к конфигу без комнаты`() {
        assertTrue(stale(coreRoom = true, coreSelfOutside = true, wantRoom = false, wantSelfOutside = true))
    }

    @Test
    fun `комната больше не нужна — снимаем и конфиг, и вывод`() {
        assertTrue(stale(coreRoom = true, coreSelfOutside = true, wantRoom = false, wantSelfOutside = false))
    }

    @Test
    fun `комнату поднимают прямо сейчас — посреди входа ядро не трогаем`() {
        assertFalse(stale(coreRoom = true, coreSelfOutside = true, wantRoom = false, wantSelfOutside = false, roomBusy = true))
    }

    @Test
    fun `ядро и так отвечает намерению — пересобирать нечего`() {
        assertFalse(stale(coreRoom = false, coreSelfOutside = false, wantRoom = false, wantSelfOutside = false))
        assertFalse(stale(coreRoom = true, coreSelfOutside = true, wantRoom = true, wantSelfOutside = true))
    }
}
