package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Узлы своей сети — резолвером hosts профиля (`svoi-imena`), без DNS сети. Набор узла в
 * sing-box 1.14.1 берёт `route.default_domain_resolver` мимо `dns.rules`, поэтому правило
 * «имена узлов → svoi-imena» в профиле само ядру не помогало.
 */
class UzlyBezDnsPatchTest {

    private val profil = """
        {
          "dns": {
            "servers": [
              {"tag": "local", "type": "local"},
              {"tag": "svoi-imena", "type": "hosts", "predefined": {
                "Node2kv.Example.org.": ["203.0.113.10"],
                "nodekv.example.org": "198.51.100.7",
                "subkv.example.org": ["203.0.113.20"],
                "blok.example.org": ["0.0.0.0"]
              }},
              {"tag": "fakeip", "type": "fakeip", "inet4_range": "198.18.0.0/15"}
            ],
            "rules": [{"domain": ["node2kv.example.org"], "server": "svoi-imena"}]
          },
          "route": {"default_domain_resolver": {"server": "local"}},
          "outbounds": [
            {"tag": "Соединение", "type": "selector", "outbounds": ["Нидерланды", "Финляндия"]},
            {"tag": "Нидерланды", "type": "urltest", "outbounds": ["Нидерланды · XHTTP"]},
            {"tag": "Финляндия", "type": "urltest", "outbounds": ["Финляндия · Hysteria2", "Финляндия · свой"]},
            {"tag": "Нидерланды · XHTTP", "type": "vless", "server": "node2kv.example.org", "server_port": 2053},
            {"tag": "Финляндия · Hysteria2", "type": "hysteria2", "server": "NODEKV.example.org", "server_port": 443},
            {"tag": "Финляндия · свой", "type": "vless", "server": "nodekv.example.org", "server_port": 2083, "domain_resolver": "local"},
            {"tag": "чужой", "type": "vless", "server": "drugoy.example.net", "server_port": 443},
            {"tag": "по адресу", "type": "vless", "server": "203.0.113.10", "server_port": 443},
            {"tag": "через посредника", "type": "vless", "server": "node2kv.example.org", "server_port": 443, "detour": "Нидерланды · XHTTP"},
            {"tag": "заблокированный", "type": "vless", "server": "blok.example.org", "server_port": 443},
            {"tag": "direct", "type": "direct"},
            {"tag": "Комната", "type": "socks", "server": "127.0.0.1", "server_port": 10808}
          ]
        }
    """.trimIndent()

    private fun vyhod(content: String, tag: String): JSONObject {
        val arr = JSONObject(content).getJSONArray("outbounds")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("tag") == tag }
    }

    @Test
    fun `узлам с именем из svoi-imena ставится резолвер hosts`() {
        val r = UzlyBezDnsPatch.apply(profil)
        assertTrue(r.patched)
        assertEquals(2, r.count)
        assertEquals("svoi-imena", vyhod(r.content, "Нидерланды · XHTTP").getString("domain_resolver"))
        // Регистр и точка в конце имени не важны.
        assertEquals("svoi-imena", vyhod(r.content, "Финляндия · Hysteria2").getString("domain_resolver"))
    }

    @Test
    fun `не трогаем свой резолвер, чужое имя, адрес, detour, нулевую запись, группы, direct, комнату`() {
        val r = UzlyBezDnsPatch.apply(profil)
        assertEquals("local", vyhod(r.content, "Финляндия · свой").getString("domain_resolver"))
        for (tag in listOf("чужой", "по адресу", "через посредника", "заблокированный", "Соединение", "Нидерланды", "direct", "Комната")) {
            assertFalse(tag, vyhod(r.content, tag).has("domain_resolver"))
        }
        // Резолвер по умолчанию (для direct) и правила DNS — как были.
        val root = JSONObject(r.content)
        assertEquals("local", root.getJSONObject("route").getJSONObject("default_domain_resolver").getString("server"))
        assertEquals(1, root.getJSONObject("dns").getJSONArray("rules").length())
    }

    @Test
    fun `профиль без hosts — как был`() {
        val bez = """{"dns":{"servers":[{"tag":"local","type":"local"}]},"outbounds":[{"tag":"x","type":"vless","server":"node2kv.example.org","server_port":443}]}"""
        val r = UzlyBezDnsPatch.apply(bez)
        assertFalse(r.patched)
        assertEquals(bez, r.content)
    }

    @Test
    fun `имени узла нет в hosts — как был`() {
        val bez = """{"dns":{"servers":[{"tag":"svoi-imena","type":"hosts","predefined":{"subkv.example.org":["203.0.113.20"]}}]},"outbounds":[{"tag":"x","type":"vless","server":"node2kv.example.org","server_port":443}]}"""
        val r = UzlyBezDnsPatch.apply(bez)
        assertFalse(r.patched)
        assertEquals(bez, r.content)
    }

    @Test
    fun `два hosts-сервера знают имя — берётся svoi-imena`() {
        val dva = """{"dns":{"servers":[
            {"tag":"drugoy","type":"hosts","predefined":{"a.example.org":["192.0.2.1"],"b.example.org":["192.0.2.2"]}},
            {"tag":"svoi-imena","type":"hosts","predefined":{"a.example.org":["192.0.2.9"]}}]},
            "outbounds":[
            {"tag":"a","type":"vless","server":"a.example.org","server_port":443},
            {"tag":"b","type":"trojan","server":"b.example.org","server_port":443}]}"""
        val r = UzlyBezDnsPatch.apply(dva)
        assertEquals("svoi-imena", vyhod(r.content, "a").getString("domain_resolver"))
        assertEquals("drugoy", vyhod(r.content, "b").getString("domain_resolver"))
        assertEquals(listOf("192.0.2.9"), UzlyBezDnsPatch.adresaImen(dva)["a.example.org"])
    }

    @Test
    fun `битый конфиг — как был, старт не роняем`() {
        val r = UzlyBezDnsPatch.apply("{не json")
        assertFalse(r.patched)
        assertEquals("{не json", r.content)
    }

    @Test
    fun `адреса имён для рукопожатия автомата — из профиля, нулевые отброшены`() {
        val a = UzlyBezDnsPatch.adresaImen(profil)
        assertEquals(listOf("203.0.113.10"), a["node2kv.example.org"])
        assertEquals(listOf("198.51.100.7"), a["nodekv.example.org"])
        assertFalse(a.containsKey("blok.example.org"))
    }

    @Test
    fun `раскладка автомата несёт адреса своих имён`() {
        val layout = AutoModeExits.parse(profil, socksPort = 10808)
        assertEquals("Нидерланды", layout.main)
        assertEquals(listOf("203.0.113.10"), layout.imena["node2kv.example.org"])
        // Узел очереди — та же карта имён.
        assertEquals(layout.imena, layout.forNode("Финляндия").imena)
    }

    @Test
    fun `литерал адреса — без DNS`() {
        assertTrue(UzlyBezDnsPatch.adres("203.0.113.10"))
        assertTrue(UzlyBezDnsPatch.adres("2001:db8::1"))
        assertFalse(UzlyBezDnsPatch.adres("node2kv.example.org"))
        assertFalse(UzlyBezDnsPatch.adres("300.1.1.1"))
    }
}
