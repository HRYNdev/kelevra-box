package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Отказы соединений через узел будят проверку узла (17.10.2026: без трафика смерть узла заметили
 * только через 4 минуты). Узлы своей сети названы по-русски — разбор строки ядра обязан это понимать.
 */
class CoreLogOtkazyUzlaTest {

    @Test
    fun `узел с русским именем узнаётся`() {
        val line = "ERROR [3418245930 5.0s] connection: open connection to 142.250.74.46:443 " +
            "using outbound/vless[Нидерланды · XHTTP]: dial tcp 77.239.102.44:2083: i/o timeout"
        assertEquals("Нидерланды · XHTTP", CoreLog.vyhodUzla(line))
    }

    @Test
    fun `прямой выход и комната — не узел`() {
        assertNull(CoreLog.vyhodUzla("ERROR connection: open connection to 1.2.3.4:443 using outbound/direct[direct]: refused"))
        assertNull(CoreLog.vyhodUzla("ERROR connection: open connection to 1.2.3.4:443 using outbound/socks[Комната]: EOF"))
    }

    @Test
    fun `строка без выхода — не узел`() {
        assertNull(CoreLog.vyhodUzla("ERROR connection: open connection to 1.2.3.4:443: no route to host"))
    }
}
