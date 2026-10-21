package io.nekohasekai.sfa.bg.pult

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приём пульта: подпись, адресат, версия, границы, испытание и откат, команды, заголовок. */
class PultYadroTest {
    private val k = PultTestKlyuch()
    private val klyuchi = listOf(k.otkrytyy)
    private val t0 = 1_800_000_000_000L

    private fun prinyat(s: PultSostoyanie, d: JSONObject, now: Long = t0, blok: JSONObject = k.blok(d)) =
        PultYadro.prinyat(s, blok, USTROYSTVO, now, klyuchi)

    // ---- подпись, адресат, версия ----

    @Test
    fun `верная подпись принимается, версия и настройки встают`() {
        val itog = prinyat(PultSostoyanie(), dannye(42, JSONObject().put("opros.info_min", 15)))
        assertEquals(42L, itog.sostoyanie.versiya)
        assertEquals(15, itog.sostoyanie.nastroyki["opros.info_min"])
        assertTrue(itog.nastroykiIzmenilis)
        assertNotNull(itog.sostoyanie.ispytanie)
        assertEquals("v=1;umeet=1;versiya=42", PultYadro.zagolovok(itog.sostoyanie))
    }

    @Test
    fun `чужая подпись — отказ, версия прежняя, причина в заголовке`() {
        val chuzhoy = PultTestKlyuch()
        val s0 = PultSostoyanie(versiya = 7)
        val itog = prinyat(s0, dannye(42), blok = chuzhoy.blok(dannye(42)))
        assertEquals(7L, itog.sostoyanie.versiya)
        assertEquals("v=1;umeet=1;versiya=7;oshibki=pult:podpis", PultYadro.zagolovok(itog.sostoyanie))
    }

    @Test
    fun `подменённые данные под старой подписью — отказ`() {
        val blok = k.blok(dannye(42))
        blok.put("dannye", PultBase64.zakodirovat(dannye(43).toString().toByteArray()))
        assertEquals("podpis", prinyat(PultSostoyanie(), dannye(42), blok = blok).sostoyanie.oshibkaPulta)
    }

    @Test
    fun `боевые ключи не принимают пробную подпись`() {
        val itog = PultYadro.prinyat(PultSostoyanie(), k.blok(dannye(1)), USTROYSTVO, t0)
        assertEquals("podpis", itog.sostoyanie.oshibkaPulta)
    }

    @Test
    fun `чужой адресат и не та версия протокола — отказ`() {
        assertEquals("komu", prinyat(PultSostoyanie(), dannye(1, komu = "kelevra-chuzhoe")).sostoyanie.oshibkaPulta)
        assertEquals("v", prinyat(PultSostoyanie(), dannye(1, v = 2)).sostoyanie.oshibkaPulta)
    }

    @Test
    fun `версия ниже сохранённой — отказ, равная и выше — приём`() {
        val s = PultSostoyanie(versiya = 10)
        assertEquals("versiya", prinyat(s, dannye(9)).sostoyanie.oshibkaPulta)
        assertNull(prinyat(s, dannye(10)).sostoyanie.oshibkaPulta)
        assertEquals(11L, prinyat(s, dannye(11)).sostoyanie.versiya)
    }

    @Test
    fun `нет блока — не меняется ничего`() {
        val s = PultSostoyanie(versiya = 5, nastroyki = mapOf("opros.info_min" to 30))
        assertEquals(s, PultYadro.prinyat(s, null, USTROYSTVO, t0, klyuchi).sostoyanie)
    }

    @Test
    fun `отказ снимается следующим верным блоком`() {
        val plohoy = prinyat(PultSostoyanie(), dannye(1, komu = "x")).sostoyanie
        assertNull(prinyat(plohoy, dannye(2)).sostoyanie.oshibkaPulta)
    }

    // ---- границы и типы ----

