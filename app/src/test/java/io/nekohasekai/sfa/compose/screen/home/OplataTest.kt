package io.nekohasekai.sfa.compose.screen.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Оплата подписки (KelevraServer/docs/OPLATA.md): разбор ответа двери и срок. */
class OplataTest {
    private val den = 24L * 60 * 60 * 1000

    @Test
    fun `платящему — все поля`() {
        val o = razobratOplatu(
            JSONObject(
                """{"platit": true, "cena": 249, "ssylka": "https://t.tb.ru/c2c-qr-choose-bank?requisiteNumber=1&bankCode=2",
                   "nomer": "+7 900 000-00-00", "srok": "2026-12-12T07:00:00Z", "zhdet": false}""",
            ),
        )!!
        assertTrue(o.platit)
        assertEquals(249, o.cena)
        assertEquals("https://t.tb.ru/c2c-qr-choose-bank?requisiteNumber=1&bankCode=2", o.ssylka)
        assertEquals("+7 900 000-00-00", o.nomer)
        assertEquals(1797058800000L, o.srokMs)
        assertFalse(o.zhdet)
    }

    @Test
    fun `неплатящему — только platit false`() {
        assertEquals(OplataInfo(platit = false), razobratOplatu(JSONObject("""{"platit": false}""")))
    }

    @Test
    fun `не ответ оплаты (старая выдача отдала профиль) — null`() {
        assertNull(razobratOplatu(JSONObject("""{"outbounds": [], "route": {}}""")))
    }

    @Test
    fun `пустые ссылка, номер и срок, ссылка не https — как нет`() {
        val o = razobratOplatu(JSONObject("""{"platit": true, "cena": 249, "ssylka": "", "nomer": "", "srok": "", "zhdet": true}"""))!!
        assertEquals("", o.ssylka)
        assertEquals("", o.nomer)
        assertEquals(0L, o.srokMs)
        assertTrue(o.zhdet)
        val chuzhaya = razobratOplatu(JSONObject("""{"platit": true, "ssylka": "intent://x#Intent;end"}"""))!!
        assertEquals("", chuzhaya.ssylka)
        val nul = razobratOplatu(JSONObject("""{"platit": true, "ssylka": null, "nomer": null}"""))!!
        assertEquals("", nul.ssylka)
        assertEquals("", nul.nomer)
    }

    @Test
    fun `срок голой датой — полночь UTC, мусор — 0`() {
        assertEquals(1797033600000L, srokMillis("2026-12-12"))
        assertEquals(0L, srokMillis("завтра"))
        assertEquals(0L, srokMillis(null))
    }

    @Test
    fun `срок — обычный, скоро, кончился, бессрочно`() {
        val now = 1_800_000_000_000L
        assertEquals(SrokOplaty.Obychnyj, srokOplaty(0L, now))
        assertEquals(SrokOplaty.Obychnyj, srokOplaty(now + 4 * den, now))
        assertEquals(SrokOplaty.Skoro, srokOplaty(now + 3 * den, now))
        assertEquals(SrokOplaty.Skoro, srokOplaty(now + 1, now))
        assertEquals(SrokOplaty.Konchilsya, srokOplaty(now, now))
        assertEquals(SrokOplaty.Konchilsya, srokOplaty(now - den, now))
    }

