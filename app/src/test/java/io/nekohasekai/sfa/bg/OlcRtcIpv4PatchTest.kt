package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки правки «через комнату идём только по IPv4».
 *
 * Главная из них — последняя: распознавание протокола остаётся на месте. 11.08.2026 я
 * снял его целиком, и трафик из туннеля перестал доходить до выхода вообще: домен брать
 * стало неоткуда, правило с доменными наборами не досчитывалось, соединения зависали
 * навсегда (263 тысячи за день). Тест держит эту дверь закрытой.
 */
class OlcRtcIpv4PatchTest {

    private fun config(): String = JSONObject(
        """
        {
          "dns": {
            "servers": [
              {"type": "fakeip", "tag": "fakeip", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"},
              {"type": "tcp", "tag": "local", "server": "1.1.1.1"}
            ]
          },
          "inbounds": [
            {"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"]},
            {"type": "mixed", "tag": "mixed-in", "listen_port": 2412}
          ],
          "route": {
            "rules": [
              {"action": "sniff"},
              {"outbound": "direct", "ip_is_private": true},
              {"outbound": "Соединение", "rule_set": ["telegram", "youtube"]}
            ],
            "final": "direct"
          },
          "outbounds": [
            {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": 2409, "version": "5"}
          ]
        }
        """.trimIndent(),
    ).toString()

    @Test
    fun `подменные адреса остаются только IPv4`() {
        val result = OlcRtcConfigPatch.onlyIpv4(config())
        assertTrue(result.patched)

        val servers = JSONObject(result.content).getJSONObject("dns").getJSONArray("servers")
        val fakeip = servers.getJSONObject(0)
        assertTrue("подменный диапазон IPv4 обязан остаться", fakeip.has("inet4_range"))
        assertFalse("IPv6-диапазон должен быть снят", fakeip.has("inet6_range"))
    }

    @Test
    fun `у туннеля остаётся только адрес IPv4`() {
        val result = OlcRtcConfigPatch.onlyIpv4(config())

        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        val addresses = tun.getJSONArray("address")
        assertEquals(1, addresses.length())
        assertEquals("172.19.0.1/30", addresses.getString(0))
    }

    @Test
    fun `распознавание протокола не трогаем — иначе трафик из туннеля зависает навсегда`() {
        val result = OlcRtcConfigPatch.onlyIpv4(config())

        val rules = JSONObject(result.content).getJSONObject("route").getJSONArray("rules")
        val sniff = (0 until rules.length()).count { rules.getJSONObject(it).optString("action") == "sniff" }
        assertEquals("правило sniff должно остаться ровно одно", 1, sniff)
    }

    @Test
    fun `правила маршрутизации и выходы не меняются`() {
        val before = JSONObject(config())
        val after = JSONObject(OlcRtcConfigPatch.onlyIpv4(config()).content)

        assertEquals(
            before.getJSONObject("route").getJSONArray("rules").toString(),
            after.getJSONObject("route").getJSONArray("rules").toString(),
        )
        assertEquals(
            before.getJSONArray("outbounds").toString(),
            after.getJSONArray("outbounds").toString(),
        )
    }

    @Test
    fun `второй проход ничего не меняет и говорит об этом честно`() {
        val once = OlcRtcConfigPatch.onlyIpv4(config())
        val twice = OlcRtcConfigPatch.onlyIpv4(once.content)

        assertFalse(twice.patched)
        assertEquals(once.content, twice.content)
    }

    @Test
    fun `испорченный конфиг не роняет старт`() {
        val result = OlcRtcConfigPatch.onlyIpv4("это не json")

        assertFalse(result.patched)
        assertEquals("это не json", result.content)
    }
}
