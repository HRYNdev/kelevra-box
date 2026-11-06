package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.Samopodem.Shag
import org.junit.Assert.assertEquals
import org.junit.Test

/** Решение «поднимать ли ядро», когда система сама подняла липкую службу без интента. */
class SamopodemTest {

    @Test
    fun `человек включал, передний план есть, VPN разрешён — поднимаем`() {
        assertEquals(Shag.PODNYAT, Samopodem.reshit(hotel = true, vPerednemPlane = true, vpnRazreshenie = true))
    }

    @Test
    fun `человек службу выключал — система ошиблась, тихо гасим`() {
        assertEquals(Shag.NICHEGO, Samopodem.reshit(hotel = false, vPerednemPlane = true, vpnRazreshenie = true))
        assertEquals(Shag.NICHEGO, Samopodem.reshit(hotel = false, vPerednemPlane = false, vpnRazreshenie = false))
    }

    @Test
    fun `передний план отклонён системой — на фоновой службе ядро не поднимаем, зовём человека`() {
        assertEquals(Shag.UVEDOMIT, Samopodem.reshit(hotel = true, vPerednemPlane = false, vpnRazreshenie = true))
    }

    @Test
    fun `разрешение VPN отобрали — сразу к человеку`() {
        assertEquals(Shag.UVEDOMIT, Samopodem.reshit(hotel = true, vPerednemPlane = true, vpnRazreshenie = false))
    }
}
