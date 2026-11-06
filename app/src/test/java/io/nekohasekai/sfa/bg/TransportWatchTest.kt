package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сторож транспорта: ритм проб и счётчик провалов, без Android. */
class TransportWatchTest {

    @Test
    fun `пауза держится в границах 30-60 с при включённом экране`() {
        assertEquals(TransportWatch.MIN_MILLIS, TransportWatch.slice(300_000, 1_000, screenOn = true))
        assertEquals(TransportWatch.MAX_MILLIS, TransportWatch.slice(300_000, 999_999, screenOn = true))
        assertEquals(45_000L, TransportWatch.slice(300_000, 45_000, screenOn = true))
    }

    @Test
    fun `экран выключен — шаг вдвое длиннее`() {
        assertEquals(90_000L, TransportWatch.slice(300_000, 45_000, screenOn = false))
        assertEquals(120_000L, TransportWatch.slice(300_000, 999_999, screenOn = false))
    }

    @Test
    fun `остаток до полного захода не перешагиваем`() {
        assertEquals(10_000L, TransportWatch.slice(10_000, 45_000, screenOn = true))
        assertEquals(10_000L, TransportWatch.slice(10_000, 45_000, screenOn = false))
    }

    @Test
    fun `один провал не будит, два подряд будят`() {
        val s = TransportWatch.Schetchik { 2 }
        assertFalse(s.fail())
        assertEquals(1, s.fails)
        assertTrue(s.fail())
        // После побудки счёт идёт с нуля.
        assertEquals(0, s.fails)
    }

    @Test
    fun `удача между провалами обнуляет счёт`() {
        val s = TransportWatch.Schetchik { 2 }
        assertFalse(s.fail())
        s.ok()
        assertFalse(s.fail())
        assertTrue(s.fail())
    }

    @Test
    fun `порог меньше единицы читается как один — каждый провал будит`() {
        val s = TransportWatch.Schetchik { 0 }
        assertTrue(s.fail())
    }

    @Test
    fun `сброс при смене обстановки забывает провалы`() {
        val s = TransportWatch.Schetchik { 2 }
        assertFalse(s.fail())
        s.reset()
        assertFalse(s.fail())
    }
}
