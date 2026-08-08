package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Проверки чистой логики определителя режима сети: таблица решений по признакам
 * и разбор ошибок сокета в признаки.
 *
 * Сети тут нет ни одного байта, Android тоже: [NetworkModeDecision] и [ProbeFailure]
 * живут в файле без единого `android.*` импорта, поэтому проверяются на JVM.
 *
 * Тест намеренно не трогает ни [AutoMode], ни `Settings`: инициализация того и
 * другого на JVM падает, и привязываться к ней значило бы получить тест, который
 * ничего не проверяет.
 */
class NetworkModeLogicTest {

    private fun signals(
        physicalNetwork: Boolean = true,
        tcpUnlisted: ProbeOutcome = ProbeOutcome.Skipped,
        tcpAllowed: ProbeOutcome = ProbeOutcome.Skipped,
        tlsCanary: ProbeOutcome = ProbeOutcome.Skipped,
        bulkCanary: ProbeOutcome = ProbeOutcome.Skipped,
        bulkBytes: Int = 0,
    ) = NetworkSignals(
        physicalNetwork = physicalNetwork,
        tcpUnlisted = tcpUnlisted,
        tcpAllowed = tcpAllowed,
        tlsCanary = tlsCanary,
        bulkCanary = bulkCanary,
        bulkBytes = bulkBytes,
    )

    private fun decide(s: NetworkSignals) = NetworkModeDecision.decide(s)

    // ------------------------------------------------------------ таблица решений

    @Test
    fun `сети под нами нет — мерить нечего, что бы ни показали остальные пробы`() {
        assertEquals(
            NetworkMode.NoNetwork,
            decide(
                signals(
                    physicalNetwork = false,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Ok,
                    bulkCanary = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `неразрешённый молчит, разрешённый отвечает — белый список`() {
        assertEquals(
            NetworkMode.Whitelist,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Silence,
                    tcpAllowed = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `молчит и неразрешённый, и контроль — это не белый список, а неизвестность`() {
        assertEquals(
            NetworkMode.Unknown,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Silence,
                    tcpAllowed = ProbeOutcome.Silence,
                ),
            ),
        )
    }

    @Test
    fun `тишина без контрольной пробы вердикта не даёт`() {
        assertEquals(
            NetworkMode.Unknown,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Silence,
                    tcpAllowed = ProbeOutcome.Skipped,
                ),
            ),
        )
    }

    @Test
    fun `явный отказ — не подпись белого списка`() {
        for (control in ProbeOutcome.entries) {
            assertNotEquals(
                "отказ при контроле $control не должен читаться как белый список",
                NetworkMode.Whitelist,
                decide(signals(tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = control)),
            )
        }
    }

    @Test
    fun `недостижимость — тоже ответ, значит не белый список`() {
        assertEquals(
            NetworkMode.Unknown,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Unreachable,
                    tcpAllowed = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `TCP доходит, TLS рвут — чёрный список с DPI`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(signals(tcpUnlisted = ProbeOutcome.Ok, tlsCanary = ProbeOutcome.Reset)),
        )
    }

    @Test
    fun `TCP доходит, рукопожатие подвисло — чёрный список с DPI`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(signals(tcpUnlisted = ProbeOutcome.Ok, tlsCanary = ProbeOutcome.Stalled)),
        )
    }

    @Test
    fun `рукопожатие встало, поток подвис после первых килобайт — DPI`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Ok,
                    bulkCanary = ProbeOutcome.Stalled,
                    bulkBytes = 16 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `рукопожатие встало, поток оборвали — DPI`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Ok,
                    bulkCanary = ProbeOutcome.Reset,
                    bulkBytes = 20 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `всё прошло целиком — норма`() {
        assertEquals(
            NetworkMode.Normal,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Ok,
                    bulkCanary = ProbeOutcome.Ok,
                    bulkBytes = 64 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `сертификат не понравился — значит TLS прошёл по сети целым, это норма`() {
        assertEquals(
            NetworkMode.Normal,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Answered,
                    bulkCanary = ProbeOutcome.Skipped,
                ),
            ),
        )
    }

    @Test
    fun `рукопожатие сорвалось непонятно чем — не выдумываем DPI`() {
        assertEquals(
            NetworkMode.Unknown,
            decide(signals(tcpUnlisted = ProbeOutcome.Ok, tlsCanary = ProbeOutcome.Failed)),
        )
    }

    @Test
    fun `рукопожатие встало, а передачу не мерили — вердикта нет`() {
        assertEquals(
            NetworkMode.Unknown,
            decide(
                signals(
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Ok,
                    bulkCanary = ProbeOutcome.Skipped,
                ),
            ),
        )
    }

    // ------------------------------------------------- гарантии, а не отдельные случаи

    @Test
    fun `белый список не объявляется никогда, кроме связки «тишина плюс живой контроль»`() {
        for (unlisted in ProbeOutcome.entries) {
            for (allowed in ProbeOutcome.entries) {
                val mode = decide(signals(tcpUnlisted = unlisted, tcpAllowed = allowed))
                val legitimate = unlisted == ProbeOutcome.Silence && allowed == ProbeOutcome.Ok
                if (mode == NetworkMode.Whitelist) {
                    assertTrue(
                        "белый список объявлен по признакам $unlisted / $allowed",
                        legitimate,
                    )
                }
            }
        }
    }

