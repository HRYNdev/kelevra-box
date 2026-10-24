package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Куда уводить выход, когда комната пропала. 24.10.2026 под белым списком присмотр
 * перезапустил вставшую комнату и увёл выход на основной канал, который там мёртв.
 */
class RoomLostTargetTest {

    private val main = "Нидерланды"
    private val wb = "Комната WB"

    @Test
    fun `основной жив — как раньше, на основной`() {
        assertEquals(main, AutoMode.roomLostTarget(mainKnownDead = false, wbLive = false, main = main, wb = wb))
        assertEquals(main, AutoMode.roomLostTarget(mainKnownDead = false, wbLive = true, main = main, wb = wb))
    }

    @Test
    fun `основной мёртв — на основной не уходим никогда`() {
        for (wbLive in listOf(false, true)) {
            for (w in listOf(wb, null, "")) {
                assertTrue(
                    "wbLive=$wbLive wb=$w",
                    AutoMode.roomLostTarget(mainKnownDead = true, wbLive = wbLive, main = main, wb = w) != main,
                )
            }
        }
    }

    @Test
    fun `основной мёртв, WB жив — на WB`() {
        assertEquals(wb, AutoMode.roomLostTarget(mainKnownDead = true, wbLive = true, main = main, wb = wb))
    }

    @Test
    fun `основной мёртв, WB не стоит или его нет — селектор не трогаем`() {
        assertNull(AutoMode.roomLostTarget(mainKnownDead = true, wbLive = false, main = main, wb = wb))
        assertNull(AutoMode.roomLostTarget(mainKnownDead = true, wbLive = true, main = main, wb = null))
        assertNull(AutoMode.roomLostTarget(mainKnownDead = true, wbLive = true, main = main, wb = ""))
    }

    @Test
    fun `стоим на живой WB — не уводим на основной, даже если он жив`() {
        assertEquals(wb, AutoMode.roomLostTarget(mainKnownDead = false, wbLive = true, main = main, wb = wb, onWb = true))
        assertEquals(wb, AutoMode.roomLostTarget(mainKnownDead = true, wbLive = true, main = main, wb = wb, onWb = true))
    }

    @Test
    fun `стоим на WB, но она не готова — как без неё`() {
        assertEquals(main, AutoMode.roomLostTarget(mainKnownDead = false, wbLive = false, main = main, wb = wb, onWb = true))
        assertNull(AutoMode.roomLostTarget(mainKnownDead = true, wbLive = false, main = main, wb = wb, onWb = true))
    }

    @Test
    fun `мёртв — это свежая подсказка белого списка или провалы на пороге подтверждений`() {
        val n = AutoMode.CONFIRMATIONS
        assertTrue(AutoMode.mainKnownDeadIn(whitelistHint = true, mainFailures = 0, confirmations = n))
        assertTrue(AutoMode.mainKnownDeadIn(whitelistHint = false, mainFailures = n, confirmations = n))
        assertFalse(AutoMode.mainKnownDeadIn(whitelistHint = false, mainFailures = n - 1, confirmations = n))
        assertFalse(AutoMode.mainKnownDeadIn(whitelistHint = false, mainFailures = 0, confirmations = n))
    }
}