    @Test
    fun `за границами, незнакомый ключ и не тот тип — в ошибки, остальное принимается`() {
        val n = JSONObject()
            .put("opros.info_min", 3) // меньше 5
            .put("uzel.porog_otkazov", 21) // больше 20
            .put("avtomat.podtverzhdeniy", "3") // строка вместо числа
            .put("funkcii.komnata", 1) // число вместо bool
            .put("neizvestnyy.klyuch", 1)
            .put("zhurnal.otpravka_min", 30)
            .put("zhurnal.chasto_do", "2026-10-22T12:00:00Z")
            .put("obnovleniya.kanal", "proba")
        val s = prinyat(PultSostoyanie(), dannye(1, n)).sostoyanie
        assertEquals(
            listOf("avtomat.podtverzhdeniy", "funkcii.komnata", "neizvestnyy.klyuch", "opros.info_min", "uzel.porog_otkazov"),
            s.oshibkiKlyuchey,
        )
        assertEquals(
            mapOf("obnovleniya.kanal" to "proba", "zhurnal.chasto_do" to "2026-10-22T12:00:00Z", "zhurnal.otpravka_min" to 30),
            s.nastroyki,
        )
        assertTrue(
            PultYadro.zagolovok(s).endsWith(
                "oshibki=avtomat.podtverzhdeniy,funkcii.komnata,neizvestnyy.klyuch,opros.info_min,uzel.porog_otkazov",
            ),
        )
    }

    @Test
    fun `границы включительно, дробное и неизвестный вариант отвергаются`() {
        assertEquals(5, PultReestr.proverit("opros.info_min", 5))
        assertEquals(240, PultReestr.proverit("opros.info_min", 240))
        assertNull(PultReestr.proverit("opros.info_min", 241))
        assertNull(PultReestr.proverit("opros.info_min", 15.5))
        assertNull(PultReestr.proverit("obnovleniya.kanal", "beta"))
        assertNull(PultReestr.proverit("zhurnal.chasto_do", "завтра"))
        assertEquals(false, PultReestr.proverit("funkcii.avtorezhim", false))
    }

    // ---- испытание и откат ----

    private fun naIspytanii(): PultSostoyanie {
        var s = prinyat(PultSostoyanie(), dannye(1, JSONObject().put("opros.info_min", 30))).sostoyanie
        for (i in 1..12) s = PultYadro.otmetit(s, t0 + i * 60_000L, nablyudaem = true, zhivoy = true).sostoyanie
        assertNull(s.ispytanie)
        return prinyat(s, dannye(2, JSONObject().put("opros.info_min", 5)), now = t0 + 20 * 60_000L).sostoyanie
    }

    @Test
    fun `канал ожил за испытание — настройки приняты`() {
        var s = naIspytanii()
        val t = t0 + 20 * 60_000L
        for (i in 1..9) s = PultYadro.otmetit(s, t + i * 60_000L, nablyudaem = true, zhivoy = i == 3).sostoyanie
        assertNotNull(s.ispytanie)
        s = PultYadro.otmetit(s, t + 10 * 60_000L, nablyudaem = true, zhivoy = false).sostoyanie
        assertNull(s.ispytanie)
        assertEquals(5, s.nastroyki["opros.info_min"])
        assertFalse(s.otkat)
    }

    @Test
    fun `канал ни разу не ожил при сети — откат на прошлые, версия плохая, pult otkat`() {
        var s = naIspytanii()
        val t = t0 + 20 * 60_000L
        var itog: PultYadro.Itog? = null
        for (i in 1..10) {
            itog = PultYadro.otmetit(s, t + i * 60_000L, nablyudaem = true, zhivoy = false)
            s = itog.sostoyanie
        }
        assertTrue(itog!!.nastroykiIzmenilis)
        assertEquals(30, s.nastroyki["opros.info_min"])
        assertTrue(2L in s.plohieVersii)
        assertTrue(PultYadro.zagolovok(s).contains("oshibki=pult:otkat"))
        // Те же настройки под новой версией (сервер добавил команду) снова не применяются.
        val s3 = prinyat(s, dannye(3, JSONObject().put("opros.info_min", 5)), now = t + 11 * 60_000L).sostoyanie
        assertEquals(30, s3.nastroyki["opros.info_min"])
        assertNull(s3.ispytanie)
        // А другие — применяются, и откат из заголовка уходит.
        val s4 = prinyat(s3, dannye(4, JSONObject().put("opros.info_min", 20)), now = t + 12 * 60_000L).sostoyanie
        assertEquals(20, s4.nastroyki["opros.info_min"])
        assertFalse(PultYadro.zagolovok(s4).contains("otkat"))
    }

    @Test
    fun `откат первого пульта возвращает значения из кода`() {
        var s = prinyat(PultSostoyanie(), dannye(1, JSONObject().put("funkcii.avtorezhim", false))).sostoyanie
        for (i in 1..10) s = PultYadro.otmetit(s, t0 + i * 60_000L, nablyudaem = true, zhivoy = false).sostoyanie
        assertTrue(s.nastroyki.isEmpty())
        assertTrue(s.otkat)
    }

