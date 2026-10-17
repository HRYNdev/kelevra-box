package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Очередь узлов (решение 17.10.2026): сервер кладёт в «Соединение» группу на каждый узел по
 * порядку человека — первый основной, дальше запасные. Раскладка должна это узнать, а профиль
 * с одним узлом — читаться ровно как раньше.
 */
class AutoModeOcheredUzlovTest {

    private val komnata = """{"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808}"""

    private fun vless(tag: String, server: String, port: Int) =
        """{"tag":"$tag","type":"vless","server":"$server","server_port":$port}"""

    /** Профиль, как его собирает выдача с двумя узлами (`_profil_po_uzlam`), плюс входы пробы. */
    private val dvaUzla = """
        {"inbounds":[
           {"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080},
           {"type":"socks","tag":"kelevra-probe-0","listen":"127.0.0.1","listen_port":3001},
           {"type":"socks","tag":"kelevra-probe-1","listen":"127.0.0.1","listen_port":3002}],
         "route":{"rules":[
           {"inbound":["kelevra-probe-0"],"outbound":"Нидерланды"},
           {"inbound":["kelevra-probe-1"],"outbound":"Финляндия"}]},
         "outbounds":[
           {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Финляндия","Комната"],"default":"Нидерланды"},
           {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · XHTTP"]},
           {"tag":"Финляндия","type":"urltest","outbounds":["Финляндия · XHTTP","Финляндия · Vision"]},
           {"tag":"direct","type":"direct"},
           ${vless("Финляндия · XHTTP", "node2kv.example", 2053)},
           ${vless("Финляндия · Vision", "node2kv.example", 443)},
           ${vless("Нидерланды · XHTTP", "nodekv.example", 2083)},
           $komnata]}
    """.trimIndent()

    @Test
    fun `очередь узлов в порядке сервера`() {
        val l = AutoModeExits.parse(dvaUzla, 8808)
        assertEquals(listOf("Нидерланды", "Финляндия"), l.nodes)
        assertEquals("Нидерланды", l.main)
        assertEquals("Комната", l.room)
    }

    @Test
    fun `у каждого узла свои адреса`() {
        val l = AutoModeExits.parse(dvaUzla, 8808)
        assertEquals(listOf(AutoModeExits.Endpoint("nodekv.example", 2083)), l.nodeEndpoints["Нидерланды"])
        assertEquals(
            listOf(AutoModeExits.Endpoint("node2kv.example", 2053), AutoModeExits.Endpoint("node2kv.example", 443)),
            l.nodeEndpoints["Финляндия"],
        )
    }

    @Test
    fun `мерить по отдельности нужно каждый узел`() {
        assertEquals(listOf("Нидерланды", "Финляндия"), AutoModeExits.parse(dvaUzla, 8808).measurable)
    }

    @Test
    fun `запасной узел становится основным со своим входом`() {
        val l = AutoModeExits.parse(dvaUzla, 8808).forNode("Финляндия")
        assertEquals("Финляндия", l.main)
        assertEquals(2, l.mainEndpoints.size)
        assertEquals(AutoModeExits.Endpoint("127.0.0.1", 3002), l.localProxy)
        assertEquals(true, l.mainPinned)
        // Очередь и комната те же: узел сменился, раскладка нет.
        assertEquals(listOf("Нидерланды", "Финляндия"), l.nodes)
        assertEquals("Комната", l.room)
    }

    @Test
    fun `возврат на первый узел даёт исходную раскладку`() {
        val base = AutoModeExits.parse(dvaUzla, 8808)
        assertSame(base, base.forNode("Нидерланды"))
        assertSame(base, base.forNode("нет такого"))
    }

    @Test
    fun `профиль с одним узлом читается как раньше`() {
        val odin = """
            {"outbounds":[
               {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Комната"],"default":"Нидерланды"},
               {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · прямой"]},
               ${vless("Нидерланды · прямой", "nodekv.example", 443)},
               $komnata]}
        """.trimIndent()
        val l = AutoModeExits.parse(odin, 8808)
        assertEquals(listOf("Нидерланды"), l.nodes)
        assertEquals(listOf("Нидерланды"), l.measurable)
        assertEquals("Нидерланды", l.main)
    }

    @Test
    fun `ручной выход в селекторе — не узел`() {
        val sRuchnym = """
            {"outbounds":[
               {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Германия · проба","Комната"]},
               {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · прямой"]},
               ${vless("Нидерланды · прямой", "nodekv.example", 443)},
               ${vless("Германия · проба", "de.example", 443)},
               $komnata]}
        """.trimIndent()
        assertEquals(listOf("Нидерланды"), AutoModeExits.parse(sRuchnym, 8808).nodes)
    }

    @Test
    fun `без селектора очереди нет`() {
        val l = AutoModeExits.parse("""{"outbounds":[${vless("x", "a.example", 443)}]}""", 8808)
        assertEquals(emptyList<String>(), l.nodes)
        assertNull(l.main)
    }
}
