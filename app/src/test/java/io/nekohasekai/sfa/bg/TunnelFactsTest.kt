package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки решения «есть ли что гасить». Само гашение живёт в сервисе и без Android
 * не проверяется, а вот выбор между флагом и делом — чистая логика, и он же был багом.
 */
class TunnelFactsTest {

    @Test
    fun `туннель числится поднятым — гасим, что бы ни было живо`() {
        for (tunOpen in listOf(true, false)) {
            for (roomLive in listOf(true, false)) {
                assertTrue(
                    "флаг «поднят» гасится всегда: tun=$tunOpen комната=$roomLive",
                    TunnelFacts.suspendNeeded(suspendedFlag = false, tunOpen = tunOpen, roomLive = roomLive),
                )
            }
        }
    }

    @Test
    fun `погашено и на деле ничего не живо — делать нечего`() {
        assertFalse(
            TunnelFacts.suspendNeeded(suspendedFlag = true, tunOpen = false, roomLive = false),
        )
    }

    @Test
    fun `флаг говорит «погашено», а tun открыт — гасим по факту`() {
        // Ровно то, что запирало систему: ядро поднималось мимо автомата (перечитывание
        // конфига), флаг оставался «погашено», и гашение отвечало «уже погашено».
        // Туннель после этого висел до ручного выключения сервиса.
        assertTrue(
            TunnelFacts.suspendNeeded(suspendedFlag = true, tunOpen = true, roomLive = false),
        )
    }

    @Test
    fun `флаг говорит «погашено», а комната работает — гасим по факту`() {
        // Комната без туннеля бессмысленна: дома обход делает роутер. Осталась живой при
        // погашенном флаге — это тот же рассинхрон, и лечится он тем же заходом.
        assertTrue(
            TunnelFacts.suspendNeeded(suspendedFlag = true, tunOpen = false, roomLive = true),
        )
    }
}
