package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Проба комнаты WB висит ([AutoMode.WbTrial.Pending]) — обстановка остаётся
 * [Situation.Searching] (задвижка про флаг не знает, подтверждения не меняются),
 * флаг [AutoMode.State.wbTrialPending] трогает только текст: «проверяю комнату WB»
 * вместо «ничего не поднимается» (31-12-2026, по следам наряда 1027-011102).
 */
class AutoModeWbProbaTekstTest {

    @Test
    fun `проба WB висит - текст другой, только для Searching`() {
        assertEquals("проверяю комнату WB", AutoMode.name(Situation.Searching, wbTrialPending = true))
        assertEquals("ничего не поднимается", AutoMode.name(Situation.Searching, wbTrialPending = false))
        // Без аргумента — то же, что и раньше: вызовов name() в файле много, лишний
        // параметр по умолчанию не должен менять их текст.
        assertEquals("ничего не поднимается", AutoMode.name(Situation.Searching))
    }

    @Test
    fun `флаг не трогает текст других обстановок`() {
        assertEquals("комната", AutoMode.name(Situation.Room, wbTrialPending = true))
        assertEquals("основной канал", AutoMode.name(Situation.Main, wbTrialPending = true))
        assertEquals("дома", AutoMode.name(Situation.Home, wbTrialPending = true))
        assertEquals("комната WB", AutoMode.name(Situation.Wb, wbTrialPending = true))
    }

    @Test
    fun `по умолчанию State флаг снят`() {
        assertFalse(AutoMode.State().wbTrialPending)
    }
}
