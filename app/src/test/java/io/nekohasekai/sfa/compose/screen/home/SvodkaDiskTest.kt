package io.nekohasekai.sfa.compose.screen.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сводка /info целиком: разбор ответа и запись на диск для старта без сети. */
class SvodkaDiskTest {
    private val otvet = """
        {"name":"Вова","active":true,"expires":"2026-12-01","limit_bytes":0,"used_bytes":5,
         "person":{"name":"Вова"},"device":{"name":"OnePlus"},"panel":true,
         "reply":{"id":3,"text":"починили","about":"не работает"},
         "olcrtc":{"room_id":"r"},"send_log_now":true,
         "channels":[{"name":"Нидерланды","transport":"xhttp"}],
         "pult":{"dannye":"e30=","podpis":"AAAA"}}
    """.trimIndent()

    @Test
    fun `разбор сводки — все поля карточки, панели и ответа`() {
        val info = razobratSvodku(JSONObject(otvet))
        assertEquals("Вова", info.name)
        assertTrue(info.active)
        assertTrue(info.panel)
        assertEquals("починили", info.reply!!.text)
        assertEquals("OnePlus", info.deviceName)
        assertEquals(mapOf("Нидерланды" to "xhttp"), info.transports)
        assertEquals(false, info.izKesha)
    }

    @Test
    fun `запись диска разбирается обратно, мусор — null`() {
        val zapis = JSONObject().put("baza", "https://h/k/K").put("kogda", 5L).put("etag", "\"42\"").put("tekst", otvet).toString()
        val z = SvodkaDisk.razobrat(zapis)!!
        assertEquals("https://h/k/K", z.baza)
        assertEquals("\"42\"", z.etag)
        assertEquals(otvet, z.tekst)
        assertNull(SvodkaDisk.razobrat("не json"))
        assertNull(SvodkaDisk.razobrat("""{"baza":"x"}"""))
    }
}
