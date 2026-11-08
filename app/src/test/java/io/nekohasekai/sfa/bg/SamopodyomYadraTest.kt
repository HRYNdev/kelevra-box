package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.SamopodyomYadra.Diagnoz
import io.nekohasekai.sfa.bg.SamopodyomYadra.Reshenie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Самоподъём ядра: сеть есть, канал мёртв — перезапуск по лестнице. Часы свои, без Android:
 * заходы подаются так, как их шлёт автомат (серия после смены сети 1/3/8/20 с, поиск 12 с).
 */
class SamopodyomYadraTest {

    private val s = SamopodyomYadra()

    /** Подаёт наблюдения в моменты [t] (секунды), возвращает моменты перезапусков. */
    private fun gnat(d: Diagnoz?, vararg t: Long): List<Long> = t.toList().mapNotNull { sec ->
        val (r, _) = s.nablyudenie(d, sec * 1000)
        if (r is Reshenie.Perezapusk) {
            s.perezapushcheno(sec * 1000)
            sec
        } else {
            null
        }
    }

    private fun kazhdye(ot: Long, `do`: Long, shag: Long): LongArray = (ot..`do` step shag).toList().toLongArray()

    @Test
    fun `после смены сети — перезапуск через 30 с от смены, не раньше`() {
        // Случай ноутбука 07.11 в переложении на телефон: Wi-Fi выключили, сети нет — ядро
        // не трогаем; сеть вернулась, адрес узла отвечает, канал пуст — через 30 с перезапуск.
        assertEquals(emptyList<Long>(), gnat(Diagnoz.NetSeti, *kazhdye(0, 300, 12)))
        assertEquals(Diagnoz.NetSeti, s.diagnoz)
        s.smenaSeti(310_000)
        val r = gnat(Diagnoz.TunnelMertv, 311, 313, 318, 330, 342, 354)
        assertEquals(listOf(342L), r)
        assertEquals(1, s.stupen)
    }

    @Test
    fun `без смены сети — перезапуск через 45 с подтверждённой смерти`() {
        val r = gnat(Diagnoz.TunnelMertv, 0, 12, 24, 36, 48, 60)
        assertEquals(listOf(48L), r)
    }

    @Test
    fun `одна мёртвая проба — не довод`() {
        // Один заход «пусто», следующий через минуту живой: перезапуска нет.
        assertEquals(emptyList<Long>(), gnat(Diagnoz.TunnelMertv, 0))
        assertEquals(emptyList<Long>(), gnat(Diagnoz.Ok, 60))
        // Две пробы с разрывом больше порога — уже довод.
        assertEquals(listOf(170L), gnat(Diagnoz.TunnelMertv, 100, 170))
    }

    @Test
    fun `после смены сети один пустой заход после 30 с — ещё не перезапуск`() {
        // Первый заход на новой сети пришёл поздно (сеть долго вставала): срок от смены уже
        // вышел, но довод один — ждём второго захода.
        s.smenaSeti(1_000)
        assertEquals(emptyList<Long>(), gnat(Diagnoz.TunnelMertv, 36))
        assertEquals(listOf(40L), gnat(Diagnoz.TunnelMertv, 40))
    }

    @Test
    fun `нет сети, узлы недоступны, белый список — ядро не трогаем и время не копим`() {
        assertEquals(emptyList<Long>(), gnat(Diagnoz.UzlyNedostupny, *kazhdye(0, 1800, 12)))
        assertEquals(emptyList<Long>(), gnat(Diagnoz.BelyySpisok, *kazhdye(1812, 3600, 12)))
        assertEquals(emptyList<Long>(), gnat(Diagnoz.NetSeti, *kazhdye(3612, 5400, 12)))
        // Канал опустел только сейчас — отсчёт с нуля, а не с получаса назад.
        assertEquals(listOf(5460L), gnat(Diagnoz.TunnelMertv, 5412, 5424, 5436, 5448, 5460))
    }

    @Test
    fun `ожил сам — перезапуска нет`() {
        repeat(5) { i ->
            val t = i * 100L
            assertEquals(emptyList<Long>(), gnat(Diagnoz.TunnelMertv, t, t + 12, t + 24, t + 30))
            assertEquals(emptyList<Long>(), gnat(Diagnoz.Ok, t + 40))
        }
        assertEquals(0, s.stupen)
    }