    @Test
    fun `без сети испытание не судит, сон телефона не засчитывается`() {
        var s = naIspytanii()
        val t = t0 + 20 * 60_000L
        for (i in 1..30) s = PultYadro.otmetit(s, t + i * 60_000L, nablyudaem = false, zhivoy = false).sostoyanie
        assertNotNull(s.ispytanie)
        // Час без отметок (телефон спал) — шаг засчитывается не больше двух минут.
        s = PultYadro.otmetit(s, t + 90 * 60_000L, nablyudaem = true, zhivoy = false).sostoyanie
        assertNotNull(s.ispytanie)
        assertEquals(PultYadro.SHAG_MAX_MS, s.ispytanie!!.nablyudenoMs)
    }

    @Test
    fun `три падения подряд на испытании — откат`() {
        var s = naIspytanii()
        val t = t0 + 20 * 60_000L
        s = PultYadro.naStarte(s, t + 60_000L).sostoyanie
        s = PultYadro.naStarte(s, t + 70_000L).sostoyanie
        assertNotNull(s.ispytanie)
        val itog = PultYadro.naStarte(s, t + 80_000L)
        assertNull(itog.sostoyanie.ispytanie)
        assertEquals(30, itog.sostoyanie.nastroyki["opros.info_min"])
        assertTrue(itog.sostoyanie.otkat)
    }

    @Test
    fun `перезапуски после долгой жизни процесса — не падения`() {
        var s = naIspytanii()
        val t = t0 + 20 * 60_000L
        repeat(5) { i ->
            s = PultYadro.naStarte(s, t + i * 60_000L).sostoyanie
            s = PultYadro.prozhilBezPadeniya(s)
        }
        assertNotNull(s.ispytanie)
        assertEquals(0, s.ispytanie!!.startov)
    }

    @Test
    fun `те же настройки новой версией испытания не начинают`() {
        var s = prinyat(PultSostoyanie(), dannye(1, JSONObject().put("opros.info_min", 30))).sostoyanie
        for (i in 1..12) s = PultYadro.otmetit(s, t0 + i * 60_000L, nablyudaem = true, zhivoy = true).sostoyanie
        val itog = prinyat(s, dannye(2, JSONObject().put("opros.info_min", 30)))
        assertFalse(itog.nastroykiIzmenilis)
        assertNull(itog.sostoyanie.ispytanie)
        assertEquals(2L, itog.sostoyanie.versiya)
    }

    // ---- команды и квитанции ----

    @Test
    fun `команда выполняется один раз, квитанция держится, пока сервер её шлёт`() {
        val kom = JSONArray().put(komanda("k17", "prishli_zhurnal"))
        val i1 = prinyat(PultSostoyanie(), dannye(1, komandy = kom))
        assertEquals(listOf("k17"), i1.zadaniya.map { it.id })
        // Пока выполняется — в заголовке её нет.
        assertFalse(PultYadro.zagolovok(i1.sostoyanie).contains("kvit"))
        val s1 = PultYadro.zavershit(i1.sostoyanie, "k17", "ok", t0 + 1000)
        assertEquals("v=1;umeet=1;versiya=1;kvit=k17:ok", PultYadro.zagolovok(s1))
        // Повтор той же id — не выполняется.
        val i2 = prinyat(s1, dannye(2, komandy = kom), now = t0 + 60_000)
        assertTrue(i2.zadaniya.isEmpty())
        assertTrue(PultYadro.zagolovok(i2.sostoyanie).contains("kvit=k17:ok"))
        // Сервер перестал слать — квитанция ушла из заголовка, но id помнится.
        val i3 = prinyat(i2.sostoyanie, dannye(3), now = t0 + 120_000)
        assertFalse(PultYadro.zagolovok(i3.sostoyanie).contains("kvit"))
        assertTrue(prinyat(i3.sostoyanie, dannye(4, komandy = kom), now = t0 + 180_000).zadaniya.isEmpty())
        // Через 7 суток память чистится.
        val pozzhe = t0 + PultYadro.PAMYAT_KOMAND_MS + 10_000
        val i5 = prinyat(i3.sostoyanie, dannye(5), now = pozzhe)
        assertFalse(i5.sostoyanie.komandy.containsKey("k17"))
    }

