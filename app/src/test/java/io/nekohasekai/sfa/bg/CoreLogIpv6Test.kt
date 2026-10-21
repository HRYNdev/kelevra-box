package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Сеть недоступна» на IPv6-адрес - у сети нет v6-маршрута, а не обрыв туннеля: в долю тревоги не идёт. */
class CoreLogIpv6Test {
    @Test
    fun `network unreachable на IPv6 не в долю отказов`() {
        assertTrue(CoreLog.bezMarshrutaV6("set_nedostupna", "2a02:6b8::2:242"))
    }

    @Test
    fun `network unreachable на IPv4 остаётся отказом`() {
        assertFalse(CoreLog.bezMarshrutaV6("set_nedostupna", "93.184.216.34"))
    }

    @Test
    fun `таймаут и сброс на IPv6 остаются отказами`() {
        assertFalse(CoreLog.bezMarshrutaV6("taymaut", "2a02:6b8::2:242"))
        assertFalse(CoreLog.bezMarshrutaV6("sbros", "2a02:6b8::2:242"))
    }

    @Test
    fun `без адреса не считаем v6`() {
        assertFalse(CoreLog.bezMarshrutaV6("set_nedostupna", ""))
    }
}
