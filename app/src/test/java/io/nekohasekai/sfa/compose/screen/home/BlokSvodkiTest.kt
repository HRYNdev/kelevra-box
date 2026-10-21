package io.nekohasekai.sfa.compose.screen.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Комната и WB Chat в сводке (KelevraServer/docs/PULT.md, «Комната и WB Chat»): ключа нет — держать прежнее,
 * явный null — стереть, блок — применить. Раньше «нет блока» стирало, и один сбойный ответ обнулял комнату.
 */
class BlokSvodkiTest {
    private val komnata = """{"carrier":"wbstream","room_id":"komnata-1","key_hex":"ab","transport":"vp8channel","socks_port":8808}"""

    /** Что дошло бы до applyServer: список вызовов (null — «стереть»). */
    private fun vyzovy(blok: BlokSvodki): List<JSONObject?> {
        val bylo = mutableListOf<JSONObject?>()
        primenitBlok(blok) { bylo += it }
        return bylo
    }

    @Test
    fun `ключа нет — сервер молчит, applyServer не зовётся, комната цела`() {
        val info = razobratSvodku(JSONObject("""{"name":"Вова","active":true}"""))
        assertEquals(BlokSvodki.Molchit, info.olcrtc)
        assertEquals(BlokSvodki.Molchit, info.wbchat)
        assertTrue(vyzovy(info.olcrtc).isEmpty())
        assertTrue(vyzovy(info.wbchat).isEmpty())
    }

    @Test
    fun `явный null — сервер выключил, applyServer зовётся с null (стереть)`() {
        val info = razobratSvodku(JSONObject("""{"name":"Вова","olcrtc":null,"wbchat":null}"""))
        assertEquals(BlokSvodki.Vyklyuchen, info.olcrtc)
        assertEquals(BlokSvodki.Vyklyuchen, info.wbchat)
        val v = vyzovy(info.olcrtc)
        assertEquals(1, v.size)
        assertNull(v[0])
        assertEquals(1, vyzovy(info.wbchat).size)
    }

    @Test
    fun `блок пришёл — применяется как есть`() {
        val info = razobratSvodku(JSONObject("""{"name":"Вова","olcrtc":$komnata,"wbchat":{"socks_port":8809,"peer":"p"}}"""))
        assertEquals("komnata-1", vyzovy(info.olcrtc).single()!!.optString("room_id"))
        assertEquals("p", vyzovy(info.wbchat).single()!!.optString("peer"))
    }

    @Test
    fun `мусор вместо блока — как молчание, не стирает`() {
        val json = JSONObject("""{"olcrtc":"slomano","wbchat":[1,2]}""")
        assertEquals(BlokSvodki.Molchit, blokSvodki(json, "olcrtc"))
        assertEquals(BlokSvodki.Molchit, blokSvodki(json, "wbchat"))
        assertFalse(primenitBlok(blokSvodki(json, "olcrtc")) { error("не должен зваться") })
    }

    @Test
    fun `вид - ключа нет, держим прежний вид`() {
        val info = razobratSvodku(JSONObject("""{"name":"Вова","olcrtc":$komnata}"""))
        assertEquals(BlokSvodki.Molchit, info.vid)
        assertTrue(vyzovy(info.vid).isEmpty())
    }

    @Test
    fun `вид - явный null снимает вид, блок применяется`() {
        val snyat = razobratSvodku(JSONObject("""{"name":"Вова","vid":null}"""))
        assertEquals(BlokSvodki.Vyklyuchen, snyat.vid)
        assertNull(vyzovy(snyat.vid).single())
        val est = razobratSvodku(JSONObject("""{"name":"Вова","vid":{"versiya":3,"teksty":{"k":"t"}}}"""))
        assertEquals(3, vyzovy(est.vid).single()!!.optInt("versiya"))
    }

    @Test
    fun `старый сервер без блока и старая запись диска разбираются без падения`() {
        // Запись диска, сделанная до этой правки (с блоком), поднимается как раньше.
        val sDiska = razobratSvodku(JSONObject("""{"name":"Вова","olcrtc":{"room_id":"r"}}""")).copy(izKesha = true)
        assertTrue(sDiska.olcrtc is BlokSvodki.Est)
        assertEquals(BlokSvodki.Molchit, sDiska.wbchat)
    }
}
