package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ IPv6, который ушёл бы в комнату.
 *
 * Беда: через сокс комнаты шли соединения к IPv6-адресам дата-центров Телеграма и тут же
 * получали `connection reset by peer` — у ноги комнаты IPv6 нет, её SOCKS его не понимает.
 *
 * Проверяем сборку конфига целиком и маленьким матчером поверх неё: куда попадёт
 * соединение с данным адресом, если читать правила сверху вниз, как ядро.
 */
class OlcRtcIpv6RejectTest {

    private val socksPort = 2409

    private val telegramV6 = "2001:67c:4e8:f002::b"

    private fun config(rules: String, final: String = "direct"): String = JSONObject(
        """
        {
          "route": {"rules": [$rules], "final": "$final"},
          "outbounds": [
            {"type": "selector", "tag": "Соединение", "outbounds": ["Нидерланды", "Комната"], "default": "Нидерланды"},
            {"type": "urltest", "tag": "Нидерланды", "outbounds": ["vless-1"]},
            {"type": "direct", "tag": "direct"},
            {"type": "vless", "tag": "vless-1", "server": "203.0.113.1", "server_port": 443},
            {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": $socksPort, "version": "5"}
          ]
        }
        """.trimIndent(),
    ).toString()

    /** Форма профиля с сервера: наборы — в селектор с комнатой, остальное — напрямую. */
    private val serverRules = """
        {"action": "sniff"},
        {"type": "logical", "mode": "or", "rules": [{"protocol": "dns"}, {"port": 53}], "action": "hijack-dns"},
        {"outbound": "direct", "ip_is_private": true},
        {"outbound": "Соединение", "rule_set": ["telegram", "youtube"]},
        {"outbound": "direct", "rule_set": ["geoip-ru"]}
    """

