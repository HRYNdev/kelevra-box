package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Журнал ядра: отказы туннеля в счёте и что из потока ложится в файл.
 *
 * Строки — настоящие, из журналов телефонов 20.10.2026: у одного при мёртвом туннеле
 * сводка писала «соединений 475, отказов 0», у другого журнал ядра на DEBUG/TRACE
 * вытеснял сам себя за минуты.
 */
class CoreLogTunnelTest {

    private val upload = "ERROR[0002] [3592663761 1.60s] connection: connection upload handshake: io: read/write on closed pipe"
    private val report = "ERROR[1195] [3864939118 104ms] connection: report handshake success: connection refused"
    private val packet = "ERROR[0002] [1386848347 1.40s] connection: listen packet connection using  using " +
        "outbound/urltest[Нидерланды]: io: read/write on closed pipe"
    private val urltest = "DEBUG[0002] outbound/urltest[Финляндия]: outbound Финляндия · XHTTP unavailable: " +
        "Head \"https://www.gstatic.com/generate_204\": io: read/write on closed pipe"
    private val shtatnoe = "ERROR[0002] [1 5ms] connection: connection download closed: http2: response body closed"
    private val pryamoy = "ERROR[0002] [2 3.1s] connection: open connection to 149.154.167.51:443 using " +
        "outbound/direct[direct]: dial rmnet_data0 (1): dial tcp 149.154.167.51:443: i/o timeout"

    @Test
    fun `гибель туннеля считается по выходам`() {
        assertEquals("tunnel_upload", CoreLog.kodOtkazaTunnelya(upload))
        assertEquals("tunnel_rukopozhatie", CoreLog.kodOtkazaTunnelya(report))
        assertEquals("tunnel_pakety", CoreLog.kodOtkazaTunnelya(packet))
        assertEquals("urltest_nedostupen", CoreLog.kodOtkazaTunnelya(urltest))
    }

    @Test
    fun `штатное закрытие потока и прямой отказ — не отказ туннеля`() {
        assertNull(CoreLog.kodOtkazaTunnelya(shtatnoe))
        assertNull(CoreLog.kodOtkazaTunnelya(pryamoy))
        assertNull(CoreLog.kodOtkazaTunnelya("INFO[0001] [5 0ms] inbound/tun[tun-in]: inbound connection to 1.2.3.4:443"))
    }

    @Test
    fun `UDP прямым выходом — не туннель`() {
        assertNull(
            CoreLog.kodOtkazaTunnelya(
                "ERROR[0002] [7 1ms] connection: listen packet connection using outbound/direct[direct]: no route",
            ),
        )
    }

    @Test
    fun `голый адрес отличается от имени`() {
        assertTrue(CoreLog.golyyAdres("149.154.167.51"))
        assertTrue(CoreLog.golyyAdres("2a02:6b8::1"))
        assertFalse(CoreLog.golyyAdres(""))
        assertFalse(CoreLog.golyyAdres("cafe.de"))
    }

    @Test
    fun `уровень строки читается по началу`() {
        assertEquals(5, CoreLog.urovenStroki("DEBUG[1067] [3360056459 0ms] dns: exchange x. IN A"))
        assertEquals(6, CoreLog.urovenStroki("TRACE[0002] something"))
        assertEquals(4, CoreLog.urovenStroki("INFO[1067] router: found package name: x"))
        assertEquals(2, CoreLog.urovenStroki(upload))
        assertNull(CoreLog.urovenStroki("-- связь с ядром потеряна"))
    }

    @Test
    fun `в файл идёт INFO и выше, TRACE и шум DEBUG — нет`() {
        val info = "INFO[1067] [3490676673 0ms] outbound/vless[Нидерланды · XHTTP]: outbound connection to 1.2.3.4:443"
        assertEquals(listOf(info), CoreLog.vFayl(5, info))
        assertEquals(listOf(upload), CoreLog.vFayl(2, upload))
        assertEquals(emptyList<String>(), CoreLog.vFayl(6, "TRACE[0002] [1 0ms] connection: copy"))
        assertEquals(emptyList<String>(), CoreLog.vFayl(5, "DEBUG[1067] [1 0ms] router: pre-match[0] => sniff"))
        assertEquals(emptyList<String>(), CoreLog.vFayl(5, "DEBUG[1067] [1 0ms] dns: exchanged x.com NOERROR 60"))
    }

    @Test
    fun `из DEBUG остаётся то, что разбирает сервер`() {
        val route = "DEBUG[1067] [9 0ms] router: match[3] rule_set=[main-domains] => route(Нидерланды)"
        val direct = "DEBUG[1067] [9 0ms] router: match[5] ip_is_private => direct"
        assertEquals(listOf(route), CoreLog.vFayl(5, route))
        assertEquals(listOf(direct), CoreLog.vFayl(5, direct))
        assertEquals(listOf(urltest), CoreLog.vFayl(5, urltest))
    }

    @Test
    fun `запрос имени пишется только вместе с отказом набором`() {
        val zapros = "DEBUG[1067] [3360056459 0ms] dns: exchange mobile.events.data.microsoft.com. IN AAAA"
        val otkaz = "DEBUG[1067] [3360056459 0ms] dns: match[1] rule_set=ads => reject"
        val drugoy = "DEBUG[1067] [111 0ms] dns: exchange ya.ru. IN A"
        assertEquals(emptyList<String>(), CoreLog.vFayl(5, zapros))
        assertEquals(emptyList<String>(), CoreLog.vFayl(5, drugoy))
        assertEquals(listOf(zapros, otkaz), CoreLog.vFayl(5, otkaz))
        // Второй раз тот же отказ без запроса — только он сам.
        assertEquals(listOf(otkaz), CoreLog.vFayl(5, otkaz))
    }
}
