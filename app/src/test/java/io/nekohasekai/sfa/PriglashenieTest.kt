package io.nekohasekai.sfa

import io.nekohasekai.sfa.Priglashenie.Itog
import io.nekohasekai.sfa.Priglashenie.Otvet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriglashenieTest {
    private val subkv = "subkv.chickenkiller.com"

    @Test
    fun kod_v_lyubom_registre_s_defisami_i_bez() {
        val ozhidaemo = listOf("K7QM2XRT9PLA")
        assertEquals(ozhidaemo, Priglashenie.varianty("K7QM-2XRT-9PLA").take(1))
        assertEquals(ozhidaemo, Priglashenie.varianty("k7qm-2xrt-9pla").take(1))
        assertEquals(ozhidaemo, Priglashenie.varianty("k7qm 2xrt 9pla").take(1))
        assertEquals(ozhidaemo, Priglashenie.varianty("  K7QM2XRT9PLA ").take(1))
        assertEquals(ozhidaemo, Priglashenie.varianty("K7QM2XRT9PLA"))
    }

    @Test
    fun staryj_kod_ne_menyaetsya() {
        val staryj = "aB3dE-fG_h1JkLmN"
        assertEquals(staryj, Priglashenie.varianty(staryj).first())
        assertEquals("aB3dEfGhXY34", Priglashenie.varianty("aB3dEfGhXY34").last())
        assertEquals("aBcDeFgHiJkLmNoP", Priglashenie.varianty("aBcD eFgH iJkL mNoP").first())
    }

    @Test
    fun ssylki_i_musor_ne_kody() {
        assertTrue(Priglashenie.varianty("https://subkv.chickenkiller.com/k/abc").isEmpty())
        assertTrue(Priglashenie.varianty("   ").isEmpty())
        assertTrue(Priglashenie.varianty("abc/../x").isEmpty())
    }

    @Test
    fun maska_polya() {
        assertEquals("K7QM", Priglashenie.maska("k7qm"))
        assertEquals("K7QM-2X", Priglashenie.maska("k7qm2x"))
        assertEquals("K7QM-2XRT-9PLA", Priglashenie.maska("k7qm2xrt9pla"))
        assertEquals("K7QM-2XRT-9PLA", Priglashenie.maska("K7QM-2XRT-9PLA"))
        assertEquals("K7QM-2XRT-9PLA", Priglashenie.maska("k7qm 2xrt 9pla"))
        // длиннее 12 знаков или с подчёркиванием: старый код, не трогаем
        assertEquals("aBcDeFgHiJkLm", Priglashenie.maska("aBcDeFgHiJkLm"))
        assertEquals("ab_cd", Priglashenie.maska("ab_cd"))
    }

    @Test
    fun kod_iz_priglasheniya() {
        assertEquals("K7QM-2XRT-9PLA", Priglashenie.kodIzSsylki("kelevra://import?kod=K7QM-2XRT-9PLA"))
        assertNull(Priglashenie.kodIzSsylki("kelevra://import?url=x"))
        assertNull(Priglashenie.kodIzSsylki("kelevra://import?kod="))
    }

    @Test
    fun spisok_dverej() {
        val spisok = Priglashenie.spisokDverej(
            subkv,
            Priglashenie.razobratSpravochnik("""{"dveri": ["Host1.example.com", "subkv.chickenkiller.com", "bad host", ""]}"""),
            listOf("host2.example.com:8443", "host1.example.com"),
        )
        assertEquals(listOf(subkv, "host1.example.com", "host2.example.com:8443"), spisok)
        assertTrue(Priglashenie.razobratSpravochnik("не json").isEmpty())
        assertTrue(Priglashenie.razobratSpravochnik("""{"x": 1}""").isEmpty())
    }

    @Test
    fun razbor_otvetov() {
        val ok = Priglashenie.razobratOtvet(200, """{"profil":"https://h.example.com/u/KEY123","slot":"01-2"}""")
        assertEquals(Otvet.Ok("https://h.example.com/u/KEY123", "01-2"), ok)
        assertEquals(Otvet.Sboy, Priglashenie.razobratOtvet(200, """{"profil":"https://h.example.com/k/abc"}"""))
        assertEquals(Otvet.Sboy, Priglashenie.razobratOtvet(200, "<html>"))
        assertEquals(
            Otvet.Limit("Достигнут лимит устройств"),
            Priglashenie.razobratOtvet(403, """{"oshibka":"limit_ustroystv","tekst":"Достигнут лимит устройств"}"""),
        )
        assertEquals(Otvet.Sboy, Priglashenie.razobratOtvet(403, "{}"))
        assertEquals(Otvet.NetProtokola, Priglashenie.razobratOtvet(404, ""))
        assertEquals(Otvet.NetProtokola, Priglashenie.razobratOtvet(405, ""))
        assertEquals(Otvet.Sboy, Priglashenie.razobratOtvet(502, ""))
    }

    private fun vybor(
        dveri: List<String>,
        otvety: Map<String, Otvet?>,
        znayut: Set<String> = emptySet(),
        oprosheno: MutableList<String> = mutableListOf(),
    ): Itog = runBlocking {
        Priglashenie.vybratDver(
            dveri,
            aktivaciya = { oprosheno += it; otvety[it] },
            znaetKod = { it in znayut },
        )
    }

    @Test
    fun vybor_novyj_protokol_na_vtoroj_dveri() {
        val opros = mutableListOf<String>()
        val itog = vybor(
            listOf("a", "b", "c"),
            mapOf("a" to Otvet.NetProtokola, "b" to Otvet.Ok("https://b/u/K", "01-1"), "c" to Otvet.Ok("https://c/u/Z", null)),
            oprosheno = opros,
        )
        assertEquals(Itog.Novyj("b", "https://b/u/K"), itog)
        assertEquals(listOf("a", "b"), opros)
    }

    @Test
    fun vybor_limit_ostanavlivaet_obhod() {
        val opros = mutableListOf<String>()
        val itog = vybor(listOf("a", "b"), mapOf("a" to Otvet.Limit("Достигнут лимит устройств")), oprosheno = opros)
        assertEquals(Itog.Limit("Достигнут лимит устройств"), itog)
        assertEquals(listOf("a"), opros)
    }

    @Test
    fun vybor_staryj_put_esli_dver_znaet_kod() {
        val itog = vybor(listOf("a", "b"), mapOf("a" to Otvet.NetProtokola, "b" to Otvet.NetProtokola), znayut = setOf("a"))
        assertEquals(Itog.Staryj("a"), itog)
    }

    @Test
    fun vybor_ne_najden_i_net_svyazi() {
        assertEquals(Itog.NeNajden, vybor(listOf("a", "b"), mapOf("a" to Otvet.NetProtokola, "b" to Otvet.NetProtokola)))
        // одна дверь недоступна, другая ответила 404: код не найден
        assertEquals(Itog.NeNajden, vybor(listOf("a", "b"), mapOf("a" to null, "b" to Otvet.NetProtokola)))
        assertEquals(Itog.NetSvyazi, vybor(listOf("a", "b"), mapOf("a" to null, "b" to null)))
        assertEquals(Itog.NetSvyazi, vybor(listOf("a"), mapOf("a" to Otvet.Sboy)))
        assertEquals(Itog.NetSvyazi, vybor(emptyList(), emptyMap()))
    }

    @Test
    fun id_ustrojstva_ot_android_id() {
        val id = Priglashenie.idIzAndroidId("9774d56d682e549c")
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals(id, Priglashenie.idIzAndroidId("9774d56d682e549c"))
        assertFalse(id == Priglashenie.idIzAndroidId("0000000000000000"))
    }

    @Test
    fun povtornyj_vvod_nahodit_tot_zhe_profil() {
        // тот же ключ устройства
        assertTrue(Priglashenie.totZhe("https://h/u/KEY", "https://h/u/KEY", listOf("K7QM2XRT9PLA")))
        // сервер выдаёт новый ключ при повторной активации: тот же хост — тот же профиль устройства
        assertTrue(Priglashenie.totZhe("https://h/u/KEY", "https://h/u/OTHER", listOf("K7QM2XRT9PLA")))
        assertFalse(Priglashenie.totZhe("https://h/u/KEY", "https://other/u/KEY2", listOf("K7QM2XRT9PLA")))
        // старый профиль по общему коду заменяется при вводе того же кода
        assertTrue(Priglashenie.totZhe("https://h/u/KEY", "https://$subkv/k/oldCode", listOf("oldCode")))
        assertFalse(Priglashenie.totZhe("https://h/u/KEY", "https://$subkv/k/oldCode", listOf("another")))
        assertFalse(Priglashenie.totZhe("https://h/u/KEY", "https://example.com/sub", listOf("sub")))
    }

    @Test
    fun profil_ustrojstva_raspoznaetsya_kak_svoj() {
        val p = SubscriptionLink.parse("https://h.example.com/u/KEY_1-x/info")!!
        assertEquals('u', p.kind)
        assertEquals("KEY_1-x", p.code)
        assertEquals("https://h.example.com/u/KEY_1-x", SubscriptionLink.normalize("https://h.example.com/u/KEY_1-x/"))
        assertEquals("https://h.example.com/u/KEY_1-x", SubscriptionLink.baseUrl("https://h.example.com/u/KEY_1-x/info"))
        assertEquals("https://$subkv/k/abc", SubscriptionLink.baseUrl("https://$subkv/abc/singbox"))
        assertTrue(SubscriptionLink.isOwn("https://h.example.com/u/KEY"))
        assertFalse(SubscriptionLink.maskCode("GET https://h.example.com/u/ABCDEFGH1234").contains("ABCDEFGH"))
    }

    @Test
    fun uvedomlenie_ob_obnovlenii_ne_chashche_raza_v_sutki() {
        val sutki = 24 * 60 * 60_000L
        assertTrue(io.nekohasekai.sfa.update.IshodUstanovki.mozhnoUvedomit(1_000_000L, 0L))
        assertFalse(io.nekohasekai.sfa.update.IshodUstanovki.mozhnoUvedomit(1_000_000L + sutki - 1, 1_000_000L))
        assertTrue(io.nekohasekai.sfa.update.IshodUstanovki.mozhnoUvedomit(1_000_000L + sutki, 1_000_000L))
    }
}
