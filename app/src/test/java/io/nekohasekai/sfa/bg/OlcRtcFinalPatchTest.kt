package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Проверки правки «под белым списком разрешённое напрямую, остальное — через комнату».
 *
 * Конфиг повторяет форму профиля с сервера: `final: direct`, наборы уводят в селектор,
 * комната — сокс на петле внутри селектора.
 */
class OlcRtcFinalPatchTest {

    private val socksPort = 2409

    private val spisok = listOf("ozon.ru", "adv.ozon.ru", "gosuslugi.ru", "sun1-13.userapi.com", "ya.ru")

    private val baseRules = """
        {"action": "sniff"},
        {"type": "logical", "mode": "or", "rules": [{"protocol": "dns"}, {"port": 53}], "action": "hijack-dns"},
        {"outbound": "direct", "ip_is_private": true},
        {"action": "reject", "rule_set": ["ads"]},
        {"action": "reject", "network": "udp", "port": 443},
        {"outbound": "Соединение", "rule_set": ["telegram", "youtube"]}
    """

    private fun config(rules: String = baseRules, withRoom: Boolean = true, withDirect: Boolean = true): String {
        val room = if (withRoom) {
            """,{"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": $socksPort, "version": "5"}"""
        } else {
            ""
        }
        val direct = if (withDirect) """{"type": "direct", "tag": "direct"},""" else ""
        val members = if (withRoom) """["Нидерланды", "Комната"]""" else """["Нидерланды"]"""
        return JSONObject(
            """
            {
              "route": {
                "rules": [$rules],
                "rule_set": [{"tag": "telegram", "type": "local", "format": "binary", "path": "/x/telegram.srs"}],
                "final": "direct"
              },
              "outbounds": [
                {"type": "selector", "tag": "Соединение", "outbounds": $members, "default": "Нидерланды"},
                {"type": "urltest", "tag": "Нидерланды", "outbounds": ["vless-1"]},
                $direct
                {"type": "vless", "tag": "vless-1", "server": "203.0.113.1", "server_port": 443}
                $room
              ]
            }
            """.trimIndent(),
        ).toString()
    }

    private fun patch(content: String = config(), domeny: List<String> = spisok) =
        OlcRtcConfigPatch.finalViaRoom(content, socksPort, domeny)

    private fun route(content: String): JSONObject = JSONObject(content).getJSONObject("route")

    private fun rules(content: String): List<JSONObject> {
        val list = route(content).getJSONArray("rules")
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun keys(rule: JSONObject): Set<String> = rule.keys().asSequence().toSet()

    /** Первое правило, которое поймает TLS-соединение с этим именем, — как в ядре, сверху вниз. */
    private fun firstForDomain(rules: List<JSONObject>, host: String): JSONObject? = rules.firstOrNull { rule ->
        val suffixes = rule.optJSONArray("domain_suffix") ?: return@firstOrNull false
        strings(suffixes).any { host == it || host.endsWith(".$it") }
    }

    // ------------------------------------------------------------------ когда править

    @Test
    fun `комнату выбрал человек при выключенном автомате — правим, даже без вердикта`() {
        assertTrue(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = false, manualRoom = true, whitelistAgeMillis = null, ttlMillis = 300_000))
    }

