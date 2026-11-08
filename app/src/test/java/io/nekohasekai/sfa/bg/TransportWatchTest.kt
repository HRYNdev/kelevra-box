package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сторож транспорта: окно проб после смены сети и счётчик провалов, без Android. */
class TransportWatchTest {

    private val window = 180_000L
    private val step = 30_000L

    /** Прогон ожидания основного канала: времена проб от открытия окна до конца полного ритма. */
    private fun probes(okno: TransportWatch.Okno, from: Long, round: Long): List<Long> {
        val out = mutableListOf<Long>()
        var now = from
        val end = from + round
        while (now < end) {
            val slice = TransportWatch.slice(end - now, okno.left(now), step)
            if (slice <= 0L) break // вне окна — просто ждём до конца ритма
            now += slice
            if (now >= end) break
            if (okno.isOpen(now)) out += now
        }
        return out
    }

    @Test
    fun `без смены сети окно не открыто и проб нет`() {
        val okno = TransportWatch.Okno { window }
        assertEquals(0L, okno.left(1_000_000))
        assertTrue(probes(okno, 1_000_000, 300_000).isEmpty())
    }

    @Test
    fun `в окне пробы идут с шагом, после окна молчат`() {
        val okno = TransportWatch.Okno { window }
        okno.open(0)
        // Пятиминутный ритм: пробы на 30, 60, 90, 120, 150 с; на 180 с окно закрыто.
        assertEquals(listOf(30_000L, 60_000L, 90_000L, 120_000L, 150_000L), probes(okno, 0, 300_000))
        assertFalse(okno.isOpen(180_000))
        assertTrue(probes(okno, 180_000, 300_000).isEmpty())
    }

    @Test
    fun `остаток до полного захода не перешагиваем`() {
        val okno = TransportWatch.Okno { window }
        okno.open(0)
        assertEquals(10_000L, TransportWatch.slice(10_000, 100_000, step))
        assertEquals(5_000L, TransportWatch.slice(300_000, 5_000, step))
        assertEquals(0L, TransportWatch.slice(300_000, 0, step))
    }

    @Test
    fun `новая смена сети открывает окно заново`() {
        val okno = TransportWatch.Okno { window }
        okno.open(0)
        assertFalse(okno.isOpen(200_000))
        okno.open(400_000)
        assertTrue(okno.isOpen(400_001))
        assertEquals(listOf(430_000L, 460_000L, 490_000L, 520_000L, 550_000L), probes(okno, 400_000, 300_000))
        // Смена посреди окна продлевает его от новой точки.
        okno.open(500_000)
        assertEquals(180_000L, okno.left(500_000))
    }

    @Test
    fun `закрытое окно проб не даёт`() {
        val okno = TransportWatch.Okno { window }
        okno.open(0)
        okno.close()
        assertFalse(okno.isOpen(1))
    }

    @Test
    fun `два провала в окне будят заход, один нет`() {
        val okno = TransportWatch.Okno { window }
        val s = TransportWatch.Schetchik { 2 }
        okno.open(0)
        val times = probes(okno, 0, 300_000)
        // Первая проба провалилась, вторая тоже — заход будится на второй.
        assertFalse(s.fail())
        assertTrue(s.fail())
        assertTrue(times.size >= 2)
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
