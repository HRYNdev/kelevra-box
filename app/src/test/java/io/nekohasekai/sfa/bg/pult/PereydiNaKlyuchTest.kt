package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.Priglashenie
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `pereydi_na_klyuch` на подменённой сети: ни одной боевой двери — вход с выдуманным
 * устройством завёл бы человеку лишнее устройство.
 */
class PereydiNaKlyuchTest {
    private val kod = "K7QM2XRT9PLA"
    private val profilK = "https://node2kv.example.org/k/$kod"
    private val profilU = "https://node2kv.example.org/u/klyuch-ustroystva"

    private class Podmena(
        val otvety: Map<String, Priglashenie.Otvet?>,
        val znaet: Set<String> = emptySet(),
        val konfig: () -> String = { """{"outbounds":[]}""" },
        val godnyy: Boolean = true,
        val spravochnik: List<String> = listOf("subkv.example.org", "node2kv.example.org"),
    ) : PereydiNaKlyuch.Okruzhenie {
        val sprosheno = mutableListOf<String>()
        val skachano = mutableListOf<String>()
        var perepisano: Pair<String, String>? = null

        override suspend fun dveri() = spravochnik
        override suspend fun aktivirovat(dver: String, kod: String): Priglashenie.Otvet? {
            sprosheno += "$dver/$kod"
            return otvety[dver]
        }
        override suspend fun znaetKod(dver: String, kod: String) = dver in znaet
        override suspend fun skachatKonfig(url: String): String {
            skachano += url
            return konfig()
        }
        override fun proveritKonfig(konfig: String) {
            if (!godnyy) throw IllegalStateException("конфиг не принят ядром")
        }
        override suspend fun perepisat(novyyUrl: String, konfig: String) {
            perepisano = novyyUrl to konfig
        }
    }

    @Test
    fun `200 и рабочий конфиг — адрес переписан в том же профиле`() = runBlocking {
        val p = Podmena(mapOf("node2kv.example.org" to Priglashenie.Otvet.Ok(profilU, "s3")))
        assertEquals("ok", PereydiNaKlyuch.vypolnit(profilK, p))
        // Первой спрошена дверь, выдавшая профиль, и ровно один раз.
        assertEquals(listOf("node2kv.example.org/$kod"), p.sprosheno)
        assertEquals(listOf(profilU), p.skachano)
        assertEquals(profilU, p.perepisano!!.first)
    }

    @Test
    fun `конфиг по новому адресу не принят ядром — профиль как был`() = runBlocking {
        val p = Podmena(mapOf("node2kv.example.org" to Priglashenie.Otvet.Ok(profilU, null)), godnyy = false)
        assertEquals("oshibka-konfig_negoden", PereydiNaKlyuch.vypolnit(profilK, p))
        assertNull(p.perepisano)
    }

    @Test
    fun `конфиг не скачался — профиль как был`() = runBlocking {
        val p = Podmena(
            mapOf("node2kv.example.org" to Priglashenie.Otvet.Ok(profilU, null)),
            konfig = { throw java.io.IOException("timeout") },
        )
        assertEquals("oshibka-konfig_ne_skachan", PereydiNaKlyuch.vypolnit(profilK, p))
        assertNull(p.perepisano)
    }

    @Test
    fun `лимит устройств, неизвестный код, нет связи, старая дверь — отказ без правок`() = runBlocking {
        val limit = Podmena(mapOf("node2kv.example.org" to Priglashenie.Otvet.Limit("Достигнут лимит устройств")))
        assertEquals("oshibka-limit", PereydiNaKlyuch.vypolnit(profilK, limit))
        assertNull(limit.perepisano)

        val neNayden = Podmena(mapOf("node2kv.example.org" to Priglashenie.Otvet.NetProtokola, "subkv.example.org" to Priglashenie.Otvet.NetProtokola))
        assertEquals("oshibka-kod_ne_nayden", PereydiNaKlyuch.vypolnit(profilK, neNayden))

        val netSvyazi = Podmena(emptyMap())
        assertEquals("oshibka-net_svyazi", PereydiNaKlyuch.vypolnit(profilK, netSvyazi))

        val staraya = Podmena(mapOf("node2kv.example.org" to Priglashenie.Otvet.NetProtokola), znaet = setOf("node2kv.example.org"))
        assertEquals("oshibka-dver_bez_klyuchey", PereydiNaKlyuch.vypolnit(profilK, staraya))
        assertNull(staraya.perepisano)
    }

    @Test
    fun `первая дверь молчит — идём по справочнику`() = runBlocking {
        val p = Podmena(mapOf("subkv.example.org" to Priglashenie.Otvet.Ok("https://subkv.example.org/u/k2", null)))
        assertEquals("ok", PereydiNaKlyuch.vypolnit(profilK, p))
        assertEquals(listOf("node2kv.example.org/$kod", "subkv.example.org/$kod"), p.sprosheno)
        assertEquals("https://subkv.example.org/u/k2", p.perepisano!!.first)
    }

    @Test
    fun `профиль уже на ключе или чужой — двери не трогаются`() = runBlocking {
        val p = Podmena(emptyMap())
        assertEquals("ok", PereydiNaKlyuch.vypolnit(profilU, p))
        assertEquals("oshibka-chuzhoy_profil", PereydiNaKlyuch.vypolnit("https://example.com/sub.json", p))
        assertEquals("oshibka-net_profilya", PereydiNaKlyuch.vypolnit(null, p))
        assertEquals(emptyList<String>(), p.sprosheno)
    }
}