    @Test
    fun `свежий вердикт «белый список» — правим`() {
        assertTrue(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = true, manualRoom = false, whitelistAgeMillis = 10_000, ttlMillis = 300_000))
    }

    @Test
    fun `обычная сеть — конфиг не трогаем`() {
        assertFalse(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = true, manualRoom = false, whitelistAgeMillis = null, ttlMillis = 300_000))
        assertFalse(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = false, manualRoom = false, whitelistAgeMillis = null, ttlMillis = 300_000))
        // Флажок ручной комнаты при включённом автомате — остаток прошлого выбора, не довод.
        assertFalse(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = true, manualRoom = true, whitelistAgeMillis = null, ttlMillis = 300_000))
    }

    @Test
    fun `протухший вердикт — не правим`() {
        assertFalse(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = true, manualRoom = false, whitelistAgeMillis = 300_000, ttlMillis = 300_000))
        assertFalse(OlcRtcConfigPatch.wantsFinalViaRoom(autoMode = true, manualRoom = false, whitelistAgeMillis = -1, ttlMillis = 300_000))
    }

    // ------------------------------------------------------------------ порядок

    @Test
    fun `всё, что было в конфиге, остаётся на своих местах и выше наших правил`() {
        val before = rules(config())
        val after = rules(patch().content)
        assertEquals(before.map { it.toString() }, after.take(before.size).map { it.toString() })
    }

    @Test
    fun `порядок — домены, приложения, отказ прочему UDP, и всё это последним`() {
        val before = rules(config()).size
        val added = rules(patch().content).drop(before)
        assertEquals(3, added.size)
        assertEquals(setOf("outbound", "domain_suffix"), keys(added[0]))
        assertEquals(setOf("outbound", "package_name"), keys(added[1]))
        assertEquals(setOf("action", "network"), keys(added[2]))
        assertEquals("reject", added[2].getString("action"))
        assertEquals("udp", added[2].getString("network"))
    }

    @Test
    fun `домены и приложения — отдельные правила в прямой выход`() {
        val after = rules(patch().content)
        val domains = after.single { it.has("domain_suffix") }
        val packages = after.single { it.has("package_name") }
        assertEquals("direct", domains.getString("outbound"))
        assertEquals("direct", packages.getString("outbound"))
        assertFalse("поля в одном правиле сложились бы через «И»", domains.has("package_name"))
        assertEquals(OlcRtcConfigPatch.PAKETY_RAZRESHYONNYE, strings(packages.getJSONArray("package_name")))
    }

    @Test
    fun `подсетей не дали — правила по адресам нет`() {
        val added = rules(patch().content).drop(rules(config()).size)
        assertTrue(added.none { it.has("ip_cidr") || it.optString("type") == "logical" })
    }

    private val podseti = listOf("5.255.255.0/24", "185.73.193.0/24")

    /**
     * Живой матчер поверх сгенерированных правил: тот же порядок и те же поля
     * (network/ip_cidr, domain_suffix, domain_regex+invert, port+invert, package_name,
     * reject udp), какими их строит [OlcRtcConfigPatch]. Отвечает — уходит ли TCP-соединение
     * с данным именем (`null` — пустое, распознать не удалось) и портом напрямую, минуя
     * комнату, до того как дойдёт до `final`.
     */
    private fun napryamuyu(added: List<JSONObject>, direct: String, ip: String, port: Int, host: String?): Boolean {
        fun leaf(rule: JSONObject): Boolean {
            rule.optJSONArray("ip_cidr")?.let { cidr ->
                val hit = strings(cidr).any { net ->
                    val prefix = net.substringBefore('/').substringBeforeLast('.')
                    ip.startsWith("$prefix.")
                }
                return hit != rule.optBoolean("invert", false)
            }
            rule.optJSONArray("domain_regex")?.let {
                return (host != null) != rule.optBoolean("invert", false)
            }
            rule.optJSONArray("port")?.let {
                val hit = (0 until it.length()).any { i -> it.getInt(i) == port }
                return hit != rule.optBoolean("invert", false)
            }
            return false
        }
        for (rule in added) {
            val matched = when {
                rule.optString("type") == "logical" -> {
                    val inner = rule.getJSONArray("rules")
                    (0 until inner.length()).all { leaf(inner.getJSONObject(it)) }
                }
                rule.has("domain_suffix") -> host != null && strings(rule.getJSONArray("domain_suffix")).any { host == it || host.endsWith(".$it") }
                else -> false
            }
            if (matched) return rule.optString("outbound") == direct
        }
        return false
    }

    @Test
    fun `подсети дали, но правила по адресу больше нет — второго списка портов не заводим`() {
        val before = rules(config()).size
        val added = rules(OlcRtcConfigPatch.finalViaRoom(config(), socksPort, spisok, podseti).content).drop(before)
        assertEquals(3, added.size)
        assertTrue(added.none { it.has("ip_cidr") || it.optString("type") == "logical" || it.has("port") })
    }

    @Test
    fun `нераспознанное имя к живой подсети на 8443 уходит в комнату — было наоборот, порт значения не имеет`() {
        val added = rules(OlcRtcConfigPatch.finalViaRoom(config(), socksPort, spisok, podseti).content)
        assertFalse(
            "8443 не входил в старый PORTY_S_IMENEM=[80,443] — трафик утекал напрямую",
            napryamuyu(added, "direct", "5.255.255.7", 8443, host = null),
        )
    }

    @Test
    fun `пустое имя на 443 по-прежнему уходит в комнату — #16 не сломан`() {
        val added = rules(OlcRtcConfigPatch.finalViaRoom(config(), socksPort, spisok, podseti).content)
        assertFalse(napryamuyu(added, "direct", "5.255.255.7", 443, host = null))
    }

    @Test
    fun `распознанное разрешённое имя по-прежнему уходит напрямую — правка не выключает белый список`() {
        val added = rules(OlcRtcConfigPatch.finalViaRoom(config(), socksPort, spisok, podseti).content)
        assertTrue(napryamuyu(added, "direct", "203.0.113.9", 443, host = "adv.ozon.ru"))
        assertTrue(napryamuyu(added, "direct", "203.0.113.9", 8443, host = "adv.ozon.ru"))
    }

    @Test
    fun `с подсетями повторная правка тоже ничего не добавляет`() {
        val once = OlcRtcConfigPatch.finalViaRoom(config(), socksPort, spisok, podseti)
        val twice = OlcRtcConfigPatch.finalViaRoom(once.content, socksPort, spisok, podseti)
        assertFalse(twice.patched)
    }

    @Test
    fun `final уходит в селектор с комнатой`() {
        val result = patch()
        assertTrue(result.note, result.patched)
        assertEquals("Соединение", route(result.content).getString("final"))
    }

    @Test
    fun `отказ по QUIC уже стоит выше — второй не добавляем`() {
        val after = rules(patch().content)
        assertEquals(1, after.count { it.optString("action") == "reject" && it.optInt("port") == 443 })
    }

    @Test
    fun `отказа по QUIC нет — ставим его выше разрешённого`() {
        val plain = config(
            rules = """
                {"action": "sniff"},
                {"outbound": "Соединение", "rule_set": ["telegram", "youtube"]}
            """,
        )
        val after = rules(patch(plain).content)
        val reject = after.indexOfFirst { it.optString("action") == "reject" && it.optInt("port") == 443 }
        val domains = after.indexOfFirst { it.has("domain_suffix") }
        assertTrue("отказа по QUIC нет", reject >= 0)
        assertTrue("QUIC к разрешённому ушёл бы напрямую и висел", reject < domains)
    }

    // ------------------------------------------------------------------ состав

    @Test
    fun `добавки CDN входят, поддомены свёрнуты родителем`() {
        val suffixes = strings(rules(patch().content).single { it.has("domain_suffix") }.getJSONArray("domain_suffix"))
        assertTrue("wbbasket.ru" in suffixes)
        assertTrue("ozonusercontent.com" in suffixes)
        assertTrue("gosuslugi.ru" in suffixes)
        assertFalse("adv.ozon.ru накрыт ozon.ru", "adv.ozon.ru" in suffixes)
        assertFalse("sun1-13.userapi.com накрыт добавкой userapi.com", "sun1-13.userapi.com" in suffixes)
        assertEquals(suffixes.size, suffixes.toSet().size)
    }

    @Test
    fun `список не прочитался — добавки и приложения всё равно напрямую`() {
        val after = rules(patch(domeny = emptyList()).content)
        val suffixes = strings(after.single { it.has("domain_suffix") }.getJSONArray("domain_suffix"))
        assertTrue("wbbasket.ru" in suffixes)
        assertTrue(after.any { it.has("package_name") })
    }

    // ------------------------------------------------------------------ комната умерла

    @Test
    fun `комната умерла — разрешённое остаётся напрямую, остаток идёт за селектором`() {
        val patched = patch().content
        // Так делает AutoMode.roomLost: переключает селектор на основной канал, ядро не пересобирается.
        val root = JSONObject(patched)
        val outbounds = root.getJSONArray("outbounds")
        (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .single { it.optString("tag") == "Соединение" }
            .put("default", "Нидерланды")
        val after = rules(root.toString())

        for (host in listOf("cdn1.wbbasket.ru", "www.ozon.ru", "esia.gosuslugi.ru")) {
            val rule = firstForDomain(after, host)
            assertNotNull("$host не пойман разрешённым", rule)
            assertEquals("$host ушёл бы за границу", "direct", rule!!.getString("outbound"))
        }
        val selectorOrRoom = setOf("Соединение", "Комната", "Нидерланды")
        val added = after.drop(rules(config()).size)
        assertTrue("разрешённое не должно зависеть от селектора", added.none { it.optString("outbound") in selectorOrRoom })
        assertEquals("Соединение", route(root.toString()).getString("final"))
    }

    // ------------------------------------------------------------------ не трогаем

    @Test
    fun `повторная правка ничего не добавляет`() {
        val once = patch()
        val twice = patch(once.content)
        assertFalse(twice.patched)
        assertEquals(once.content, twice.content)
    }

    @Test
    fun `комнаты в конфиге нет — конфиг не трогаем`() {
        val content = config(withRoom = false)
        val result = patch(content)
        assertFalse(result.patched)
        assertEquals(content, result.content)
    }

    @Test
    fun `сокс на другом порту — это не комната`() {
        assertFalse(OlcRtcConfigPatch.finalViaRoom(config(), socksPort + 1, spisok).patched)
    }

    @Test
    fun `прямого выхода нет — не трогаем, иначе разрешённое ушло бы в звонок`() {
        val content = config(withDirect = false)
        val result = patch(content)
        assertFalse(result.patched)
        assertEquals(content, result.content)
    }

    @Test
    fun `комната без селектора — final прямо в её сокс`() {
        val content = JSONObject(
            """
            {
              "route": {"rules": [], "final": "direct"},
              "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": $socksPort}
              ]
            }
            """.trimIndent(),
        ).toString()
        assertEquals("Комната", route(patch(content).content).getString("final"))
    }

    @Test
    fun `не json — конфиг возвращается как есть`() {
        val result = patch("не json")
        assertFalse(result.patched)
        assertEquals("не json", result.content)
    }

    @Test
    fun `входы для пробы после правки по-прежнему первые`() {
        val root = JSONObject(patch().content)
        root.put("inbounds", JSONArray())
        val probe = ProbeInboundPatch.addProbeInbounds(root.toString(), listOf("Нидерланды"))
        if (!probe.patched) return // свободного порта в песочнице может не быть — проверять нечего
        val first = rules(probe.content).first()
        assertTrue("правило входа для пробы должно стоять первым: $first", first.has("inbound"))
    }

    // ------------------------------------------------------------------ вшитый список

    @Test
    fun `вшитый список читается и в нём есть разрешённое`() {
        val file = File("src/main/assets/belyj-spisok/domeny.txt")
        assertTrue("нет ${file.absolutePath}", file.isFile)
        val domeny = BelyjSpisok.razobrat(file.readText())
        assertTrue("список подозрительно мал: ${domeny.size}", domeny.size > 300)
        assertTrue(domeny.none { it.startsWith("#") || it.isBlank() })
        for (host in listOf("ozon.ru", "wb.ru", "gosuslugi.ru", "yandex.ru")) {
            assertTrue("$host нет в списке", host in domeny)
        }
    }

    @Test
    fun `вшитые подсети короткие, корректные и с комнатой WB`() {
        val file = File("src/main/assets/belyj-spisok/podseti.txt")
        assertTrue("нет ${file.absolutePath}", file.isFile)
        val podseti = BelyjSpisok.razobrat(file.readText())
        assertTrue("набор должен быть коротким, а не снимком на 30 тыс.: ${podseti.size}", podseti.size in 100..2000)
        val cidr = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")
        for (net in podseti) {
            val m = cidr.matchEntire(net)
            assertNotNull("не подсеть: $net", m)
            assertTrue("$net: октеты", m!!.groupValues.subList(1, 5).all { it.toInt() in 0..255 })
            assertTrue("$net: шире /16 — подозрительно для живого замера", m.groupValues[5].toInt() in 16..32)
        }
        assertEquals(podseti.size, podseti.toSet().size)
        for (net in listOf("91.230.107.0/24", "185.62.202.0/24")) {
            assertTrue("$net (комната WB) нет в наборе", net in podseti)
        }
    }
}
