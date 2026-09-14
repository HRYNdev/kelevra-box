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

    // ------------------------------------------------------------ версия ядра 1.15+
    //
    // sing-box 1.15.0 (release notes, дословно): "Since 1.15.0, sing-tun uses its own
    // TCP/IP stack, with substantial improvements... Remove the `stack` option to use
    // it." Ключ `tun.stack` deprecated с 1.15.0, снесут в 1.17.0. Значит на новых ядрах
    // ключ не ставим, а снимаем; версию узнаём в рантайме из Libbox.version(), а не
    // храним отдельно от CORE_REF, чтобы апгрейд ядра не требовал правки этого файла.

    @Test
    fun `ядро до 1_15 — стек ставится как раньше`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "1.14.0-beta.4")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertEquals("gvisor", tun.getString("stack"))
    }

    @Test
    fun `ядро 1_15 alpha — ключ stack снимается`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "1.15.0-alpha.3")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertFalse("на своём стеке ключа stack быть не должно", tun.has("stack"))
    }

    @Test
    fun `ядро 1_15_0 релиз — ключ stack снимается`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "1.15.0")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tun.has("stack"))
    }

    @Test
    fun `ядро 1_16_1 — ключ stack снимается`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "1.16.1")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tun.has("stack"))
    }

    @Test
    fun `версия ядра уже на своём стеке без ключа — второй раз не трогаем`() {
        val content = config(stack = "mixed").let {
            JSONObject(it).apply {
                getJSONArray("inbounds").getJSONObject(0).remove("stack")
            }.toString()
        }
        val result = OlcRtcConfigPatch.tunnelStack(content, "1.16.0")
        assertFalse(result.patched)
    }

    @Test
    fun `пустая версия — ставим gvisor как раньше`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertEquals("gvisor", tun.getString("stack"))
    }

    @Test
    fun `мусорная версия — ставим gvisor и не молчим об этом`() {
        val result = OlcRtcConfigPatch.tunnelStack(config(stack = "mixed"), "мусор-не-версия")
        assertTrue(result.patched)
        val tun = JSONObject(result.content).getJSONArray("inbounds").getJSONObject(0)
        assertEquals("gvisor", tun.getString("stack"))
        assertTrue("note должен назвать версию нераспознанной", result.note.contains("не распознана"))
    }

    @Test
    fun `конфиг без tun-входа не падает ни на старой, ни на новой версии`() {
        val noTun = JSONObject(
            """{"inbounds": [{"type": "mixed", "tag": "mixed-in"}], "route": {"rules": []}, "outbounds": []}""",
        ).toString()

        assertFalse(OlcRtcConfigPatch.tunnelStack(noTun, "1.14.0-beta.4").patched)
        assertFalse(OlcRtcConfigPatch.tunnelStack(noTun, "1.15.0").patched)
    }
}