    @Test
    fun `лестница 2, 5, 15, 15 мин и сброс после 10 мин живого канала`() {
        val r = gnat(Diagnoz.TunnelMertv, *kazhdye(0, 3000, 12))
        // 48 — первая ступень, дальше промежутки 120, 300, 900, 900 с (на сетке заходов в 12 с).
        assertEquals(listOf(48L, 168L, 468L, 1368L, 2268L), r)
        assertEquals(5, s.stupen)
        // 9 минут живого канала — лестница помнит ступени.
        gnat(Diagnoz.Ok, *kazhdye(3010, 3010 + 540, 60))
        assertEquals(5, s.stupen)
        // 10 минут — сначала.
        gnat(Diagnoz.Ok, 3010 + 600)
        assertEquals(0, s.stupen)
        assertEquals(listOf(3700L), gnat(Diagnoz.TunnelMertv, 3652, 3664, 3676, 3688, 3700))
    }

    @Test
    fun `смена сети, канал ожил и снова умер — обычные 45 с`() {
        s.smenaSeti(0)
        gnat(Diagnoz.TunnelMertv, 3, 8)
        gnat(Diagnoz.Ok, 20)
        assertEquals(listOf(97L), gnat(Diagnoz.TunnelMertv, 50, 62, 74, 86, 97))
    }

    @Test
    fun `смена сети давнее пяти минут — первая ступень по обычному правилу`() {
        s.smenaSeti(0)
        assertEquals(listOf(405L), gnat(Diagnoz.TunnelMertv, 360, 372, 384, 396, 405))
    }

    @Test
    fun `не судим — счёт мёртвого времени сброшен`() {
        gnat(Diagnoz.TunnelMertv, 0, 12, 24, 36)
        gnat(null, 40)
        assertNull(s.diagnoz)
        assertEquals(listOf(100L), gnat(Diagnoz.TunnelMertv, 50, 62, 74, 86, 100))
    }

    @Test
    fun `перезапуск не состоялся — ступень не тратится, решает следующий заход`() {
        val (r, _) = s.nablyudenie(Diagnoz.TunnelMertv, 0)
        assertEquals(Reshenie.Nichego, r)
        s.nablyudenie(Diagnoz.TunnelMertv, 24_000)
        val (r2, _) = s.nablyudenie(Diagnoz.TunnelMertv, 48_000)
        assertTrue(r2 is Reshenie.Perezapusk)
        // Замок был занят — perezapushcheno не звали: следующий заход снова просит перезапуск.
        val (r3, _) = s.nablyudenie(Diagnoz.TunnelMertv, 60_000)
        assertEquals(1, (r3 as Reshenie.Perezapusk).stupen)
    }

    @Test
    fun `сброс человеком — всё сначала`() {
        gnat(Diagnoz.TunnelMertv, *kazhdye(0, 200, 12))
        assertEquals(2, s.stupen)
        s.sbros()
        assertEquals(0, s.stupen)
        assertNull(s.diagnoz)
        assertEquals("diagnoz=- stupen=0", s.stroka())
    }

    @Test
    fun `строка журнала — одна на смену состояния`() {
        val stroki = listOf(
            Diagnoz.NetSeti, Diagnoz.NetSeti, Diagnoz.TunnelMertv, Diagnoz.TunnelMertv, Diagnoz.Ok, Diagnoz.Ok,
        ).mapIndexed { i, d -> s.nablyudenie(d, i * 10_000L).second }
        assertTrue(stroki[0]!!.contains("сети нет"))
        assertNull(stroki[1])
        assertTrue(stroki[2]!!.contains("перезапущу ядро"))
        assertNull(stroki[3])
        assertEquals("самоподъём: туннель ожил", stroki[4])
        assertNull(stroki[5])
    }

    @Test
    fun `диагноз захода`() {
        assertEquals(Diagnoz.Ok, SamopodyomYadra.diagnozZahoda(true, false, null, belyy = false))
        // Задушенный канал: трафик шёл — перезапуск не лечит.
        assertEquals(Diagnoz.Ok, SamopodyomYadra.diagnozZahoda(false, true, true, belyy = false))
        assertEquals(Diagnoz.TunnelMertv, SamopodyomYadra.diagnozZahoda(false, true, false, belyy = false))
        assertEquals(Diagnoz.BelyySpisok, SamopodyomYadra.diagnozZahoda(false, true, false, belyy = true))
        assertEquals(Diagnoz.UzlyNedostupny, SamopodyomYadra.diagnozZahoda(false, false, false, belyy = false))
        assertEquals(Diagnoz.UzlyNedostupny, SamopodyomYadra.diagnozZahoda(false, false, null, belyy = false))
        // Канал не мерили (туннель погашен, входа нет) или адресов нет — не судим.
        assertNull(SamopodyomYadra.diagnozZahoda(false, true, null, belyy = false))
        assertNull(SamopodyomYadra.diagnozZahoda(false, null, false, belyy = false))
    }
}
