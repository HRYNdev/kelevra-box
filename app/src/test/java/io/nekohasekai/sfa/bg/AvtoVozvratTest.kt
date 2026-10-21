package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AvtoVozvrat.Fakty
import io.nekohasekai.sfa.bg.AvtoVozvrat.Povod
import io.nekohasekai.sfa.bg.AvtoVozvrat.Shag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «поднимать ли службу, которую человек включал, и когда звать его самого».
 * Подъём и уведомление живут в Android; выбор между ними — чистая логика.
 */
class AvtoVozvratTest {

    private fun f(
        povod: Povod = Povod.OBNOVLENIE,
        hotel: Boolean = true,
        zhiva: Boolean = false,
        vpn: Boolean = true,
        popytok: Int = 0,
        nedavno: Boolean = false,
    ) = Fakty(povod, hotel, zhiva, vpn, popytok, AvtoVozvrat.POPYTOK, nedavno)

    @Test
    fun `служба не была включена — ничего не делаем ни по какому поводу`() {
        for (p in Povod.values()) {
            assertEquals(p.name, Shag.NICHEGO, AvtoVozvrat.reshit(f(povod = p, hotel = false)))
        }
    }

    @Test
    fun `служба жива — ничего не делаем`() {
        for (p in Povod.values()) {
            assertEquals(p.name, Shag.NICHEGO, AvtoVozvrat.reshit(f(povod = p, zhiva = true)))
        }
    }

    @Test
    fun `после обновления включённая служба поднимается`() {
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(povod = Povod.OBNOVLENIE)))
    }

    @Test
    fun `после обновления поднимаем и при недавнем возврате — обновление новое событие`() {
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(povod = Povod.OBNOVLENIE, nedavno = true)))
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(povod = Povod.ZAGRUZKA, nedavno = true)))
    }

    @Test
    fun `вторая попытка разрешена, третьей нет — дальше уведомление`() {
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(popytok = 1)))
        assertEquals(Shag.UVEDOMIT, AvtoVozvrat.reshit(f(popytok = 2)))
        assertEquals(Shag.UVEDOMIT, AvtoVozvrat.reshit(f(popytok = 5)))
    }

    @Test
    fun `без разрешения VPN не поднимаем, а сразу зовём человека`() {
        for (p in Povod.values()) {
            assertEquals(p.name, Shag.UVEDOMIT, AvtoVozvrat.reshit(f(povod = p, vpn = false)))
        }
    }

    @Test
    fun `убита снова вскоре после нашего возврата — не ходим по кругу, зовём человека`() {
        assertEquals(Shag.UVEDOMIT, AvtoVozvrat.reshit(f(povod = Povod.UBITA, nedavno = true)))
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(povod = Povod.UBITA, nedavno = false)))
    }

    @Test
    fun `убита и в этом заходе уже пробовали — вторая попытка идёт, отметка круга её не рвёт`() {
        assertEquals(Shag.PODNYAT, AvtoVozvrat.reshit(f(povod = Povod.UBITA, nedavno = true, popytok = 1)))
    }

    @Test
    fun `окно круга — десять минут, часы назад не считаются`() {
        val t = 1_000_000_000L
        assertFalse("возврата не было", AvtoVozvrat.nedavno(t, 0L))
        assertTrue(AvtoVozvrat.nedavno(t, t - 60_000))
        assertTrue(AvtoVozvrat.nedavno(t, t - AvtoVozvrat.OKNO_KRUGA_MS + 1))
        assertFalse(AvtoVozvrat.nedavno(t, t - AvtoVozvrat.OKNO_KRUGA_MS))
        assertFalse("часы ушли назад", AvtoVozvrat.nedavno(t, t + 60_000))
    }

    @Test
    fun `уведомление не дублируется и напоминает не чаще шести часов`() {
        val t = 1_000_000_000L
        assertTrue("ни разу не показывали", AvtoVozvrat.pokazyvat(t, 0L, uzheVisit = false))
        assertFalse("уже висит", AvtoVozvrat.pokazyvat(t, 0L, uzheVisit = true))
        assertFalse("смахнули час назад", AvtoVozvrat.pokazyvat(t, t - 3_600_000, uzheVisit = false))
        assertTrue("смахнули давно", AvtoVozvrat.pokazyvat(t, t - AvtoVozvrat.POVTOR_UVEDOMLENIYA_MS, uzheVisit = false))
        assertTrue("часы ушли назад", AvtoVozvrat.pokazyvat(t, t + 1, uzheVisit = false))
    }

    @Test
    fun `запуск подтверждён только командой, пришедшей после отправки`() {
        assertFalse("команда не приходила", AvtoVozvrat.podtverdila(0L, 500L, zhiva = false))
        assertFalse("старая команда не в счёт", AvtoVozvrat.podtverdila(400L, 500L, zhiva = false))
        assertTrue(AvtoVozvrat.podtverdila(500L, 500L, zhiva = false))
        assertTrue(AvtoVozvrat.podtverdila(900L, 500L, zhiva = false))
        assertTrue("служба уже жива", AvtoVozvrat.podtverdila(0L, 500L, zhiva = true))
    }

    @Test
    fun `тексты уведомления официальные и без слова защита`() {
        for (p in Povod.values()) {
            val (z, t) = AvtoVozvrat.tekst(p)
            assertTrue(z, z.startsWith("Kelevra"))
            assertTrue(t, t.contains("Нажмите, чтобы включить"))
            assertFalse("$z $t", "$z $t".lowercase().contains("защит"))
        }
        assertEquals("Kelevra не включилась после обновления", AvtoVozvrat.tekst(Povod.OBNOVLENIE).first)
    }

    @Test
    fun `причина в журнале называет исключение целиком`() {
        val e = IllegalStateException("startForegroundService() not allowed")
        val s = AvtoVozvrat.prichina(f(popytok = 2), e)
        assertTrue(s, s.contains("java.lang.IllegalStateException"))
        assertTrue(s, s.contains("not allowed"))
        assertTrue(AvtoVozvrat.prichina(f(vpn = false), null).contains("VPN"))
        assertTrue(AvtoVozvrat.prichina(f(popytok = 2), null).contains("без исключения"))
    }
}
