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
}
