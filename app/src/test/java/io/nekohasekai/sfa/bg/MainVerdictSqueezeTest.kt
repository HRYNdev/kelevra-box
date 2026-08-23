package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикт по основному каналу с учётом полосы. Раньше «трафик идёт» означало «канал
 * в порядке», и залипание на задушенном входе снять было нечем.
 */
class MainVerdictSqueezeTest {

    @Test
    fun `трафик идёт и полоса цела — канал годен`() {
        assertTrue(AutoMode.mainVerdict(portOpen = true, trafficFlows = true, squeezed = false))
    }

    @Test
    fun `трафик идёт, но полоса задушена — канал не годен`() {
        assertFalse(AutoMode.mainVerdict(portOpen = true, trafficFlows = true, squeezed = true))
    }

    @Test
    fun `задушенность не воскрешает мёртвый канал`() {
        assertFalse(AutoMode.mainVerdict(portOpen = true, trafficFlows = false, squeezed = false))
    }

    @Test
    fun `без честной пробы полосу не спрашивают, вердикт по порту`() {
        assertTrue(AutoMode.mainVerdict(portOpen = true, trafficFlows = null))
        assertFalse(AutoMode.mainVerdict(portOpen = false, trafficFlows = null))
    }

    @Test
    fun `про канал не известно ничего — отказ не выдумываем`() {
        assertTrue(AutoMode.mainVerdict(portOpen = null, trafficFlows = null))
    }
}