    @Test
    fun `без физической сети не объявляется ни один режим, кроме «сети нет»`() {
        for (unlisted in ProbeOutcome.entries) {
            for (tls in ProbeOutcome.entries) {
                assertEquals(
                    NetworkMode.NoNetwork,
                    decide(
                        signals(
                            physicalNetwork = false,
                            tcpUnlisted = unlisted,
                            tlsCanary = tls,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `решение чистое — те же признаки дают тот же режим`() {
        for (unlisted in ProbeOutcome.entries) {
            for (tls in ProbeOutcome.entries) {
                val s = signals(tcpUnlisted = unlisted, tlsCanary = tls, bulkCanary = tls)
                assertEquals(decide(s), decide(s))
            }
        }
    }

    @Test
    fun `на любых признаках вердикт объясним словами`() {
        for (unlisted in ProbeOutcome.entries) {
            val s = signals(tcpUnlisted = unlisted)
            val note = NetworkModeDecision.explain(decide(s), s)
            assertTrue("пустое объяснение для $unlisted", note.isNotBlank())
        }
    }

    // ------------------------------------------------------- разбор ошибок соединения

    @Test
    fun `таймаут соединения — это тишина, а не отказ`() {
        assertEquals(
            ProbeOutcome.Silence,
            ProbeFailure.onConnect(SocketTimeoutException("connect timed out")),
        )
    }

    @Test
    fun `RST — это отказ`() {
        assertEquals(
            ProbeOutcome.Refused,
            ProbeFailure.onConnect(ConnectException("failed to connect: ECONNREFUSED (Connection refused)")),
        )
    }

    @Test
    fun `нет маршрута — недостижимость`() {
        assertEquals(
            ProbeOutcome.Unreachable,
            ProbeFailure.onConnect(NoRouteToHostException("No route to host")),
        )
        assertEquals(
            ProbeOutcome.Unreachable,
            ProbeFailure.onConnect(PortUnreachableException("port unreachable")),
        )
        assertEquals(
            ProbeOutcome.Unreachable,
            ProbeFailure.onConnect(ConnectException("Network is unreachable")),
        )
    }

    @Test
    fun `непонятная ошибка соединения ничего не доказывает`() {
        assertEquals(ProbeOutcome.Failed, ProbeFailure.onConnect(IOException("что-то не то")))
    }

    // ---------------------------------------------------------- разбор ошибок рукопожатия

    @Test
    fun `рукопожатие по таймауту — подвисание`() {
        assertEquals(
            ProbeOutcome.Stalled,
            ProbeFailure.onHandshake(SocketTimeoutException("Read timed out")),
        )
    }

    @Test
    fun `сертификат не из хранилища — собеседник ответил, сеть ни при чём`() {
        assertEquals(
            ProbeOutcome.Answered,
            ProbeFailure.onHandshake(
                SSLHandshakeException("Trust anchor for certification path not found."),
            ),
        )
    }

    /**
     * Случай пойман живьём: ClientHello с чужим именем на адрес нашего VPS, и сервер
     * честно ответил предупреждением «не знаю такого имени». Предупреждение прислал
     * собеседник — значит ClientHello дошёл и ответ вернулся целым. Разбирать это как
     * невнятную неудачу нельзя: так теряется доказательство, что сеть TLS не трогала.
     */
    @Test
    fun `предупреждение от собеседника — доказательство, что TLS прошёл по сети`() {
        val real = SSLHandshakeException(
            "Read error: ssl=0x7b97f1bab398: Failure in SSL library, usually a protocol error\n" +
                "error:10000458:SSL routines:OPENSSL_internal:TLSV1_ALERT_UNRECOGNIZED_NAME " +
                "(external/boringssl/src/ssl/tls_record.cc:592)",
        )
        assertEquals(ProbeOutcome.Answered, ProbeFailure.onHandshake(real))
    }

    @Test
    fun `разрыв на рукопожатии — Reset, а не проблема доверия`() {
        assertEquals(
            ProbeOutcome.Reset,
            ProbeFailure.onHandshake(SSLException("Connection reset by peer")),
        )
        assertEquals(
            ProbeOutcome.Reset,
            ProbeFailure.onHandshake(SocketException("Connection reset")),
        )
        assertEquals(ProbeOutcome.Reset, ProbeFailure.onHandshake(EOFException()))
    }

    // -------------------------------------------------------------- разбор передачи

    @Test
    fun `набрали нужный объём — передача прошла`() {
        assertEquals(
            ProbeOutcome.Ok,
            ProbeFailure.onTransfer(bytes = 65536, wanted = 65536, complete = true, error = null),
        )
    }

    @Test
    fun `короткий, но дочитанный до конца ответ — тоже норма`() {
        assertEquals(
            ProbeOutcome.Ok,
            ProbeFailure.onTransfer(bytes = 1200, wanted = 65536, complete = true, error = null),
        )
    }

    @Test
    fun `данные кончились на полпути без EOF — подвисание`() {
        assertEquals(
            ProbeOutcome.Stalled,
            ProbeFailure.onTransfer(bytes = 16384, wanted = 65536, complete = false, error = null),
        )
    }

    @Test
    fun `рукопожатие прошло, а данных не пришло вовсе — подвисание`() {
        assertEquals(
            ProbeOutcome.Stalled,
            ProbeFailure.onTransfer(bytes = 0, wanted = 65536, complete = true, error = null),
        )
    }

    @Test
    fun `поток встал по таймауту чтения — подвисание`() {
        assertEquals(
            ProbeOutcome.Stalled,
            ProbeFailure.onTransfer(
                bytes = 16384,
                wanted = 65536,
                complete = false,
                error = SocketTimeoutException("Read timed out"),
            ),
        )
    }

    @Test
    fun `поток оборвали на ходу — разрыв`() {
        assertEquals(
            ProbeOutcome.Reset,
            ProbeFailure.onTransfer(
                bytes = 16384,
                wanted = 65536,
                complete = false,
                error = SocketException("Connection reset"),
            ),
        )
    }
}
