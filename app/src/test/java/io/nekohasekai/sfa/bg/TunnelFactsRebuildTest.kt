package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelFactsRebuildTest {

    @Test
    fun `сервис работает, туннель поднят — пересборка разрешена`() {
        assertTrue(TunnelFacts.rebuildAllowed(serviceStopping = false, suspendedFlag = false))
    }

    @Test
    fun `сервис останавливается — комната, вставшая после стопа, ядро не пересобирает`() {
        assertFalse(TunnelFacts.rebuildAllowed(serviceStopping = true, suspendedFlag = false))
        assertFalse(TunnelFacts.rebuildAllowed(serviceStopping = true, suspendedFlag = true))
    }

    @Test
    fun `туннель погашен — пересборки нет`() {
        assertFalse(TunnelFacts.rebuildAllowed(serviceStopping = false, suspendedFlag = true))
    }
}