    @Test
    fun `истёкшая, неизвестная и с битым сроком — без выполнения, с квитанцией`() {
        val kom = JSONArray()
            .put(komanda("a1", "prishli_zhurnal", srok = "2020-01-01T00:00:00Z"))
            .put(komanda("a2", "sotri_vse"))
            .put(komanda("a3", "obnovi_profil", srok = "когда-нибудь"))
            .put(komanda("a4", "sbros_avtomata", srok = null))
        val itog = prinyat(PultSostoyanie(), dannye(1, komandy = kom))
        assertEquals(listOf("a4"), itog.zadaniya.map { it.id })
        val z = PultYadro.zagolovok(itog.sostoyanie)
        assertTrue(z, z.contains("a1:istekla"))
        assertTrue(z, z.contains("a2:ne_umeyu"))
        assertTrue(z, z.contains("a3:oshibka-srok"))
    }

    @Test
    fun `все команды v1 и диагностика известны клиенту`() {
        val kom = JSONArray()
        listOf("prishli_zhurnal", "obnovi_profil", "pereydi_na_klyuch", "sbros_avtomata", "proverit_obnovlenie", "diagnostika", "proba")
            .forEachIndexed { i, chto -> kom.put(komanda("c$i", chto)) }
        assertEquals(7, prinyat(PultSostoyanie(), dannye(1, komandy = kom)).zadaniya.size)
    }

    @Test
    fun `команда, прерванная падением, после старта получает ошибку`() {
        val itog = prinyat(PultSostoyanie(), dannye(1, komandy = JSONArray().put(komanda("x", "diagnostika"))))
        val s = PultYadro.naStarte(itog.sostoyanie, t0 + 5000).sostoyanie
        assertEquals("oshibka-prervano", s.komandy["x"]!!.itog)
    }

    @Test
    fun `команды из отвергнутого блока не выполняются`() {
        val kom = JSONArray().put(komanda("k1", "prishli_zhurnal"))
        assertTrue(prinyat(PultSostoyanie(), dannye(1, komandy = kom, komu = "x")).zadaniya.isEmpty())
    }

    @Test
    fun `заголовок только ASCII и без разделителей в id`() {
        val kom = JSONArray().put(komanda("к;1,2", "neznakomaya"))
        val z = PultYadro.zagolovok(prinyat(PultSostoyanie(), dannye(1, komandy = kom)).sostoyanie)
        assertTrue(z, z.all { it.code in 32..126 })
        assertTrue(z, Regex(""".*;kvit=[A-Za-z0-9_.-]+:ne_umeyu$""").matches(z))
    }

    // ---- диск ----

    @Test
    fun `состояние переживает запись и чтение`() {
        var s = naIspytanii()
        s = prinyat(
            s,
            dannye(
                3,
                JSONObject().put("opros.info_min", 5).put("zhurnal.chasto_do", "2026-10-22T12:00:00Z"),
                JSONArray().put(komanda("k9", "prishli_zhurnal")),
                obnovlenie = JSONObject().put("versiya_kod", 150).put("url", "https://example.org/a.apk")
                    .put("sha256", "a".repeat(64)).put("razmer", 10),
            ),
            now = t0 + 21 * 60_000L,
        ).sostoyanie
        s = PultYadro.zavershit(s, "k9", "ok", t0 + 21 * 60_000L)
        val nazad = PultSostoyanie.izJson(JSONObject(s.vJson().toString()))
        assertEquals(s, nazad)
        assertEquals(150, nazad.obnovlenie!!.versiyaKod)
    }

    @Test
    fun `X-Pult с kod — versionCode установленного, блок obnovlenie с versiya_imya`() {
        assertEquals("v=1;umeet=1;versiya=5;kod=711147", PultYadro.zagolovok(PultSostoyanie(versiya = 5), kod = 711147))
        assertEquals("v=1;umeet=1;versiya=5", PultYadro.zagolovok(PultSostoyanie(versiya = 5)))
        val o = PultYadro.Obnovlenie.izJson(
            JSONObject().put("versiya_kod", 711147).put("versiya_imya", "1.14.147").put("url", "https://example.org/a.apk")
                .put("sha256", "b".repeat(64)).put("razmer", 10),
        )!!
        assertEquals(711147, o.versiyaKod)
        assertEquals("1.14.147", o.versiyaImya)
        assertEquals(o, PultYadro.Obnovlenie.izJson(o.vJson()))
        assertEquals("1.14.147", io.nekohasekai.sfa.update.ObnovleniyaPult.vUpdateInfo(o).versionName)
        assertEquals(711147, io.nekohasekai.sfa.update.ObnovleniyaPult.vUpdateInfo(o).versionCode)
    }
}
