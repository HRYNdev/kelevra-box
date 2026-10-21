package io.nekohasekai.sfa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException

/** Запасной путь профиля: прямой → туннель → другие двери. */
class ZapasnoyPutTest {
    private val subkv = "subkv.chickenkiller.com"
    private val node2 = "node2kv.chickenkiller.com"
    private val url = "https://$subkv/k/ABC123"

    @Test
    fun `reset EOF и таймаут - сбой сети`() {
        assertTrue(ZapasnoyPut.sbojSeti(RuntimeException("read tcp 1.2.3.4:5->6.7.8.9:443: read: connection reset by peer")))
        assertTrue(ZapasnoyPut.sbojSeti(RuntimeException("Get \"https://x\": EOF")))
        assertTrue(ZapasnoyPut.sbojSeti(RuntimeException("net/http: request canceled (Client.Timeout exceeded)")))
        assertTrue(ZapasnoyPut.sbojSeti(EOFException()))
        assertTrue(ZapasnoyPut.sbojSeti(RuntimeException("обёртка", java.net.SocketTimeoutException("x"))))
    }

    @Test
    fun `ответ сервера и мусор - не сбой сети`() {
        assertFalse(ZapasnoyPut.sbojSeti(IllegalStateException("HTTP 404")))
        assertFalse(ZapasnoyPut.sbojSeti(RuntimeException("invalid config: unknown field")))
    }

    @Test
    fun `прямой путь прошёл - остальное не трогаем`() {
        val vyzovy = mutableListOf<String>()
        val r = ZapasnoyPut.poluchit(url, listOf(subkv, node2), { u: String -> vyzovy += "t:$u"; "tunnel" }) { u ->
            vyzovy += "p:$u"
            "pryamo"
        }
        assertEquals("pryamo", r)
        assertEquals(listOf("p:$url"), vyzovy)
    }

    @Test
    fun `reset напрямую - повтор через туннель`() {
        val r = ZapasnoyPut.poluchit(url, listOf(subkv, node2), { _: String -> "tunnel" }) { _ ->
            throw RuntimeException("connection reset by peer")
        }
        assertEquals("tunnel", r)
    }

    @Test
    fun `туннеля нет - перебор дверей напрямую`() {
        val vyzovy = mutableListOf<String>()
        val r = ZapasnoyPut.poluchit<String>(url, listOf(subkv, node2), null) { u ->
            vyzovy += u
            if (u.contains(node2)) "ok" else throw EOFException()
        }
        assertEquals("ok", r)
        assertEquals(listOf(url, "https://$node2/k/ABC123"), vyzovy)
    }

    @Test
    fun `дверь сбоит и в туннеле - берём вторую дверь через туннель`() {
        val r = ZapasnoyPut.poluchit(
            url,
            listOf(subkv, node2),
            { u: String -> if (u.contains(node2)) "viaTunnel" else throw RuntimeException("timeout") },
        ) { _ -> throw EOFException() }
        assertEquals("viaTunnel", r)
    }

    @Test
    fun `везде сбой - бросается первая ошибка`() {
        try {
            ZapasnoyPut.poluchit<String>(url, listOf(node2), { _ -> throw EOFException("t") }) { _ ->
                throw RuntimeException("connection reset first")
            }
            fail()
        } catch (e: RuntimeException) {
            assertEquals("connection reset first", e.message)
        }
    }

    @Test
    fun `ответ сервера не ищет запасных путей`() {
        var vyzovov = 0
        try {
            ZapasnoyPut.poluchit(url, listOf(node2), { _: String -> vyzovov++; "x" }) { _ ->
                throw IllegalStateException("HTTP 403")
            }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals(0, vyzovov)
        }
    }

    @Test
    fun `чужой адрес на другие двери не переносится`() {
        assertEquals(emptyList<String>(), ZapasnoyPut.drugieDveri("https://example.org/sub", listOf(subkv, node2)))
        assertEquals(listOf(node2), ZapasnoyPut.drugieDveri(url, listOf(subkv, node2, node2.uppercase())))
    }
}
