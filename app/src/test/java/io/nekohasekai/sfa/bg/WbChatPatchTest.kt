package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Второй носитель комнаты — WB Chat — отдельным выходом рядом с комнатой.
 *
 * Проверяем три вещи: собственный трафик ядра WB Chat уводится в direct первым маршрутным
 * правилом (иначе ядро понесёт свой звонок в свой же сокс), автомат по-прежнему видит
 * комнатой только порт olcRTC/Телемоста и не берёт выход WB Chat основным каналом, и
 * пауза перед перезапуском ядра.
 */
class WbChatPatchTest {

    private val roomPort = 8808
    private val wbPort = 8809
    private val pkg = "io.nekohasekai.sfa"

    /** Форма профиля с сервера; выход WB Chat кладётся в тот же ручной селектор. */
    private fun config(selectorMembers: String = "\"Нидерланды\", \"Комната\", \"Комната WB\""): String = JSONObject(
        """
        {
          "route": {
            "rules": [
              {"action": "sniff"},
              {"type": "logical", "mode": "or", "rules": [{"protocol": "dns"}, {"port": 53}], "action": "hijack-dns"},
              {"outbound": "direct", "ip_is_private": true},
              {"outbound": "Соединение", "rule_set": ["main-domains"]}
            ],
            "final": "direct"
          },
          "outbounds": [
            {"type": "selector", "tag": "Соединение", "outbounds": [$selectorMembers], "default": "Нидерланды"},
            {"type": "urltest", "tag": "Нидерланды", "outbounds": ["vless-1"]},
            {"type": "direct", "tag": "direct"},
            {"type": "vless", "tag": "vless-1", "server": "203.0.113.1", "server_port": 443},
            {"type": "socks", "tag": "Комната", "server": "127.0.0.1", "server_port": $roomPort, "version": "5"},
            {"type": "socks", "tag": "Комната WB", "server": "127.0.0.1", "server_port": $wbPort, "version": "5"}
          ]
        }
        """.trimIndent(),
    ).toString()

    private fun rules(content: String) = JSONObject(content).getJSONObject("route").getJSONArray("rules")

    @Test
    fun directRuleGoesFirstAfterSniffAndDns() {
        val result = WbChatConfigPatch.directForSelf(config(), pkg)
        assertTrue(result.patched)
        val rules = rules(result.content)
        assertEquals(5, rules.length())
        assertEquals("sniff", rules.getJSONObject(0).optString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).optString("action"))
        val rule = rules.getJSONObject(2)
        assertEquals("direct", rule.optString("outbound"))
        assertEquals(pkg, rule.getJSONArray("package_name").getString(0))
        assertEquals("wb.ru", rule.getJSONArray("domain_suffix").getString(0))
        assertEquals("176.101.92.0/23", rule.getJSONArray("ip_cidr").getString(0))
    }

    @Test
    fun wbRangesLeaveTheTun() {
        val withTun = JSONObject(config()).put(
            "inbounds",
            org.json.JSONArray().put(JSONObject("""{"type": "tun", "tag": "tun-in", "route_exclude_address": ["10.0.0.0/8"]}""")),
        ).toString()
        val result = WbChatConfigPatch.directForSelf(withTun, pkg)
        val excl = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0).getJSONArray("route_exclude_address")
        val list = (0 until excl.length()).map { excl.getString(it) }
        assertEquals(listOf("10.0.0.0/8") + WbChatConfigPatch.TUN_EXCLUDE, list)
    }

    @Test
    fun directRuleIsNotDuplicated() {
        val once = WbChatConfigPatch.directForSelf(config(), pkg)
        val twice = WbChatConfigPatch.directForSelf(once.content, pkg)
        assertFalse(twice.patched)
        assertEquals(rules(once.content).length(), rules(twice.content).length())
    }

    @Test
    fun noDirectOutboundLeavesConfigAlone() {
        val content = JSONObject(config()).apply {
            val outbounds = getJSONArray("outbounds")
            for (i in outbounds.length() - 1 downTo 0) {
                if (outbounds.getJSONObject(i).optString("type") == "direct") outbounds.remove(i)
            }
        }.toString()
        val result = WbChatConfigPatch.directForSelf(content, pkg)
        assertFalse(result.patched)
        assertEquals(content, result.content)
    }

    @Test
    fun autoModeSeesOnlyTheRoomPortAsRoom() {
        val layout = AutoModeExits.parse(config(), roomPort)
        assertEquals("Комната", layout.room)
        assertEquals("Соединение", layout.chooser)
        assertEquals("Нидерланды", layout.main)
    }

    @Test
    fun wbExitIsNeverTakenAsMain() {
        // Сервер положил WB Chat первым — основным каналом он всё равно не становится.
        val layout = AutoModeExits.parse(config("\"Комната WB\", \"Нидерланды\", \"Комната\""), roomPort)
        assertEquals("Комната", layout.room)
        assertEquals("Нидерланды", layout.main)
        assertFalse(layout.measurable.contains("Комната WB"))
    }

    @Test
    fun selectorWithOnlyWbHasNoMain() {
        val layout = AutoModeExits.parse(config("\"Комната WB\""), roomPort)
        assertNull(layout.main)
    }

    @Test
    fun quicRejectByWbPortStaysBeforeSelectorRule() {
        val withSelf = WbChatConfigPatch.directForSelf(config(), pkg).content
        val quic = OlcRtcConfigPatch.addQuicReject(withSelf, wbPort)
        assertTrue(quic.patched)
        val rules = rules(quic.content)
        // своё direct — раньше отказа QUIC, отказ — прямо перед правилом в селектор
        assertEquals("direct", rules.getJSONObject(2).optString("outbound"))
        assertEquals("reject", rules.getJSONObject(4).optString("action"))
        assertEquals("Соединение", rules.getJSONObject(5).optString("outbound"))
    }

    @Test
    fun restartPause() {
        assertEquals(WbChatWatchdog.RESTART_PAUSE_MILLIS, WbChatWatchdog.pauseAfter(0))
        assertEquals(WbChatWatchdog.RESTART_PAUSE_MILLIS, WbChatWatchdog.pauseAfter(2))
        assertEquals(WbChatWatchdog.SLOW_PAUSE_MILLIS, WbChatWatchdog.pauseAfter(3))
        assertEquals(WbChatWatchdog.SLOW_PAUSE_MILLIS, WbChatWatchdog.pauseAfter(10))
    }

    @Test
    fun `каждый резолвер ядра WB идёт мимо tun`() {
        WbChatConfigPatch.DNS.split(",").map { it.substringBefore(":") + "/32" }.forEach {
            org.junit.Assert.assertTrue("$it не исключён из tun", it in WbChatConfigPatch.TUN_EXCLUDE)
        }
    }

    @Test
    fun `tcp_cc принимается только как reno`() {
        org.junit.Assert.assertEquals("reno", WbChatParams.tcpCcFrom("reno"))
        org.junit.Assert.assertEquals("", WbChatParams.tcpCcFrom(null))
        org.junit.Assert.assertEquals("", WbChatParams.tcpCcFrom(""))
        org.junit.Assert.assertEquals("", WbChatParams.tcpCcFrom("cubic"))
        org.junit.Assert.assertEquals("", WbChatParams.tcpCcFrom("Reno"))
        org.junit.Assert.assertEquals("", WbChatParams.tcpCcFrom("reno "))
    }
}
