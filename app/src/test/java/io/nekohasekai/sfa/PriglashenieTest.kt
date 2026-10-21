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
        zaprosy: MutableList<String> = mutableListOf(),
    ): Itog = runBlocking {
        Priglashenie.vybratDver(
            dveri,
            aktivaciya = { dver, _ -> oprosheno += dver; zaprosy += "POST $dver"; otvety[dver] },
            znaetKod = { zaprosy += "GET $it"; it in znayut },
        )
    }

    // Переехавший человек: старая дверь входа устройства не знает (404), но старый путь пересылает в новую
    // сеть и «знает» код; вторая дверь (новая сеть) заводит устройство. Берётся ключ, старым путём не ходим.
    @Test
    fun vybor_pereehavshij_poluchaet_klyuch() {
        val zaprosy = mutableListOf<String>()
        val itog = vybor(
            listOf("staraya", "novaya"),
            mapOf("staraya" to Otvet.NetProtokola, "novaya" to Otvet.Ok("https://novaya/u/KLYUCH01", "01-3")),
            znayut = setOf("staraya"),
            zaprosy = zaprosy,
        )
        assertEquals(Itog.Novyj("novaya", "https://novaya/u/KLYUCH01"), itog)
        assertEquals(listOf("POST staraya", "POST novaya"), zaprosy)
    }

    // Ни одна дверь устройство не завела: старый путь — только после входа устройства у всех дверей,
    // по порядку, до первой знающей код.
    @Test
    fun vybor_staryj_put_tolko_posle_vseh_dverej() {
        val zaprosy = mutableListOf<String>()
        val itog = vybor(
            listOf("a", "b", "c"),
            mapOf("a" to Otvet.NetProtokola, "b" to null, "c" to Otvet.NetProtokola),
            znayut = setOf("a", "c"),
            zaprosy = zaprosy,
        )
        assertEquals(Itog.Staryj("a"), itog)
        assertEquals(listOf("POST a", "POST b", "POST c", "GET a"), zaprosy)
    }

    // 200 без адреса https://…/u/<ключ> — не вход: дверь пропускается, адрес не сохраняется.
    @Test
    fun vybor_200_bez_klyucha_ne_vhod() {
        val plohie = listOf(
            "{}",
            """{"profil":""}""",
            """{"profil":"https://n.example/k/OBSHCHIYKOD1"}""",
            """{"profil":"http://n.example/u/KLYUCH01"}""",
            """{"profil":"ftp://n.example/u/KLYUCH01"}""",
            """{"profil":"https://n.example/u/KL/../x"}""",
            """{"profil":"https://n.example/u/KLYUCH01/info"}""",
            """{"profil":"https://n.example/u/ab"}""",
            """{"profil":"https://n.example/s/KLYUCH01"}""",
        )
        for (telo in plohie) {
            val o = Priglashenie.razobratOtvet(200, telo)
            assertEquals(telo, Otvet.Sboy, o)
            assertEquals(telo, Itog.NetSvyazi, vybor(listOf("a"), mapOf("a" to o)))
            assertEquals(
                telo,
                Itog.Novyj("b", "https://b.example/u/KLYUCH02"),
                vybor(listOf("a", "b"), mapOf("a" to o, "b" to Otvet.Ok("https://b.example/u/KLYUCH02", null))),
            )
        }
        assertEquals(
            Otvet.Ok("https://n.example:8443/u/Kl_yu-ch01", null),
            Priglashenie.razobratOtvet(200, """{"profil":"https://n.example:8443/u/Kl_yu-ch01/"}"""),
        )
    }

    // Адрес устройства приводится к одному виду; отвергается только не https и путь не /u/<ключ>.
    @Test
    fun adres_klyucha_privoditsya_k_odnomu_vidu() {
        val horoshie = mapOf(
            "HTTPS://H.Example.COM/u/KEY123" to "https://h.example.com/u/KEY123",
            "https://[2001:DB8::1]:8443/u/KEY123" to "https://[2001:db8::1]:8443/u/KEY123",
            "https://[2001:db8::1]/u/KEY123/" to "https://[2001:db8::1]/u/KEY123",
            "https://пример.рф/u/KEY123" to "https://xn--e1afmkfd.xn--p1ai/u/KEY123",
            "https://Пример.РФ:443/u/KEY123" to "https://xn--e1afmkfd.xn--p1ai:443/u/KEY123",
            "https://10.0.0.5:8443/u/KEY123?x=1#y" to "https://10.0.0.5:8443/u/KEY123",
            "  https://h.example/u/AbC_123-xyz0/  " to "https://h.example/u/AbC_123-xyz0",
        )
        for ((vhod, ozhid) in horoshie) assertEquals(vhod, ozhid, Priglashenie.adresKlyucha(vhod))
        val plohie = listOf(
            "", "h.example/u/KEY123", "http://h.example/u/KEY123", "HTTP://h.example/u/KEY123",
            "ftp://h.example/u/KEY123", "https://h.example/k/KEY123", "https://h.example/u/KEY123/info",
            "https://h.example/u/KE/../y", "https://h.example/u/", "https://h.example", "https:///u/KEY123",
            "https://user@h.example/u/KEY123", "https://h.example:port/u/KEY123", "https://[zz::1]/u/KEY123",
        )
        for (vhod in plohie) assertNull(vhod, Priglashenie.adresKlyucha(vhod))
        assertEquals(
            Otvet.Ok("https://xn--e1afmkfd.xn--p1ai/u/KEY123", null),
            Priglashenie.razobratOtvet(200, """{"profil":"HTTPS://пример.рф/u/KEY123"}"""),
        )
    }

    // Новая дверь висит на соединении (режут по дороге): вход человека старой системы не ждёт её
    // полный срок — первый проход ограничен, и сразу идёт старый путь.
    @Test
    fun visyashchaya_dver_ne_derzhit_vhod() {
        val zaprosy = mutableListOf<String>()
        val nachalo = System.nanoTime()
        val itog = runBlocking {
            Priglashenie.vybratDver(
                listOf("staraya", "novaya"),
                aktivaciya = { dver, _ ->
                    zaprosy += "POST $dver"
                    if (dver == "novaya") kotlinx.coroutines.delay(60_000)
                    Otvet.NetProtokola
                },
                znaetKod = { zaprosy += "GET $it"; it == "staraya" },
                srokPervogoProhodaMs = 300,
            )
        }
        val proshlo = (System.nanoTime() - nachalo) / 1_000_000
        assertEquals(Itog.Staryj("staraya"), itog)
        assertEquals(listOf("POST staraya", "POST novaya", "GET staraya"), zaprosy)
        assertTrue("вход занял $proshlo мс", proshlo < 3_000)
    }

    // Второй проход: ответившие 404/405, затем не спрошенные за срок, последними не ответившие.
    // Срок, который получает дверь, — остаток первого прохода.
    @Test
    fun poryadok_vtorogo_prohoda_i_ostatok_sroka() {
        val zaprosy = mutableListOf<String>()
        val sroki = mutableListOf<Long>()
        val itog = runBlocking {
            Priglashenie.vybratDver(
                listOf("x", "visit", "b", "c"),
                aktivaciya = { dver, srokMs ->
                    zaprosy += "POST $dver"
                    sroki += srokMs
                    if (dver == "visit") kotlinx.coroutines.delay(60_000)
                    Otvet.NetProtokola
                },
                znaetKod = { zaprosy += "GET $it"; false },
                srokPervogoProhodaMs = 300,
            )
        }
        assertEquals(Itog.NeNajden, itog)
        assertEquals(listOf("POST x", "POST visit", "GET x", "GET b", "GET c", "GET visit"), zaprosy)
        assertTrue(sroki.toString(), sroki.all { it in 1..300 })
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

    // ---- имя профиля ----

    @Test
    fun pervyj_profil_nazyvaetsya_kak_ranshe() {
        assertEquals("Kelevra", Priglashenie.imyaProfilya("https://$subkv/u/klyuch", emptyList(), "18.10"))
        assertEquals("Kelevra", Priglashenie.imyaProfilya("https://$subkv/u/klyuch", listOf("Мой профиль"), "18.10"))
    }

    @Test
    fun vtoroj_profil_otlichaetsya_serverom() {
        assertEquals(
            "Kelevra · node2kv",
            Priglashenie.imyaProfilya("https://node2kv.example.com/u/klyuch", listOf("Kelevra"), "18.10"),
        )
        assertEquals(
            "Kelevra · subkv",
            Priglashenie.imyaProfilya("https://$subkv/k/KOD", listOf("kelevra "), "18.10"),
        )
    }

    @Test
    fun tot_zhe_server_dobavlyaet_datu_potom_nomer() {
        val url = "https://node2kv.example.com/u/klyuch"
        assertEquals(
            "Kelevra · node2kv · 18.10",
            Priglashenie.imyaProfilya(url, listOf("Kelevra", "Kelevra · node2kv"), "18.10"),
        )
        assertEquals(
            "Kelevra · node2kv · 18.10 (3)",
            Priglashenie.imyaProfilya(
                url,
                listOf("Kelevra", "Kelevra · node2kv", "Kelevra · node2kv · 18.10", "Kelevra · node2kv · 18.10 (2)"),
                "18.10",
            ),
        )
    }

    @Test
    fun korotkoe_imya_servera_iz_lyubogo_adresa() {
        assertEquals("subkv", Priglashenie.korotkoeImyaServera("https://$subkv/u/klyuch"))
        assertEquals("node2kv", Priglashenie.korotkoeImyaServera("https://node2kv.example.com:8443/k/KOD"))
        assertEquals("panel", Priglashenie.korotkoeImyaServera("https://panel.example.org/sub/abc?x=1"))
        assertEquals("10.0.0.5", Priglashenie.korotkoeImyaServera("http://10.0.0.5:2096/sub/abc"))
        assertEquals("", Priglashenie.korotkoeImyaServera("ne-adres"))
        // Без сервера в адресе имя всё равно отличается: дата и номер.
        assertEquals("Kelevra · 18.10", Priglashenie.imyaProfilya("ne-adres", listOf("Kelevra"), "18.10"))
    }
}
