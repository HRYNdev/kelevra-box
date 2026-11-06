package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гистерезис на гашение туннеля дома: уход из дома сразу, приход домой с выдержкой,
 * дёрганье вайфая не плодит пересборок.
 */
class DomZaderzhkaTest {

    private val hold = 45_000L

    /** Одно наблюдение захода: когда, что увидели, была ли только что смена сети. */
    private data class Zahod(val t: Long, val home: Boolean, val netChanged: Boolean = false)

    /**
     * Прогон последовательности так, как с ней обращается автомат: «не дома» поднимает туннель
     * сразу, «дома» гасит только когда выдержка набрана. Возвращает число гашений и подъёмов.
     */
    private fun progon(z: DomZaderzhka?, zahody: List<Zahod>): Pair<Int, Int> {
        var tunnelUp = true
        var suspends = 0
        var raises = 0
        for (s in zahody) {
            z?.observe(s.home, s.netChanged, s.t)
            if (s.home) {
                if (tunnelUp && (z == null || z.ready(s.t))) {
                    tunnelUp = false
                    suspends++
                }
            } else if (!tunnelUp) {
                tunnelUp = true
                raises++
            }
        }
        return suspends to raises
    }

    /**
     * Домашний вайфай дёргается каждые ~25 с (журнал одного из телефонов, 31.10.2026 20:54–20:58):
     * каждое переключение — смена сети, заход сразу, вердикт без подтверждений.
     */
    private val dergayushchiysyaVayfay = listOf(
        Zahod(0, home = false),
        Zahod(100_000, home = true, netChanged = true),
        Zahod(125_000, home = false, netChanged = true),
        Zahod(150_000, home = true, netChanged = true),
        Zahod(175_000, home = false, netChanged = true),
        Zahod(200_000, home = true, netChanged = true),
        Zahod(225_000, home = false, netChanged = true),
        Zahod(250_000, home = true, netChanged = true),
        // Вайфай устоялся: дальше заходы без смены сети.
        Zahod(262_000, home = true),
        Zahod(274_000, home = true),
        Zahod(286_000, home = true),
        Zahod(298_000, home = true),
        Zahod(600_000, home = true),
        // Вышли из дома.
        Zahod(900_000, home = false, netChanged = true),
    )

    @Test
    fun `без гистерезиса дёрганье вайфая — пересборка на каждое переключение`() {
        val (suspends, raises) = progon(null, dergayushchiysyaVayfay)
        // Четыре прихода «домой» — четыре гашения; три дёргания плюс уход — четыре подъёма.
        assertEquals(4, suspends)
        assertEquals(4, raises)
    }

    @Test
    fun `с гистерезисом — одно гашение, когда вайфай устоялся, и один подъём при уходе`() {
        val (suspends, raises) = progon(DomZaderzhka(hold), dergayushchiysyaVayfay)
        assertEquals(1, suspends)
        // Подъём на первом же дёргании (туннель не гасился, дальше поднимать нечего) — и при уходе.
        assertEquals(1, raises)
    }

    @Test
    fun `гашение случается на первом заходе после выдержки, не раньше`() {
        val z = DomZaderzhka(hold)
        z.observe(home = true, networkChanged = true, now = 250_000)
        assertFalse(z.ready(262_000))
        assertFalse(z.ready(286_000))
        assertTrue(z.pending(286_000))
        assertEquals(9_000L, z.leftMillis(286_000))
        assertTrue(z.ready(298_000))
        assertEquals(0L, z.leftMillis(298_000))
    }

    @Test
    fun `уход из дома с туннелем — сразу, выдержка подъём не задерживает`() {
        // Класс отвечает только за гашение: наблюдение «не дома» обнуляет отсчёт, а решение
        // поднимать автомат принимает тем же заходом — проверяем, что отсчёт не мешает.
        val z = DomZaderzhka(hold)
        z.observe(home = true, networkChanged = true, now = 5_000)
        z.observe(home = false, networkChanged = true, now = 20_000)
        assertEquals(0L, z.since)
        assertFalse(z.pending(20_000))
    }

    @Test
    fun `смена сети при том же «дома» начинает выдержку заново`() {
        val z = DomZaderzhka(hold)
        z.observe(home = true, networkChanged = true, now = 10_000)
        z.observe(home = true, networkChanged = false, now = 30_000)
        assertEquals(10_000L, z.since)
        z.observe(home = true, networkChanged = true, now = 40_000)
        assertEquals(40_000L, z.since)
        assertFalse(z.ready(60_000))
        assertTrue(z.ready(85_000))
    }

    @Test
    fun `старт службы дома — ядра нет, выдержка ни на что не влияет`() {
        // Туннель уже погашен: проверка ready не зовётся вовсе, но и отсчёт начинается честно.
        val z = DomZaderzhka(hold)
        z.observe(home = true, networkChanged = false, now = 1_000)
        assertTrue(z.pending(1_000))
        assertTrue(z.ready(46_000))
    }

    @Test
    fun `нулевая выдержка — как было до правки, гасим сразу`() {
        val z = DomZaderzhka(0L)
        z.observe(home = true, networkChanged = true, now = 5_000)
        assertTrue(z.ready(5_000))
    }
}
