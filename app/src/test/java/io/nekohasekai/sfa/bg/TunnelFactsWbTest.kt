package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда ядру нужны правки под комнату WB и её ядро ([TunnelFacts.wbInConfig]): при ручном
 * выборе — как было (выбран выход WB), при автомате — когда он её пробует или на ней стоит.
 */
class TunnelFactsWbTest {
    private val tag = "Комната WB"

    private fun wb(available: Boolean = true, auto: Boolean, manualExit: String = "", wbTag: String = tag, autoWantsWb: Boolean = false) =
        TunnelFacts.wbInConfig(available, auto, manualExit, wbTag, autoWantsWb)

    @Test
    fun `ручной выбор WB — как раньше`() {
        assertTrue(wb(auto = false, manualExit = tag))
        assertFalse(wb(auto = false, manualExit = "Нидерланды"))
        assertFalse(wb(auto = false, manualExit = ""))
    }

    @Test
    fun `ручной выбор не смотрит на просьбу автомата`() {
        assertFalse(wb(auto = false, manualExit = "Нидерланды", autoWantsWb = true))
    }

    @Test
    fun `автомат держит WB, только пока она ему нужна`() {
        assertTrue(wb(auto = true, autoWantsWb = true))
        assertFalse(wb(auto = true, autoWantsWb = false))
    }

    @Test
    fun `выбор руками, оставшийся в настройках при включённом автомате, ничего не значит`() {
        assertFalse(wb(auto = true, manualExit = tag, autoWantsWb = false))
    }

    @Test
    fun `сервер не выдал WB — правок нет ни у кого`() {
        assertFalse(wb(available = false, auto = false, manualExit = tag))
        assertFalse(wb(available = false, auto = true, autoWantsWb = true))
    }

    @Test
    fun `пустой тег с сервера ручной выбор не узнаёт`() {
        assertFalse(wb(auto = false, manualExit = "", wbTag = ""))
    }
}
