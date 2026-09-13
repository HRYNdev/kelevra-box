package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Короткое окно тревоги: шторм отказов ловится на первых секундах, а не через десять минут.
 *
 * 12.09.2026 шторм уложился в два всплеска по десять секунд, до 1240 отказов в секунду,
 * а десятиминутная тревога поднялась, когда автомат уже сам всё починил.
 */
class CoreLogShtormTest {

    @Test
    fun `шторм ловится ровно на пороге`() {
        assertTrue(CoreLog.shtorm(otkazov = CoreLog.BYSTRO_POROG, soed = CoreLog.BYSTRO_POROG))
    }

    @Test
    fun `сверх порога вторая тревога в том же окне не поднимается`() {
        assertFalse(CoreLog.shtorm(otkazov = CoreLog.BYSTRO_POROG + 1, soed = CoreLog.BYSTRO_POROG + 1))
    }

    @Test
    fun `до порога шторма нет`() {
        assertFalse(CoreLog.shtorm(otkazov = CoreLog.BYSTRO_POROG - 1, soed = CoreLog.BYSTRO_POROG - 1))
    }

    @Test
    fun `двести отказов на десятках тысяч живых соединений не шторм`() {
        assertFalse(CoreLog.shtorm(otkazov = CoreLog.BYSTRO_POROG, soed = 20_000))
    }

    @Test
    fun `таймауты и сбросы при заметной доле — повод спросить режим сети`() {
        assertTrue(CoreLog.povodSprositRezhim(dolya = 40, kody = mapOf("taymaut" to 7, "sbros" to 3)))
    }

    @Test
    fun `отказы в свой сокс и нерешённые имена поводом не считаются`() {
        assertFalse(
            CoreLog.povodSprositRezhim(
                dolya = 90,
                kody = mapOf("otkaz_soedineniya" to 500, "imya_ne_reshilos" to 50, "taymaut" to 2),
            ),
        )
    }

    @Test
    fun `много отказов при малой доле — не повод`() {
        assertFalse(CoreLog.povodSprositRezhim(dolya = 5, kody = mapOf("taymaut" to 100)))
    }

    @Test
    fun `без соединений доля считается полной`() {
        assertEquals(100, CoreLog.dolyaOtkazov(otkazov = 5, soed = 0))
        assertEquals(50, CoreLog.dolyaOtkazov(otkazov = 5, soed = 10))
    }
}
