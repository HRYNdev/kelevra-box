package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.SubscriptionLink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ubrat_lishnie_profili` на подменённой базе профилей: ни одной боевой записи.
 */
class UbratLishnieProfiliTest {
    private val profilK = "https://node2kv.example.org/k/K7QM2XRT9PLA"
    private val profilU = "https://node2kv.example.org/u/klyuch-ustroystva"
    private val profilKChuzhoy = "https://node2kv.example.org/k/DRUGOY1234AB"

    private class Podmena(spisok: List<UbratLishnieProfili.Profil>) : UbratLishnieProfili.Okruzhenie {
        val profili = spisok.toMutableList()
        val udaleny = mutableListOf<Long>()

        override suspend fun profili(): List<UbratLishnieProfili.Profil> = profili
        override suspend fun udalit(id: Long) {
            udaleny += id
            profili.removeAll { it.id == id }
        }
    }

    @Test
    fun `выбран ключ устройства, рядом неактивный дубль по коду — дубль удалён`() = runBlocking {
        val p = Podmena(
            listOf(
                UbratLishnieProfili.Profil(1, remote = true, url = profilU),
                UbratLishnieProfili.Profil(2, remote = true, url = profilK),
            ),
        )
        assertEquals("ok-1", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(listOf(2L), p.udaleny)
        assertTrue(p.profili.any { it.id == 1L }) // выбранный не тронут
    }

    @Test
    fun `выбран профиль по коду, рядом профиль на ключе — ничего не удалено, ошибка`() = runBlocking {
        val p = Podmena(
            listOf(
                UbratLishnieProfili.Profil(1, remote = true, url = profilK),
                UbratLishnieProfili.Profil(2, remote = true, url = profilU),
            ),
        )
        assertEquals("oshibka-vybran_ne_klyuch", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(emptyList<Long>(), p.udaleny)
    }

    @Test
    fun `чужая подписка с путём k на другом хосте не трогается, наш основной хост — да`() = runBlocking {
        val p = Podmena(
            listOf(
                UbratLishnieProfili.Profil(1, remote = true, url = profilU),
                UbratLishnieProfili.Profil(2, remote = true, url = "https://drugoy-vpn.example.net/k/ABCDEF123456"), // чужой хост
                UbratLishnieProfili.Profil(3, remote = true, url = "https://${SubscriptionLink.DEFAULT_HOST}/k/OSNOVNOY1234"), // наш основной
            ),
        )
        assertEquals("ok-1", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(listOf(3L), p.udaleny)
    }

    @Test
    fun `локальный профиль и чужой URL не трогаются`() = runBlocking {
        val p = Podmena(
            listOf(
                UbratLishnieProfili.Profil(1, remote = true, url = profilU),
                UbratLishnieProfili.Profil(2, remote = false, url = null), // локальный
                UbratLishnieProfili.Profil(3, remote = true, url = "https://example.com/sub.json"), // чужой
                UbratLishnieProfili.Profil(4, remote = true, url = profilKChuzhoy), // настоящий дубль
            ),
        )
        assertEquals("ok-1", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(listOf(4L), p.udaleny)
    }

    @Test
    fun `выбранный профиль на ключе сам не удаляется, даже если он один`() = runBlocking {
        val p = Podmena(listOf(UbratLishnieProfili.Profil(1, remote = true, url = profilU)))
        assertEquals("ok-0", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(emptyList<Long>(), p.udaleny)
    }

    @Test
    fun `выбранного id нет в списке — ошибка, ничего не удалено`() = runBlocking {
        val p = Podmena(listOf(UbratLishnieProfili.Profil(2, remote = true, url = profilK)))
        assertEquals("oshibka-vybran_ne_klyuch", UbratLishnieProfili.vypolnit(1, p))
        assertEquals(emptyList<Long>(), p.udaleny)
    }
}