    @Test
    fun `дата ДД_ММ по часам телефона`() {
        val ms = srokMillis("2026-11-13T21:00:00Z")
        assertEquals("14.11", srokDdMm(ms, TimeZone.getTimeZone("Asia/Yekaterinburg")))
        assertEquals("13.11", srokDdMm(ms, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `опрос — час, при ждущей заявке пять минут`() {
        assertEquals(60L * 60 * 1000, shagOprosaOplaty(false))
        assertEquals(5L * 60 * 1000, shagOprosaOplaty(true))
    }

    private val ekb = TimeZone.getTimeZone("Asia/Yekaterinburg")

    @Test
    fun `история — свежие первыми, без даты заявки пропуск, нет поля — пусто`() {
        val o = razobratOplatu(
            JSONObject(
                """{"platit": true, "cena": 249, "srok": "2026-12-12T07:00:00Z", "zhdet": false, "istoriya": [
                   {"data": "2026-11-12T09:30:00Z", "summa": 249, "do": "2026-12-12T07:00:00Z"},
                   {"data": "", "summa": 249, "do": "2026-11-12T07:00:00Z"},
                   {"data": "2026-10-12T09:00:00Z", "summa": -5, "do": ""}]}""",
            ),
        )!!
        assertEquals(
            listOf(
                OplataZapis(srokMillis("2026-11-12T09:30:00Z"), 249, srokMillis("2026-12-12T07:00:00Z")),
                OplataZapis(srokMillis("2026-10-12T09:00:00Z"), 0, 0L),
            ),
            o.istoriya,
        )
        assertEquals(emptyList<OplataZapis>(), razobratOplatu(JSONObject("""{"platit": true}"""))!!.istoriya)
        assertEquals(emptyList<OplataZapis>(), razobratOplatu(JSONObject("""{"platit": true, "istoriya": null}"""))!!.istoriya)
    }

    @Test
    fun `дата заявки ДД_ММ_ГГГГ по часам телефона`() {
        assertEquals("13.11.2026", dataDdMmGggg(srokMillis("2026-11-12T20:00:00Z"), ekb))
        assertEquals("12.11.2026", dataDdMmGggg(srokMillis("2026-11-12T20:00:00Z"), TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `карточка — далеко оплачено без строки, скоро и кончился со строкой, ждёт — проверяется`() {
        val now = 1_800_000_000_000L
        fun v(srok: Long, zhdet: Boolean = false) = vidOplaty(OplataInfo(platit = true, cena = 249, srokMs = srok, zhdet = zhdet), now)
        assertEquals(VidOplaty(ZagolovokOplaty.OplachenoDo, false, null), v(now + 10 * den))
        assertEquals(VidOplaty(ZagolovokOplaty.OplachenoDo, false, StrokaOplaty.Proveryaetsya), v(now + 10 * den, zhdet = true))
        assertEquals(VidOplaty(ZagolovokOplaty.PodpiskaDo, true, StrokaOplaty.Oplatit), v(now + 2 * den))
        assertEquals(VidOplaty(ZagolovokOplaty.PodpiskaDo, true, StrokaOplaty.Proveryaetsya), v(now + 2 * den, zhdet = true))
        assertEquals(VidOplaty(ZagolovokOplaty.Zakonchilas, true, StrokaOplaty.Oplatit), v(now - 1))
        assertEquals(VidOplaty(ZagolovokOplaty.Zakonchilas, true, StrokaOplaty.Proveryaetsya), v(now - 1, zhdet = true))
        assertEquals(VidOplaty(ZagolovokOplaty.KakBylo, false, null), v(0L))
        assertEquals(VidOplaty(ZagolovokOplaty.KakBylo, false, StrokaOplaty.Proveryaetsya), v(0L, zhdet = true))
    }

    private fun platit(srok: String, zhdet: Boolean = false) =
        OplataInfo(platit = true, cena = 249, srokMs = srokMillis(srok), zhdet = zhdet)

    @Test
    fun `напоминания — за трое суток в полдень и в день срока`() {
        // 12:00 по Екатеринбургу 12.12.
        val info = platit("2026-12-12T07:00:00Z")
        val now = info.srokMs - 10 * den
        assertEquals(
            listOf(
                Napominanie(VidNapominaniya.Skoro, srokMillis("2026-12-09T07:00:00Z")),
                Napominanie(VidNapominaniya.Konec, srokMillis("2026-12-12T07:00:00Z")),
            ),
            napominaniya(info, now, ekb),
        )
    }

    @Test
    fun `напоминания — полдень за трое суток прошёл: только окончание`() {
        val info = platit("2026-12-12T07:00:00Z")
        val now = srokMillis("2026-12-09T07:01:00Z")
        assertEquals(
            listOf(Napominanie(VidNapominaniya.Konec, info.srokMs)),
            napominaniya(info, now, ekb),
        )
    }

    @Test
    fun `напоминание об окончании — не раньше срока и не ночью`() {
        val now = srokMillis("2026-11-01T00:00:00Z")
        fun konec(srok: String) = napominaniya(platit(srok), now, ekb).single { it.vid == VidNapominaniya.Konec }.kogdaMs
        // 07:00 местного — в полдень того же дня.
        assertEquals(srokMillis("2026-12-12T07:00:00Z"), konec("2026-12-12T02:00:00Z"))
        // 15:00 местного — в момент срока.
        assertEquals(srokMillis("2026-12-12T10:00:00Z"), konec("2026-12-12T10:00:00Z"))
        // 23:00 местного — в полдень следующего дня.
        assertEquals(srokMillis("2026-12-13T07:00:00Z"), konec("2026-12-12T18:00:00Z"))
        // «За трое суток» считается по дню срока: 09.12 12:00.
        assertEquals(
            srokMillis("2026-12-09T07:00:00Z"),
            napominaniya(platit("2026-12-12T18:00:00Z"), now, ekb).single { it.vid == VidNapominaniya.Skoro }.kogdaMs,
        )
    }

    @Test
    fun `напоминаний нет — срок прошёл, ждёт заявка, бессрочно, неплатящий`() {
        val info = platit("2026-12-12T07:00:00Z")
        assertEquals(emptyList<Napominanie>(), napominaniya(info, info.srokMs, ekb))
        assertEquals(emptyList<Napominanie>(), napominaniya(info.copy(zhdet = true), info.srokMs - 10 * den, ekb))
        assertEquals(emptyList<Napominanie>(), napominaniya(info.copy(srokMs = 0L), 0L, ekb))
        assertEquals(emptyList<Napominanie>(), napominaniya(OplataInfo(platit = false), 0L, ekb))
        assertEquals(emptyList<Napominanie>(), napominaniya(null, 0L, ekb))
    }
}
