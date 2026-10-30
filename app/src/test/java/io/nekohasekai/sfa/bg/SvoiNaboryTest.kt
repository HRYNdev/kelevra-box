package io.nekohasekai.sfa.bg

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Свои наборы правил (`svoi-tunnel`, `svoi-mimo`, `svoi-blok`, `svoi-razreshit`; решение
 * 25.10.2026 «одна истина»): сервер отдаёт их удалённо в текстовом виде (`format: source`) с
 * интервалом 2 минуты. Проверяем, что кэш их понимает, свежесть у них своя, страховка не
 * уходит напрямую из-за правила «напрямую» выше туннеля, а «российское» под белым списком
 * берётся из профиля.
 */
class SvoiNaboryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tunnel = "Соединение"

    /** Профиль в форме выдачи после «одной истины»: блок → реклама → напрямую → туннель. */
    private fun profil(mimoRule: String, tunnelSets: String = """["svoi-tunnel", "svoi-tunnel-2", "main-domains", "telegram"]"""): String =
        JSONObject(
            """
            {
              "inbounds": [{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2412, "users": []}],
              "outbounds": [
                {"type": "selector", "tag": "$tunnel", "outbounds": ["Нидерланды"]},
                {"type": "urltest", "tag": "Нидерланды", "outbounds": ["v"]},
                {"type": "vless", "tag": "v", "server": "203.0.113.1", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {
                "rules": [
                  {"action": "sniff"},
                  {"action": "reject", "rule_set": ["svoi-blok", "svoi-blok-2"]},
                  {"action": "reject", "rule_set": ["ads"]},
                  $mimoRule,
                  {"outbound": "$tunnel", "rule_set": $tunnelSets}
                ],
                "rule_set": [
                  ${remote("svoi-tunnel")}, ${remote("svoi-tunnel-2")}, ${remote("svoi-mimo")},
                  ${remote("svoi-blok")}, ${remote("svoi-blok-2")}, ${remote("svoi-razreshit")},
                  {"tag": "ads", "type": "remote", "format": "binary", "url": "https://subkv.chickenkiller.com/rules/ads.srs", "update_interval": "1h"},
                  {"tag": "main-domains", "type": "remote", "format": "binary", "url": "https://subkv.chickenkiller.com/rules/main-domains.srs", "update_interval": "1h"}
                ],
                "final": "direct"
              }
            }
            """.trimIndent(),
        ).toString()

    private fun remote(tag: String) =
        """{"tag": "$tag", "type": "remote", "format": "source", "url": "https://node2kv.chickenkiller.com/u/k/$tag", "update_interval": "2m", "http_client": {"detour": "direct"}}"""

    private val mimoBezAction = """{"outbound": "direct", "rule_set": ["svoi-mimo"]}"""
    private val mimoRoute = """{"action": "route", "outbound": "direct", "rule_set": ["svoi-mimo"]}"""

    private fun lifelineOutbounds(content: String): List<String> {
        val rules = JSONObject(content).getJSONObject("route").getJSONArray("rules")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
            .filter { r -> r.has("domain_suffix") && r.getJSONArray("domain_suffix").toString().contains("chickenkiller.com") || r.optJSONArray("ip_cidr")?.toString()?.contains("91.108.4.0/22") == true }
            .map { it.optString("outbound") }
    }

    private fun source(name: String, json: String): File = tmp.newFile(name).apply { writeText(json) }

    // ---- страховка (LifelinePatch) ----

    @Test
    fun `правило напрямую без action выше туннеля страховку не уводит`() {
        val result = LifelinePatch.addLifeline(profil(mimoBezAction))
        assertTrue(result.note, result.patched)
        assertEquals(listOf(tunnel, tunnel), lifelineOutbounds(result.content))
    }

    @Test
    fun `правило напрямую с action route — страховка тоже в туннель`() {
        val result = LifelinePatch.addLifeline(profil(mimoRoute))
        assertTrue(result.patched)
        assertEquals(listOf(tunnel, tunnel), lifelineOutbounds(result.content))
    }

    @Test
    fun `туннель только на своих наборах — узнаётся по svoi-tunnel`() {
        val result = LifelinePatch.addLifeline(profil(mimoBezAction, tunnelSets = """["svoi-tunnel-2"]"""))
        assertEquals(listOf(tunnel, tunnel), lifelineOutbounds(result.content))
    }

    @Test
    fun `туннель без узнаваемых наборов — первое правило не в прямой выход`() {
        val result = LifelinePatch.addLifeline(profil(mimoBezAction, tunnelSets = """["youtube"]"""))
        assertEquals(listOf(tunnel, tunnel), lifelineOutbounds(result.content))
    }

    @Test
    fun `только правило напрямую — страховку не ставим совсем`() {
        val config = JSONObject(profil(mimoBezAction)).apply {
            val rules = getJSONObject("route").getJSONArray("rules")
            rules.remove(rules.length() - 1)
        }.toString()
        val result = LifelinePatch.addLifeline(config)
        assertFalse("напрямую страховку вести нельзя", result.patched)
        assertEquals(config, result.content)
    }

    @Test
    fun `нынешний профиль сервера — выход тот же, что раньше`() {
        val config = javaClass.classLoader!!.getResourceAsStream("config-server.json")!!.readBytes().toString(Charsets.UTF_8)
        val result = LifelinePatch.addLifeline(config)
        assertTrue(result.patched)
        assertEquals(listOf(tunnel, tunnel), lifelineOutbounds(result.content))
    }

    @Test
    fun `страховка не отклоняет IPv6 Телеграма`() {
        val result = LifelinePatch.addLifeline(profil(mimoBezAction))
        assertTrue(result.patched)
        val rules = JSONObject(result.content).getJSONObject("route").getJSONArray("rules")
        val v6 = (0 until rules.length()).map { rules.getJSONObject(it) }
            .filter { it.optJSONArray("ip_cidr")?.toString()?.contains("2001:67c:4e8::/48") == true }
        assertEquals("v6-сети Телеграма страховка не трогает", emptyList<JSONObject>(), v6)
    }

    // ---- подмена наборов кэшем (RuleSetLocalPatch) ----

    @Test
    fun `интервалы sing-box разбираются`() {
        assertEquals(120_000L, RuleSetLocalPatch.durationMillis("2m"))
        assertEquals(3_600_000L, RuleSetLocalPatch.durationMillis("1h"))
        assertEquals(5_400_000L, RuleSetLocalPatch.durationMillis("1h30m"))
        assertEquals(90_000L, RuleSetLocalPatch.durationMillis("90s"))
        assertEquals(86_400_000L, RuleSetLocalPatch.durationMillis("1d"))
        assertEquals(0L, RuleSetLocalPatch.durationMillis(""))
        assertEquals(0L, RuleSetLocalPatch.durationMillis("две минуты"))
        assertEquals(0L, RuleSetLocalPatch.durationMillis("2m junk"))
    }

    @Test
    fun `текстовые наборы из кэша уходят в ядро локальными с форматом source`() {
        val cached = mapOf(
            "svoi-tunnel" to source("svoi-tunnel.srs", """{"version":2,"rules":[{"domain_suffix":["example.org"]}]}"""),
            "svoi-mimo" to source("svoi-mimo.srs", """{"version":2,"rules":[{"domain_suffix":["ozon.ru"]}]}"""),
        )
        val result = RuleSetLocalPatch.useCached(profil(mimoRoute), cached)
        assertTrue(result.note, result.patched)
        val sets = JSONObject(result.content).getJSONObject("route").getJSONArray("rule_set")
        val byTag = (0 until sets.length()).map { sets.getJSONObject(it) }.associateBy { it.getString("tag") }
        for (tag in listOf("svoi-tunnel", "svoi-mimo")) {
            assertEquals("local", byTag.getValue(tag).getString("type"))
            assertEquals("source", byTag.getValue(tag).getString("format"))
            assertFalse(byTag.getValue(tag).has("update_interval"))
        }
        assertEquals("remote", byTag.getValue("svoi-blok").getString("type"))
        val remotes = result.remotes.associateBy { it.tag }
        assertEquals(120_000L, remotes.getValue("svoi-tunnel").updateMillis)
        assertEquals(3_600_000L, remotes.getValue("ads").updateMillis)
    }

    @Test
    fun `файл в кэше другого вида, чем набор в профиле, — набор остаётся удалённым`() {
        val binary = tmp.newFile("svoi-tunnel.srs").apply { writeBytes(byteArrayOf(0x53, 0x52, 0x53, 1, 0, 0)) }
        val result = RuleSetLocalPatch.useCached(profil(mimoRoute), mapOf("svoi-tunnel" to binary))
        assertFalse("ядро не разобрало бы бинарный файл как source", result.patched)
        assertTrue("svoi-tunnel" in result.missing)
    }

    @Test
    fun `текстового набора нет в кэше — ядро получает заглушку, а не удалённый набор`() {
        // Ядро 1.14 без своего хранилища не стартует, если удалённый набор не скачался
        // («initial rule-set», эмулятор 25.10.2026) — под белым списком так и было бы.
        val made = mutableListOf<String>()
        val result = RuleSetLocalPatch.useCached(profil(mimoRoute), emptyMap()) { remote ->
            made += remote.tag
            source("${remote.tag}.srs", """{"version":2,"rules":[{"domain_suffix":["${RuleSetCache.ZAGLUSHKA_DOMEN}"]}]}""")
        }
        assertTrue(result.note, result.patched)
        assertEquals("заглушки только текстовым наборам", listOf("svoi-tunnel", "svoi-tunnel-2", "svoi-mimo", "svoi-blok", "svoi-blok-2", "svoi-razreshit"), made)
        assertEquals(made, result.placeholders)
        assertTrue("заглушка — не «правила загружены»", result.ready.isEmpty())
        assertEquals(listOf("ads", "main-domains"), result.missing)
        val sets = JSONObject(result.content).getJSONObject("route").getJSONArray("rule_set")
        val byTag = (0 until sets.length()).map { sets.getJSONObject(it) }.associateBy { it.getString("tag") }
        assertEquals("local", byTag.getValue("svoi-tunnel").getString("type"))
        assertEquals("remote", byTag.getValue("ads").getString("type"))
    }

    @Test
    fun `заглушка с прошлого запуска узнаётся и не считается загруженной`() {
        val z = source("svoi-mimo.srs", """{"version":2,"rules":[{"domain_suffix":["${RuleSetCache.ZAGLUSHKA_DOMEN}"]}]}""")
        val real = source("svoi-tunnel.srs", """{"version":2,"rules":[{"domain_suffix":["example.org"]}]}""")
        assertTrue(RuleSetCache.isZaglushka(z))
        assertFalse(RuleSetCache.isZaglushka(real))
        val result = RuleSetLocalPatch.useCached(profil(mimoRoute), mapOf("svoi-mimo" to z, "svoi-tunnel" to real))
        assertEquals(listOf("svoi-tunnel"), result.ready)
        assertEquals(listOf("svoi-mimo"), result.placeholders)
    }

    // ---- признак ML-KEM и ядро без него (MlkemPriznak) ----

    private val sPriznakom = """{"outbounds":[{"type":"vless","tag":"v","tls":{"reality":{"enabled":true,"support_x25519mlkem768":true}}},{"type":"direct","tag":"direct"}]}"""

    @Test
    fun `ядро не знает признак — конфиг без него, остальное как было`() {
        val out = JSONObject(MlkemPriznak.dlyaYadra(sPriznakom) { false })
        val reality = out.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("reality")
        assertFalse(reality.has("support_x25519mlkem768"))
        assertTrue(reality.getBoolean("enabled"))
    }

    @Test
    fun `ядро знает признак или его нет — конфиг дословно`() {
        assertEquals(sPriznakom, MlkemPriznak.dlyaYadra(sPriznakom) { true })
        val bez = """{"outbounds":[]}"""
        assertEquals(bez, MlkemPriznak.dlyaYadra(bez) { error("ядро спрашивать незачем") })
    }

    @Test
    fun `ошибка ядра про незнакомый признак узнаётся`() {
        assertTrue(MlkemPriznak.neZnaet("decode config: outbounds[5].tls.reality.support_x25519mlkem768: json: unknown field \"support_x25519mlkem768\""))
        assertFalse(MlkemPriznak.neZnaet("decode config: invalid public_key"))
        assertFalse(MlkemPriznak.neZnaet(null))
    }

    // ---- свой кэш (RuleSetCache) ----

    @Test
    fun `вид файла определяется по содержимому`() {
        assertEquals("binary", RuleSetCache.kindOf(tmp.newFile("a").apply { writeBytes(byteArrayOf(0x53, 0x52, 0x53, 1, 2)) }))
        assertEquals("source", RuleSetCache.kindOf(source("b", """{"version":2,"rules":[]}""")))
        assertEquals("source", RuleSetCache.kindOf(source("c", "﻿\n {\"version\":2,\"rules\":[{\"ip_cidr\":[\"1.2.3.0/24\"]}]}")))
        assertNull("ответ с ошибкой — не набор", RuleSetCache.kindOf(source("d", """{"error":"not found"}""")))
        assertNull("страница-заглушка — не набор", RuleSetCache.kindOf(source("e", "<html>blocked</html>")))
        assertNull("обрезанный JSON — не набор", RuleSetCache.kindOf(source("f", """{"version":2,"rules":[{"domain""")))
    }

    @Test
    fun `живые наборы свежи две минуты без запаса, большие — сутки`() {
        val svoi = RuleSetLocalPatch.Remote("svoi-tunnel", "u", "source", 120_000L)
        val ads = RuleSetLocalPatch.Remote("ads", "u", "binary", 3_600_000L)
        val old = RuleSetLocalPatch.Remote("x", "u", "binary")
        assertEquals(90_000L, RuleSetCache.freshFor(svoi))
        assertEquals(24L * 3_600_000L, RuleSetCache.freshFor(ads))
        assertEquals(24L * 3_600_000L, RuleSetCache.freshFor(old))
        assertEquals("чаще минуты не качаем", 45_000L, RuleSetCache.freshFor(svoi.copy(updateMillis = 10_000L)))
        assertEquals(listOf(svoi), RuleSetCache.shortLived(listOf(svoi, ads, old)))
        assertEquals(120_000L, RuleSetCache.shortPeriod(listOf(ads, svoi)))
        assertEquals(0L, RuleSetCache.shortPeriod(listOf(ads, old)))
    }

    @Test
    fun `файл живого набора протухает через полторы минуты`() {
        val f = source("svoi-mimo.srs", """{"version":2,"rules":[]}""")
        val now = f.lastModified()
        assertFalse(RuleSetCache.stale(f, now + 60_000L, 90_000L))
        assertTrue(RuleSetCache.stale(f, now + 100_000L, 90_000L))
        assertFalse("большой набор свеж сутки", RuleSetCache.stale(f, now + 100_000L))
    }

    @Test
    fun `домены из текстового набора читаются`() {
        val f = source("svoi-pri-belom.srs", """{"version":2,"rules":[{"domain_suffix":["ozon.ru","wb.ru"]},{"domain":["x.vk.com"]},{"ip_cidr":["1.2.3.0/24"]}]}""")
        assertEquals(listOf("ozon.ru", "wb.ru", "x.vk.com"), RuleSetCache.sourceDomains(f))
        assertNull(RuleSetCache.sourceDomains(tmp.newFile("bin").apply { writeBytes(byteArrayOf(0x53, 0x52, 0x53, 1, 2)) }))
    }

    // ---- «российское» под белым списком (BelyeDobavki) ----

    private fun sBelym(): String = JSONObject(profil(mimoRoute)).apply {
        val sets = getJSONObject("route").getJSONArray("rule_set")
        sets.put(JSONObject(remote("svoi-pri-belom")))
        sets.put(JSONObject(remote("svoi-pri-belom-2")))
    }.toString()

    @Test
    fun `набора в профиле нет — вшитый список`() {
        val itog = BelyeDobavki.iz(profil(mimoRoute), emptyMap())
        assertFalse(itog.izProfilya)
        assertEquals(OlcRtcConfigPatch.DOBAVKI_DOMENOV, itog.domeny)
    }

    @Test
    fun `набор в профиле, но ещё не скачан — вшитый список`() {
        val itog = BelyeDobavki.iz(sBelym(), emptyMap())
        assertFalse(itog.izProfilya)
        assertEquals(OlcRtcConfigPatch.DOBAVKI_DOMENOV, itog.domeny)
    }

    @Test
    fun `набор скачан — берём из профиля, обе двери вместе`() {
        val cached = mapOf(
            "svoi-pri-belom" to source("p1", """{"version":2,"rules":[{"domain_suffix":["ozon.ru","*.wb.ru","Vk.com."]}]}"""),
            "svoi-pri-belom-2" to source("p2", """{"version":2,"rules":[{"domain_suffix":["ozon.ru","sber.ru"]}]}"""),
        )
        val itog = BelyeDobavki.iz(sBelym(), cached)
        assertTrue(itog.note, itog.izProfilya)
        assertEquals(listOf("ozon.ru", "wb.ru", "vk.com", "sber.ru"), itog.domeny)
    }

    @Test
    fun `пустой набор выдачи (заглушка) — вшитый список`() {
        val cached = mapOf("svoi-pri-belom" to source("p", """{"version":2,"rules":[{"domain_suffix":["svoi.invalid"]}]}"""))
        val itog = BelyeDobavki.iz(sBelym(), cached)
        assertFalse(itog.izProfilya)
        assertEquals(OlcRtcConfigPatch.DOBAVKI_DOMENOV, itog.domeny)
    }

    @Test
    fun `под белым списком добавки из профиля заменяют вшитые`() {
        val config = JSONObject(
            """
            {"route": {"rules": [{"outbound": "Соединение", "rule_set": ["telegram"]}], "final": "direct"},
             "outbounds": [
               {"type": "selector", "tag": "Соединение", "outbounds": ["Нидерланды", "Комната"]},
               {"type": "urltest", "tag": "Нидерланды", "outbounds": ["v"]},
               {"type": "direct", "tag": "direct"},
               {"type": "vless", "tag": "v", "server": "203.0.113.1", "server_port": 443},
               {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": 2409}
             ]}
            """.trimIndent(),
        ).toString()
        val content = OlcRtcConfigPatch.finalViaRoom(config, 2409, listOf("gosuslugi.ru"), dobavki = listOf("novoe.ru")).content
        val rules = JSONObject(content).getJSONObject("route").getJSONArray("rules")
        val suffixes = (0 until rules.length()).map { rules.getJSONObject(it) }
            .first { it.has("domain_suffix") }.getJSONArray("domain_suffix")
        val list = (0 until suffixes.length()).map { suffixes.getString(it) }
        assertEquals(listOf("gosuslugi.ru", "novoe.ru"), list)
        assertFalse("вшитое не подмешивается", "wbbasket.ru" in list)
        // Без явных добавок — как раньше, вшитые.
        val old = OlcRtcConfigPatch.razreshyonnyeDomeny(listOf("gosuslugi.ru"))
        assertTrue("wbbasket.ru" in old)
        assertEquals(JSONArray(old).length(), old.size)
    }
}
