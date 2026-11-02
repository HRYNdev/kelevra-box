package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор руками хранится именем пункта, а имена задаёт сервер. Профиль «входы отдельно»
 * делает из узла «Финляндия» пункты «Финляндия · XHTTP» и «Финляндия · Vision»: имя
 * «Финляндия», выбранное раньше, в селекторе больше не встречается — такой выбор считается
 * отсутствующим, автомат и очередь узлов не держит.
 */
class AutoModeUstarevshiyVyborTest {

    private fun vless(tag: String, server: String, port: Int) =
        """{"tag":"$tag","type":"vless","server":"$server","server_port":$port}"""

    private val vhodyOtdelno = """
        {"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080}],
         "outbounds":[
           {"tag":"Соединение","type":"selector","outbounds":["Финляндия · XHTTP","Финляндия · Vision","Нидерланды · XHTTP","Hysteria2","Комната"],"default":"Финляндия · XHTTP"},
           {"tag":"Финляндия · XHTTP","type":"urltest","outbounds":["fi-1"]},
           {"tag":"Финляндия · Vision","type":"urltest","outbounds":["fi-1-vision"]},
           {"tag":"Нидерланды · XHTTP","type":"urltest","outbounds":["vu-1"]},
           ${vless("fi-1", "node2kv.example", 2053)},
           ${vless("fi-1-vision", "node2kv.example", 443)},
           ${vless("vu-1", "nodekv.example", 2083)},
           {"tag":"Hysteria2","type":"hysteria2","server":"node2kv.example","server_port":8443},
           {"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808},
           {"tag":"direct","type":"direct"}]}
    """.trimIndent()

    private val layout = AutoModeExits.parse(vhodyOtdelno, 8808)

    @Test
    fun `пункты селектора разобраны по порядку сервера`() {
        assertEquals(
            listOf("Финляндия · XHTTP", "Финляндия · Vision", "Нидерланды · XHTTP", "Hysteria2", "Комната"),
            layout.choices,
        )
    }

    @Test
    fun `прежнее имя узла — выбор устарел`() {
        assertTrue(AutoModeExits.isStaleChoice(layout, "Финляндия"))
        assertTrue(AutoModeExits.isStaleChoice(layout, "Нидерланды"))
    }

    @Test
    fun `пункт, который в конфиге есть, — выбор живой`() {
        assertFalse(AutoModeExits.isStaleChoice(layout, "Финляндия · Vision"))
        assertFalse(AutoModeExits.isStaleChoice(layout, "Hysteria2"))
        assertFalse(AutoModeExits.isStaleChoice(layout, "Комната"))
    }

    @Test
    fun `выбора нет или конфиг не прочитан — забывать нечего`() {
        assertFalse(AutoModeExits.isStaleChoice(layout, null))
        assertFalse(AutoModeExits.isStaleChoice(layout, ""))
        assertFalse(AutoModeExits.isStaleChoice(AutoModeExits.Layout.EMPTY, "Финляндия"))
        assertFalse(AutoModeExits.isStaleChoice(AutoModeExits.parse("не json", 8808), "Финляндия"))
    }

    @Test
    fun `откат признака — новое имя на старом конфиге тоже устарело`() {
        val staryy = """
            {"outbounds":[
              {"tag":"Соединение","type":"selector","outbounds":["Финляндия","Нидерланды","Комната"]},
              {"tag":"Финляндия","type":"urltest","outbounds":["Финляндия · XHTTP","Финляндия · Vision"]},
              {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · XHTTP"]},
              ${vless("Финляндия · XHTTP", "node2kv.example", 2053)},
              ${vless("Финляндия · Vision", "node2kv.example", 443)},
              ${vless("Нидерланды · XHTTP", "nodekv.example", 2083)},
              {"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808}]}
        """.trimIndent()
        val l = AutoModeExits.parse(staryy, 8808)
        assertTrue(AutoModeExits.isStaleChoice(l, "Нидерланды · XHTTP"))
        assertFalse(AutoModeExits.isStaleChoice(l, "Финляндия"))
    }
}
