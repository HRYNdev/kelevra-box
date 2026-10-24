package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultReestr
import io.nekohasekai.sfa.bg.pult.PultSostoyanie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Быстрее до интернета через комнату WB, второй заход (24.10.2026, МегаФон, сборка 153: 163 с):
 *  1. адрес узла «принимает соединение», а канал пуст до таймаута — это не ответ узла и повод
 *     спросить режим сети, а не запирать определитель на 90 с;
 *  2. при заведомо мёртвом основном живая WB берётся с одного наблюдения, без трёх подтверждений;
 *  3. пауза между попытками WB при мёртвом основном не растёт.
 */
class AutoModeWbBystree2Test {

    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    private fun pult(vararg pary: Pair<String, Any>) = Pult.dlyaTesta(PultSostoyanie(nastroyki = mapOf(*pary)))

    // ------------------------------------------------ 1. пустой порт

    @Test
    fun `порт принят, канал не прошёл — пустой порт`() {
        assertTrue(AutoMode.hollowPort(portOpen = true, trafficFlows = false, off = false))
    }

    @Test
    fun `канал прошёл, порт молчит или канал не мерили — не пустой`() {
        assertFalse(AutoMode.hollowPort(portOpen = true, trafficFlows = true, off = false))
        // Туннель погашен: судить не по чему, «принял» остаётся ответом узла, как раньше.
        assertFalse(AutoMode.hollowPort(portOpen = true, trafficFlows = null, off = false))
        assertFalse(AutoMode.hollowPort(portOpen = false, trafficFlows = false, off = false))
        assertFalse(AutoMode.hollowPort(portOpen = null, trafficFlows = false, off = false))
    }

    @Test
    fun `выключатель пустого порта возвращает старое`() {
        assertFalse(AutoMode.hollowPort(portOpen = true, trafficFlows = false, off = true))
        pult("vykl.pustoy_port" to true)
        assertFalse(AutoMode.hollowPort(portOpen = true, trafficFlows = false))
    }

    @Test
    fun `после пустого порта определитель снова можно спросить`() {
        // Довод «узел отвечает» снят — askDetector больше не запирается им.
        assertTrue(AutoMode.askDetector(broken = true, cachedAgeMillis = null, nodeAnswers = false))
        assertFalse(AutoMode.askDetector(broken = true, cachedAgeMillis = null, nodeAnswers = true))
    }

    // ------------------------------------------------ 2. WB с одного наблюдения

    @Test
    fun `мёртвый основной и живая WB — переключение с первого наблюдения`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Searching)
        assertTrue(gate.offer(Situation.Wb, proven = AutoMode.wbProven(Situation.Wb, mainKnownDead = true, off = false)))
        assertEquals(Situation.Wb, gate.current)
    }

    @Test
    fun `без доказанной смерти основного — три подтверждения, как раньше`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Searching)
        val proven = AutoMode.wbProven(Situation.Wb, mainKnownDead = false, off = false)
        assertFalse(gate.offer(Situation.Wb, proven = proven))
        assertFalse(gate.offer(Situation.Wb, proven = proven))
        assertTrue(gate.offer(Situation.Wb, proven = proven))
    }

    @Test
    fun `слепой заход поблажки не получает`() {
        val gate = AutoModeGate(3)
        gate.reset(Situation.Searching)
        assertFalse(gate.offer(Situation.Wb, blind = true, proven = true))
    }

    @Test
    fun `поблажка только для комнаты WB и гасится выключателем`() {
        assertFalse(AutoMode.wbProven(Situation.Room, mainKnownDead = true, off = false))
        assertFalse(AutoMode.wbProven(Situation.Searching, mainKnownDead = true, off = false))
        assertFalse(AutoMode.wbProven(Situation.Wb, mainKnownDead = true, off = true))
        pult("vykl.wb_srazu" to true)
        assertFalse(AutoMode.wbProven(Situation.Wb, mainKnownDead = true))
    }

    @Test
    fun `МегаФон 24-10 15-24 - WB везёт при четвёртом провале основного, переключение этим же заходом`() {
        // 15:24:44: провалов подряд 3 при трёх подтверждениях — основной заведомо мёртв.
        val dead = AutoMode.mainKnownDeadIn(whitelistHint = false, mainFailures = 3, confirmations = 3)
        assertTrue(dead)
        val gate = AutoModeGate(3)
        gate.reset(Situation.Searching)
        // Первое наблюдение WB было ещё на двух провалах — подтверждение 1 из 3.
        assertFalse(gate.offer(Situation.Wb, proven = AutoMode.wbProven(Situation.Wb, mainKnownDeadIn(false, 2, 3), off = false)))
        // Следующий заход: основной мёртв по счёту — переключаемся, не дожидаясь третьего.
        assertTrue(gate.offer(Situation.Wb, proven = AutoMode.wbProven(Situation.Wb, dead, off = false)))
    }

    @Test
    fun `свежий белый список — WB с первого же наблюдения`() {
        val dead = AutoMode.mainKnownDeadIn(whitelistHint = true, mainFailures = 1, confirmations = 3)
        val gate = AutoModeGate(3)
        gate.reset(Situation.Searching)
        assertTrue(gate.offer(Situation.Wb, proven = AutoMode.wbProven(Situation.Wb, dead, off = false)))
    }

    private fun mainKnownDeadIn(w: Boolean, f: Int, c: Int) = AutoMode.mainKnownDeadIn(w, f, c)

    // ------------------------------------------------ 3. пауза WB

    @Test
    fun `пауза WB при мёртвом основном остаётся начальной, иначе растёт`() {
        val start = 60_000L
        assertEquals(start, AutoMode.nextTrialPause(start, mainKnownDead = true))
        assertEquals(start, AutoMode.nextTrialPause(4 * start, mainKnownDead = true))
        assertEquals(2 * start, AutoMode.nextTrialPause(start, mainKnownDead = false))
    }

    // ------------------------------------------------ пульт

    @Test
    fun `новые выключатели в реестре`() {
        assertNotNull(PultReestr.klyuch("vykl.wb_srazu"))
        assertNotNull(PultReestr.klyuch("vykl.pustoy_port"))
    }
}
