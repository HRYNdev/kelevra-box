package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Запасной список выходов из конфига (ядро молчит: дома туннель погашен, первые секунды
 * после включения). Профиль полигона: «Соединение» = Нидерланды, Финляндия, Комната,
 * Комната WB. Раньше в список попадали только основной узел, комната и WB — «Финляндии»
 * в ручном выборе не было (17.10).
 */
class AutoModeZapasnoySpisokTest {

    private fun vless(tag: String, server: String, port: Int) =
        """{"tag":"$tag","type":"vless","server":"$server","server_port":$port}"""

    private val poligon = """
        {"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080}],
         "outbounds":[
           {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Финляндия","Комната","Комната WB"],"default":"Нидерланды"},
           {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · XHTTP"]},
           {"tag":"Финляндия","type":"urltest","outbounds":["Финляндия · XHTTP","Финляндия · Vision"]},
           ${vless("Нидерланды · XHTTP", "nodekv.example", 2083)},
           ${vless("Финляндия · XHTTP", "node2kv.example", 2053)},
           ${vless("Финляндия · Vision", "node2kv.example", 443)},
           {"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808},
           {"tag":"Комната WB","type":"socks","server":"127.0.0.1","server_port":8818},
           {"tag":"direct","type":"direct"}]}
    """.trimIndent()

    private val layout = AutoModeExits.parse(poligon, 8808)

    private fun tags(rows: List<Pair<String, Boolean>>) = rows.map { it.first }
    private fun chosen(rows: List<Pair<String, Boolean>>) = rows.filter { it.second }.map { it.first }

    @Test
    fun `все узлы по порядку, затем комната и WB`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = false, manualExit = "", wbTag = "Комната WB")
        assertEquals(listOf("Нидерланды", "Финляндия", "Комната", "Комната WB"), tags(rows))
    }

    @Test
    fun `без выбора руками отмечен основной узел`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = false, manualExit = "", wbTag = "Комната WB")
        assertEquals(listOf("Нидерланды"), chosen(rows))
    }

    @Test
    fun `выбранная руками Финляндия отмечена она одна`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = false, manualExit = "Финляндия", wbTag = "Комната WB")
        assertEquals(listOf("Финляндия"), chosen(rows))
    }

    @Test
    fun `выбранная комната отмечена она одна`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = true, manualExit = "Комната", wbTag = "Комната WB")
        assertEquals(listOf("Комната"), chosen(rows))
    }

    @Test
    fun `выбранный WB отмечен он один`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = false, manualExit = "Комната WB", wbTag = "Комната WB")
        assertEquals(listOf("Комната WB"), chosen(rows))
    }

    @Test
    fun `выбор руками, которого нет в конфиге, — отмечен основной`() {
        val rows = AutoModeExits.configRows(layout, roomChosen = false, manualExit = "Германия", wbTag = null)
        assertEquals(listOf("Нидерланды", "Финляндия", "Комната"), tags(rows))
        assertEquals(listOf("Нидерланды"), chosen(rows))
    }

    @Test
    fun `профиль с одним узлом — как раньше`() {
        val odin = AutoModeExits.parse(
            """
            {"outbounds":[
               {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Комната"],"default":"Нидерланды"},
               {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · прямой"]},
               ${vless("Нидерланды · прямой", "nodekv.example", 443)},
               {"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808}]}
            """.trimIndent(),
            8808,
        )
        val rows = AutoModeExits.configRows(odin, roomChosen = false, manualExit = "", wbTag = null)
        assertEquals(listOf("Нидерланды" to true, "Комната" to false), rows)
    }
}
