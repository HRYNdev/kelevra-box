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

    @Test
    fun `служебный запрос - в туннель только к своим хостам`() {
        val dveri = listOf(subkv, node2)
        assertTrue(ZapasnoyPut.svoyHost("https://$subkv/logs", dveri))
        assertTrue(ZapasnoyPut.svoyHost("https://${node2.uppercase()}/k/ABC/pult/zhdat?versiya=1", dveri))
        // ядра комнаты: Телемост, wb.ru, TURN - в туннель нельзя (петля)
        assertFalse(ZapasnoyPut.svoyHost("https://telemost.yandex.ru/j/123", dveri))
        assertFalse(ZapasnoyPut.svoyHost("https://stream.wb.ru/x", dveri))
        assertFalse(ZapasnoyPut.svoyHost("https://176.101.92.5:3478/", dveri))
        assertFalse(ZapasnoyPut.svoyHost("https://$subkv.evil.example/logs", dveri))
    }

    @Test
    fun `служебный запрос - прямой путь прошёл, туннель не нужен`() {
        val r = ZapasnoyPut.sluzhebnyj("https://$subkv/logs", { _: String -> "tunnel" }) { "pryamo" }
        assertEquals("pryamo", r)
    }

    @Test
    fun `служебный запрос - ответ сервера пробрасывается`() {
        try {
            ZapasnoyPut.sluzhebnyj("https://$subkv/logs", { _: String -> "tunnel" }) { throw IllegalStateException("HTTP 403") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 403", e.message)
        }
    }

    @Test
    fun `запрос через туннель - POST с телом и заголовками доходит до входа, ответ разобран`() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        var poluchili = ""
        val t = Thread {
            server.accept().use { s ->
                val inp = s.getInputStream()
                val buf = ByteArray(8192)
                var n = 0
                // читаем, пока не пришло тело (оно в конце запроса)
                while (!poluchili.endsWith("DATA")) {
                    val k = inp.read(buf, n, buf.size - n)
                    if (k <= 0) break
                    n += k
                    poluchili = String(buf, 0, n, Charsets.ISO_8859_1)
                }
                s.getOutputStream().write(
                    "HTTP/1.1 429 Too Many\r\nRetry-After: 42\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                )
            }
        }
        t.start()
        val o = ZapasnoyPut.zaprosCherezTunnel(
            "http://$subkv/logs", "POST", "DATA".toByteArray(), listOf("X-Test" to "1"), port = server.localPort,
        )
        t.join(3000)
        server.close()
        assertEquals(429, o.kod)
        assertEquals("ok", o.telo)
        assertEquals("42", o.retryAfter)
        assertTrue(poluchili.startsWith("POST http://$subkv/logs"))
        assertTrue(poluchili.contains("X-Test: 1"))
    }
}
