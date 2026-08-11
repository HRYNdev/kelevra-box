package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки правки стека туннеля, найденной замерами на телефоне 11.08.2026.
 *
 * Стена выглядела так: соединения из туннеля не доходят до выхода вообще, и в журнале
 * у них есть только строка «нашёл приложение». Причина — своя таблица трансляции портов
 * у стека `mixed`: `ipv4: tcp: NAT port space exhausted`, 220 раз за минуту. Запись в ней
 * освобождается только по простою (пять минут), закрытие соединения её не отдаёт, а при
 * переполнении пакет молча выбрасывается — приложение отказа не получает и висит.
 */
class OlcRtcTunnelPatchTest {

    private fun config(stack: String = "mixed"): String {
        val sniff = JSONObject().put("action", "sniff")
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

    // ------------------------------------------------------------------------ отказы

    @Test
    fun `испорченный конфиг не роняет старт`() {
        assertFalse(OlcRtcConfigPatch.tunnelStack("не json").patched)
        assertEquals("не json", OlcRtcConfigPatch.tunnelStack("не json").content)
    }
}
