package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки двух правок туннеля, найденных замерами на телефоне 11.08.2026.
 *
 * Обе лечат одну и ту же стену: соединения из туннеля не доходят до выхода вообще,
 * и в журнале у них есть только строка «нашёл приложение». Первая причина — своя
 * таблица трансляции портов у стека `mixed` (`NAT port space exhausted`, 220 раз
 * за минуту), вторая — ожидание первых байт без предела у распознавания протокола.
 */
class OlcRtcTunnelPatchTest {

    private fun config(stack: String = "mixed", sniffTimeout: String? = null): String {
        val sniff = JSONObject().put("action", "sniff")
        if (sniffTimeout != null) sniff.put("timeout", sniffTimeout)
        return JSONObject(
            """
            {
              "inbounds": [
                {"type": "tun", "tag": "tun-in", "stack": "$stack", "address": ["172.19.0.1/30"]},
                {"type": "mixed", "tag": "mixed-in", "listen_port": 2412}
              ],
              "route": {"rules": [], "final": "direct"},
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
            """.trimIndent(),
        ).apply {
            getJSONObject("route").getJSONArray("rules").put(sniff)
        }.toString()
    }

    // ------------------------------------------------------------------ стек туннеля

    @Test
    fun `стек туннеля переводится на тот, где своей трансляции портов нет`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"))
        assertTrue(result.patched)

        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertEquals("gvisor", tun.getString("stack"))
    }

    @Test
    fun `вход, который не туннель, не трогаем`() {
        val result = OlcRtcConfigPatch.tunnelStack(config())

        val mixed = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(1)
        assertEquals("mixed-in", mixed.getString("tag"))
        assertFalse("у обычного входа стека быть не должно", mixed.has("stack"))
    }

    @Test
    fun `правильный стек второй раз не переписываем`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "gvisor"))

        assertFalse(result.patched)
    }

    // ------------------------------------------------- предел ожидания распознавания

    @Test
    fun `распознаванию протокола ставится предел ожидания`() {
        val result = OlcRtcConfigPatch.sniffTimeout(config())
        assertTrue(result.patched)

        val rule = JSONObject(result.content).getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("sniff", rule.getString("action"))
        assertEquals("300ms", rule.getString("timeout"))
    }

    @Test
    fun `чужой предел ожидания не перебиваем`() {
        val result = OlcRtcConfigPatch.sniffTimeout(config(sniffTimeout = "1s"))

        assertFalse(result.patched)
        val rule = JSONObject(result.content).getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("1s", rule.getString("timeout"))
    }

    @Test
    fun `само распознавание остаётся на месте`() {
        val result = OlcRtcConfigPatch.sniffTimeout(config())

        val rules = JSONObject(result.content).getJSONObject("route").getJSONArray("rules")
        val sniff = (0 until rules.length()).count { rules.getJSONObject(it).optString("action") == "sniff" }
        assertEquals("правило распознавания снимать нельзя — из туннеля домен брать больше неоткуда", 1, sniff)
    }

    // ------------------------------------------------------------------------ отказы

    @Test
    fun `испорченный конфиг не роняет старт`() {
        assertFalse(OlcRtcConfigPatch.tunnelStack("не json").patched)
        assertFalse(OlcRtcConfigPatch.sniffTimeout("не json").patched)
        assertEquals("не json", OlcRtcConfigPatch.tunnelStack("не json").content)
    }
}