    private fun rules(content: String): List<JSONObject> {
        val list = JSONObject(content).getJSONObject("route").getJSONArray("rules")
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun patch(content: String) = OlcRtcConfigPatch.rejectIpv6ToRoom(content, socksPort)

    /**
     * Живой матчер для этих конфигов: соединение с адресом [ip] и набором [sets], которым
     * он принадлежит. Возвращает действие первого подходящего правила: тег выхода,
     * «reject» или «final:…».
     */
    private fun route(content: String, ip: String, sets: Set<String>): String {
        fun leaf(rule: JSONObject): Boolean {
            val keys = rule.keys().asSequence().toSet() - setOf("outbound", "action")
            if (keys.isEmpty()) return false
            return keys.all { key ->
                when (key) {
                    "rule_set" -> strings(rule.getJSONArray(key)).any { it in sets }
                    "ip_cidr" -> strings(rule.getJSONArray(key)).any { it == "::/0" && ip.contains(':') }
                    "ip_is_private" -> false
                    "protocol", "port" -> false
                    else -> false
                }
            }
        }
        val root = JSONObject(content).getJSONObject("route")
        for (rule in rules(content)) {
            val matched = when {
                rule.optString("action") == "sniff" -> false
                rule.optString("type") == "logical" -> {
                    val inner = rule.getJSONArray("rules")
                    val parts = (0 until inner.length()).map { leaf(inner.getJSONObject(it)) }
                    if (rule.getString("mode") == "and") parts.all { it } else parts.any { it }
                }
                else -> leaf(rule)
            }
            if (!matched) continue
            return if (rule.optString("action") == "reject") "reject" else rule.getString("outbound")
        }
        return "final:" + root.getString("final")
    }

    // ------------------------------------------------------------ что меняется

    @Test
    fun `IPv6 телеграма в селектор с комнатой получает отказ`() {
        val patched = patch(config(serverRules))
        assertTrue(patched.note, patched.patched)
        assertEquals("reject", route(patched.content, telegramV6, setOf("telegram")))
    }

    @Test
    fun `IPv4 телеграма идёт в селектор, как раньше`() {
        val patched = patch(config(serverRules)).content
        assertEquals("Соединение", route(patched, "149.154.167.51", setOf("telegram")))
    }

    @Test
    fun `IPv6 на прямом правиле ниже по списку идёт напрямую — отказ не общий`() {
        val patched = patch(config(serverRules)).content
        assertEquals("direct", route(patched, "2a02:6b8::2:242", setOf("geoip-ru")))
    }

    @Test
    fun `final в селектор с комнатой — IPv6 без правила получает отказ последним`() {
        val content = config(serverRules, final = "Соединение")
        val patched = patch(content).content
        assertEquals("reject", route(patched, "2001:db8::1", emptySet()))
        assertEquals("final:Соединение", route(patched, "198.51.100.7", emptySet()))
        val last = rules(patched).last()
        assertEquals("reject", last.getString("action"))
        assertEquals(listOf("::/0"), strings(last.getJSONArray("ip_cidr")))
    }

    @Test
    fun `белый список — разрешённое напрямую остаётся выше отказа`() {
        // Правка белого списка дописывает прямые правила в конец и уводит final в селектор;
        // отказ IPv6 ставится после них, перед самим final.
        val whitelisted = OlcRtcConfigPatch.finalViaRoom(config(serverRules), socksPort, listOf("ozon.ru")).content
        val patched = rules(patch(whitelisted).content)
        val direct = patched.indexOfLast { it.optString("outbound") == "direct" && it.has("package_name") }
        val tail = patched.indexOfLast { it.optString("action") == "reject" && it.has("ip_cidr") }
        assertTrue("прямые правила белого списка должны стоять выше отказа: $direct < $tail", direct in 0 until tail)
        assertEquals(patched.size - 1, tail)
    }

    @Test
    fun `вложенное правило без действия — иначе ядро не примет конфиг`() {
        val guard = rules(patch(config(serverRules)).content).first { it.optString("type") == "logical" && it.optString("action") == "reject" }
        val inner = guard.getJSONArray("rules")
        for (i in 0 until inner.length()) {
            val rule = inner.getJSONObject(i)
            assertFalse("во вложенном правиле нет outbound: $rule", rule.has("outbound"))
            assertFalse("во вложенном правиле нет action: $rule", rule.has("action"))
        }
        assertEquals("and", guard.getString("mode"))
        assertEquals(listOf("telegram", "youtube"), strings(inner.getJSONObject(0).getJSONArray("rule_set")))
    }

    @Test
    fun `отказ стоит прямо перед правилом в комнату`() {
        val patched = rules(patch(config(serverRules)).content)
        val toRoom = patched.indexOfFirst { it.optString("outbound") == "Соединение" }
        val guard = patched[toRoom - 1]
        assertEquals("reject", guard.optString("action"))
        assertEquals("logical", guard.optString("type"))
    }

    // ------------------------------------------------------------ что не меняется

    @Test
    fun `правило прямо в сокс комнаты тоже охраняется`() {
        val content = config("""{"outbound": "Комната", "rule_set": ["telegram"]}""")
        assertEquals("reject", route(patch(content).content, telegramV6, setOf("telegram")))
    }

    @Test
    fun `повторная правка ничего не добавляет`() {
        val once = patch(config(serverRules, final = "Соединение"))
        val twice = patch(once.content)
        assertFalse(twice.note, twice.patched)
        assertEquals(once.content, twice.content)
    }

    @Test
    fun `комнаты в конфиге нет — конфиг не трогаем`() {
        val content = config(serverRules)
        assertFalse(OlcRtcConfigPatch.rejectIpv6ToRoom(content, socksPort + 1).patched)
        assertEquals(content, OlcRtcConfigPatch.rejectIpv6ToRoom(content, socksPort + 1).content)
    }

    @Test
    fun `в комнату ничего не ведёт — конфиг не трогаем`() {
        val content = config("""{"outbound": "direct", "rule_set": ["geoip-ru"]}""")
        val result = patch(content)
        assertFalse(result.patched)
        assertEquals(content, result.content)
    }

    @Test
    fun `не json — конфиг возвращается как есть`() {
        val result = patch("не json")
        assertFalse(result.patched)
        assertEquals("не json", result.content)
    }
}
