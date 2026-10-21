package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.bg.path.HonestProbe
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Реестр ключей пульта: таблица `docs/PULT-KLYUCHI-box.md` совпадает с кодом, умолчания
 * внутри границ, списки и выключатели проверяются по формату.
 */
class PultKlyuchiDocTest {
    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    @Test
    fun `таблица ключей в docs совпадает с реестром`() {
        val tekst = PultReestr.markdown()
        // Свежая таблица всегда кладётся рядом со сборкой: из неё обновляется docs.
        File("build/pult-klyuchi-box.md").apply { parentFile?.mkdirs() }.writeText(tekst)
        val doc = File("../docs/PULT-KLYUCHI-box.md")
        assertTrue("нет ${doc.absolutePath}", doc.exists())
        assertEquals("docs/PULT-KLYUCHI-box.md устарел — скопируйте app/build/pult-klyuchi-box.md", tekst, doc.readText().replace("\r\n", "\n"))
    }

    @Test
    fun `ключи уникальны, умолчания внутри границ`() {
        val vse = PultReestr.vse
        assertEquals(vse.size, vse.map { it.imya }.toSet().size)
        assertTrue("ключей ${vse.size}", vse.size > 100)
        for (k in vse.filter { it.tip == PultReestr.Tip.CELOE }) {
            val d = (k.umolch as Int).toLong()
            assertTrue("${k.imya}: $d вне ${k.min}..${k.max}", d in k.min!!..k.max!!)
            assertEquals(k.imya, k.umolch, PultReestr.proverit(k.imya, k.umolch))
        }
        for (k in vse.filter { it.imya.startsWith("vykl.") }) assertEquals(false, k.umolch)
    }

    @Test
    fun `списки с пульта проверяются построчно`() {
        assertEquals(listOf("youtube.com", "x.ru"), PultReestr.proverit("dom.domeny", JSONArray(listOf("youtube.com", "x.ru"))))
        assertNull(PultReestr.proverit("dom.domeny", JSONArray(listOf("youtube.com", "evil;rm -rf"))))
        assertEquals(listOf("10.0.0.0/8", "2a02:6b8::/32"), PultReestr.proverit("belyj_spisok.podseti", JSONArray(listOf("10.0.0.0/8", "2a02:6b8::/32"))))
        assertNull(PultReestr.proverit("belyj_spisok.podseti", JSONArray(listOf("10.0.0.0"))))
        assertEquals("gosuslugi.ru", PultReestr.proverit("dom.kontrol", "gosuslugi.ru"))
        assertNull(PultReestr.proverit("dom.kontrol", "http://gosuslugi.ru"))
        assertEquals("www.gstatic.com:80/generate_204", PultReestr.proverit("skorost.cel", "www.gstatic.com:80/generate_204"))
        assertNull(PultReestr.proverit("rezhim_seti.neraz_adresa", JSONArray(listOf("8.8.8.8", "dns.google"))))
    }

    @Test
    fun `цели пробы с пульта разбираются, кривые отбрасываются`() {
        assertEquals(HonestProbe.Target("connectivitycheck.gstatic.com", 80, "/generate_204"), HonestProbe.razobratCel("connectivitycheck.gstatic.com:80/generate_204"))
        assertEquals(HonestProbe.Target("a.ru", 8080, "/"), HonestProbe.razobratCel("a.ru:8080/"))
        assertNull(HonestProbe.razobratCel("a.ru:99999/"))
        assertNull(HonestProbe.razobratCel("a.ru/"))
    }

    @Test
    fun `цель команды proba — только простой HTTP по имени`() {
        assertEquals(HonestProbe.Target("ya.ru", 80, "/"), PultIspolnitel.razobratCel("ya.ru"))
        assertEquals(HonestProbe.Target("ya.ru", 8080, "/x"), PultIspolnitel.razobratCel("http://ya.ru:8080/x"))
        assertNull(PultIspolnitel.razobratCel("https://ya.ru/"))
        assertNull(PultIspolnitel.razobratCel("ya.ru; rm -rf /"))
        assertNull(PultIspolnitel.razobratCel("ya.ru:70000"))
    }

    @Test
    fun `константы подсистем читаются через пульт, без пульта — из кода`() {
        assertEquals(5_000L, Pult.long("komnata.storozh.check_interval_millis", 5_000L))
        Pult.dlyaTesta(PultSostoyanie(nastroyki = mapOf("komnata.storozh.check_interval_millis" to 9_000)))
        assertEquals(9_000L, Pult.long("komnata.storozh.check_interval_millis", 5_000L))
        assertNotNull(PultReestr.klyuch("komnata.storozh.check_interval_millis"))
        assertEquals(5_000, PultReestr.klyuch("komnata.storozh.check_interval_millis")!!.umolch)
    }
}
