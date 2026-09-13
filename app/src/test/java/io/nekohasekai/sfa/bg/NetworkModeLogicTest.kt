package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Расширено осознанно: законных связок теперь две. Вторая — белый список,
     * который пропускает TCP и рвёт TLS. Она требует всех четырёх фактов сразу: TCP к
     * неразрешённому прошёл, рукопожатие с канарейкой сломано, TLS к незаблокированному
     * неразрешённому сломан, TLS к разрешённому прошёл. Любой другой набор — не белый список.
     */
    @Test
    fun `белый список не объявляется никогда, кроме связки «тишина плюс живой контроль» или «TCP есть, TLS рвут всем, кроме разрешённого»`() {
        val broken = setOf(ProbeOutcome.Reset, ProbeOutcome.Stalled)
        val passed = setOf(ProbeOutcome.Ok, ProbeOutcome.Answered)
        for (unlisted in ProbeOutcome.entries) {
            for (allowed in ProbeOutcome.entries) {
                for (canary in ProbeOutcome.entries) {
                    for (tlsUnlisted in ProbeOutcome.entries) {
                        for (tlsAllowed in ProbeOutcome.entries) {
                            val s = NetworkSignals(
                                physicalNetwork = true,
                                tcpUnlisted = unlisted,
                                tcpAllowed = allowed,
                                tlsCanary = canary,
                                tlsUnlisted = tlsUnlisted,
                                tlsAllowed = tlsAllowed,
                            )
                            if (decide(s) != NetworkMode.Whitelist) continue
                            val bySilence = unlisted == ProbeOutcome.Silence && allowed == ProbeOutcome.Ok
                            val byTls = unlisted == ProbeOutcome.Ok &&
                                (canary in broken || canary == ProbeOutcome.Failed) &&
                                tlsUnlisted in broken &&
                                tlsAllowed in passed
                            assertTrue("белый список объявлен по признакам $s", bySilence || byTls)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------ белый список, который рвёт TLS

    @Test
    fun `TCP проходит, TLS рвут и канарейке, и резолверу, а разрешённому нет — белый список`() {
        assertEquals(
            NetworkMode.Whitelist,
            decide(
                NetworkSignals(
                    physicalNetwork = true,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Reset,
                    tlsUnlisted = ProbeOutcome.Reset,
                    tlsAllowed = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `канарейку рвут, а резолверу TLS пропускают — это DPI, а не белый список`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(
                NetworkSignals(
                    physicalNetwork = true,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Reset,
                    tlsUnlisted = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `TLS сломан вообще у всех, и у разрешённого тоже — не белый список`() {
        for (control in ProbeOutcome.entries.filter { it != ProbeOutcome.Ok && it != ProbeOutcome.Answered }) {
            assertNotEquals(
                "контроль $control",
                NetworkMode.Whitelist,
                decide(
                    NetworkSignals(
                        physicalNetwork = true,
                        tcpUnlisted = ProbeOutcome.Ok,
                        tlsCanary = ProbeOutcome.Stalled,
                        tlsUnlisted = ProbeOutcome.Stalled,
                        tlsAllowed = control,
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------ устойчивый контроль

    @Test
    fun `одна осечка контроля на одном адресе — не DPI, а неизвестность`() {
        for (control in ProbeOutcome.entries.filter { it != ProbeOutcome.Ok && it != ProbeOutcome.Answered }) {
            assertEquals(
                "контроль $control за одну попытку",
                NetworkMode.Unknown,
                decide(
                    NetworkSignals(
                        physicalNetwork = true,
                        tcpUnlisted = ProbeOutcome.Ok,
                        tlsCanary = ProbeOutcome.Reset,
                        tlsUnlisted = ProbeOutcome.Reset,
                        tlsAllowed = control,
                        tlsAllowedTries = 1,
                    ),
                ),
            )
        }
    }

    @Test
    fun `TLS к разрешённому сломан на двух адресах — уверенный провал, это DPI`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(
                NetworkSignals(
                    physicalNetwork = true,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Reset,
                    tlsUnlisted = ProbeOutcome.Reset,
                    tlsAllowed = ProbeOutcome.Stalled,
                    tlsAllowedTries = NetworkModeDecision.CONTROL_CONFIDENT_TRIES,
                ),
            ),
        )
    }

    @Test
    fun `итог контроля — первая прошедшая попытка решает, провал только если сломан весь TLS`() {
        val c = NetworkModeDecision::controlOutcome
        assertEquals(ProbeOutcome.Skipped, c(emptyList()))
        assertEquals(ProbeOutcome.Ok, c(listOf(ProbeOutcome.Silence, ProbeOutcome.Ok)))
        assertEquals(ProbeOutcome.Answered, c(listOf(ProbeOutcome.Stalled, ProbeOutcome.Answered)))
        assertEquals(ProbeOutcome.Stalled, c(listOf(ProbeOutcome.Reset, ProbeOutcome.Stalled)))
        assertEquals("тишина в смеси — контроль не состоялся", ProbeOutcome.Failed, c(listOf(ProbeOutcome.Silence, ProbeOutcome.Reset)))
        assertEquals(ProbeOutcome.Failed, c(listOf(ProbeOutcome.Silence)))
    }

    // ------------------------------------------------ белый список, который замерзает после окна

    private fun freeze(
        bulkUnlisted: ProbeOutcome = ProbeOutcome.Stalled,
        unlistedBytes: Int = 0,
        bulkAllowed: ProbeOutcome = ProbeOutcome.Ok,
        allowedBytes: Int = 42_000,
    ) = NetworkSignals(
        physicalNetwork = true,
        tcpUnlisted = ProbeOutcome.Ok,
        tlsCanary = ProbeOutcome.Ok,
        bulkCanary = ProbeOutcome.Stalled,
        bulkBytes = 8668,
        bulkUnlisted = bulkUnlisted,
        bulkUnlistedBytes = unlistedBytes,
        bulkAllowed = bulkAllowed,
        bulkAllowedBytes = allowedBytes,
    )

    @Test
    fun `рукопожатие проходит, поток встаёт и у резолвера, а с разрешённого идёт больше окна — белый список`() {
        val s = freeze(unlistedBytes = 3_000)
        assertEquals(NetworkMode.Whitelist, decide(s))
        assertTrue(NetworkModeDecision.survivesOpenPort(NetworkModeReport(mode = NetworkMode.Whitelist, signals = s, atMillis = 0, tookMillis = 0, note = "")))
    }

    @Test
    fun `поток встал у канарейки, а у резолвера прошёл — это DPI`() {
        assertEquals(NetworkMode.DpiBlacklist, decide(freeze(bulkUnlisted = ProbeOutcome.Ok, unlistedBytes = 36_381)))
    }

    @Test
    fun `передачу у резолвера не мерили — прежний вердикт DPI`() {
        assertEquals(NetworkMode.DpiBlacklist, decide(freeze(bulkUnlisted = ProbeOutcome.Skipped, bulkAllowed = ProbeOutcome.Skipped)))
    }

    @Test
    fun `контроль передачи короткий — окно не пройдено, белый список не объявляется`() {
        assertEquals(NetworkMode.Unknown, decide(freeze(allowedBytes = 9_376)))
        assertEquals(NetworkMode.Unknown, decide(freeze(bulkAllowed = ProbeOutcome.Stalled, allowedBytes = 17_000)))
    }

    @Test
    fun `резолвер успел отдать больше окна и лишь потом встал — это не замерзание белого списка`() {
        assertNotEquals(NetworkMode.Whitelist, decide(freeze(unlistedBytes = 40_000)))
    }

    /** Гарантия для третьей связки: перебор всех исходов передач при живом рукопожатии. */
    @Test
    fun `по передаче белый список объявляется только связкой «резолвер встал в окне, разрешённый прошёл окно»`() {
        val broken = setOf(ProbeOutcome.Reset, ProbeOutcome.Stalled)
        val sizes = listOf(0, 16_000, NetworkModeDecision.FREEZE_PROOF_BYTES, 64_000)
        for (canaryTls in ProbeOutcome.entries) for (canary in ProbeOutcome.entries) for (unlisted in ProbeOutcome.entries)
            for (allowed in ProbeOutcome.entries) for (ub in sizes) for (ab in sizes) {
                val s = NetworkSignals(
                    physicalNetwork = true,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = canaryTls,
                    bulkCanary = canary,
                    bulkUnlisted = unlisted,
                    bulkUnlistedBytes = ub,
                    bulkAllowed = allowed,
                    bulkAllowedBytes = ab,
                )
                if (decide(s) != NetworkMode.Whitelist) continue
                val byBulk = canaryTls == ProbeOutcome.Ok && canary in broken && unlisted in broken &&
                    ub < NetworkModeDecision.FREEZE_PROOF_BYTES && allowed == ProbeOutcome.Ok &&
                    ab >= NetworkModeDecision.FREEZE_PROOF_BYTES
                assertTrue("белый список объявлен по признакам $s", byBulk)
            }
    }

    @Test
    fun `к резолверу TCP молчит — это не «TLS рвут», белый список по TLS не объявляется`() {
        assertEquals(
            NetworkMode.DpiBlacklist,
            decide(
                NetworkSignals(
                    physicalNetwork = true,
                    tcpUnlisted = ProbeOutcome.Ok,
                    tlsCanary = ProbeOutcome.Reset,
                    tlsUnlisted = ProbeOutcome.Silence,
                    tlsAllowed = ProbeOutcome.Ok,
                ),
            ),
        )
    }

    @Test
    fun `вердикт по TLS переживает открытый порт узла, а вердикт по тишине — нет`() {
        val byTls = NetworkSignals(
            physicalNetwork = true,
            tcpUnlisted = ProbeOutcome.Ok,
            tlsCanary = ProbeOutcome.Reset,
            tlsUnlisted = ProbeOutcome.Reset,
            tlsAllowed = ProbeOutcome.Ok,
        )
        val bySilence = signals(tcpUnlisted = ProbeOutcome.Silence, tcpAllowed = ProbeOutcome.Ok)
        fun report(s: NetworkSignals) = NetworkModeReport(decide(s), s, atMillis = 0, tookMillis = 0, note = "")
        assertTrue(NetworkModeDecision.survivesOpenPort(report(byTls)))
        assertFalse(NetworkModeDecision.survivesOpenPort(report(bySilence)))
        assertFalse(NetworkModeDecision.survivesOpenPort(null))
        // Не белый список — переживать нечего.
        val dpi = byTls.copy(tlsUnlisted = ProbeOutcome.Ok)
        assertFalse(NetworkModeDecision.survivesOpenPort(report(dpi)))
    }

    @Test
    fun `объяснение белого списка по TLS не говорит про тишину`() {
        val s = NetworkSignals(
            physicalNetwork = true,
            tcpUnlisted = ProbeOutcome.Ok,
            tlsCanary = ProbeOutcome.Reset,
            tlsUnlisted = ProbeOutcome.Reset,
            tlsAllowed = ProbeOutcome.Answered,
        )
        val note = NetworkModeDecision.explain(decide(s), s)
        assertTrue(note, note.contains("TLS"))
        assertFalse(note, note.contains("тишина"))
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

    /** Строка журнала телефона: отправка журнала под белым списком оператора. */
    @Test
    fun `рукопожатие закрыли на полпути — Reset, а не невнятный Failed`() {
        assertEquals(
            ProbeOutcome.Reset,
            ProbeFailure.onHandshake(SSLHandshakeException("connection closed")),
        )
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
