package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.path.HonestProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки правки конфига под честную пробу и главного правила всей затеи:
 * путь, который нечем измерить, живым не объявляется никогда.
 *
 * Конфиг для проверок — настоящий, снятый с телефона (ключи вычищены). Выдуманный
 * конфиг доказывал бы только то, что код работает на выдуманном конфиге, а ломается
 * оно на живом: русские теги с пробелами и точками, селектор поверх urltest,
 * `mixed`-вход на 2412, правила с rule_set.
 */
class ProbeInboundPatchTest {

    private fun serverConfig(): String =
        javaClass.classLoader!!.getResourceAsStream("config-server.json")!!
            .readBytes().toString(Charsets.UTF_8)

    /** Порт SOCKS комнаты; в снятом конфиге комнаты нет, поэтому любое значение. */
    private val socksPort = 2409

    // ------------------------------------------------------ правка на настоящем конфиге

    @Test
    fun `на настоящем конфиге входы встают на каждый путь`() {
        val content = serverConfig()
        val layout = AutoModeExits.parse(content, socksPort)

        // Что вообще собираемся мерить: выход селектора и прямой выход.
        assertTrue("нашли что мерить: ${layout.measurable}", layout.measurable.size >= 2)
        assertTrue(layout.measurable.contains("direct"))

        val result = ProbeInboundPatch.addProbeInbounds(content, layout.measurable)
        assertTrue(result.note, result.patched)
        assertEquals(layout.measurable.size, result.entries.size)

        val root = JSONObject(result.content)
        val inbounds = root.getJSONArray("inbounds")
        // Свои входы добавлены к чужим, ничего не потеряно: было 2 (tun + mixed).
        assertEquals(2 + result.entries.size, inbounds.length())

        val ports = result.entries.values.map { it.port }
        assertEquals("порты у путей разные", ports.size, ports.toSet().size)
        assertFalse("чужой порт не занимаем", ports.contains(2412))
        assertTrue(ports.all { it in 1..65535 })
        assertTrue(result.entries.values.all { it.host == "127.0.0.1" })

        // Правила привязки стоят первыми, иначе их перебьют правила по доменам.
        val rules = root.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until result.entries.size) {
            val rule = rules.getJSONObject(i)
            assertEquals(1, rule.getJSONArray("inbound").length())
            assertTrue(rule.getJSONArray("inbound").getString(0).startsWith(ProbeInboundPatch.TAG_PREFIX))
            assertTrue(rule.getString("outbound") in result.entries.keys)
        }
        // Чужие правила на месте и в прежнем порядке.
        val before = JSONObject(content).getJSONObject("route").getJSONArray("rules")
        assertEquals(before.length() + result.entries.size, rules.length())
        assertEquals(
            before.getJSONObject(0).toString(),
            rules.getJSONObject(result.entries.size).toString(),
        )
    }

    @Test
    fun `закреплённые входы читаются обратно из конфига`() {
        val content = serverConfig()
        val patched = ProbeInboundPatch.addProbeInbounds(
            content,
            AutoModeExits.parse(content, socksPort).measurable,
        )

        // Источник истины — сам конфиг: если правка не легла, здесь будет пусто,
        // а не память о том, что мы собирались её положить.
        assertEquals(patched.entries, AutoModeExits.probeEntries(patched.content))
        assertTrue(AutoModeExits.probeEntries(content).isEmpty())
    }

    @Test
    fun `после правки основной путь меряется сам по себе`() {
        val content = serverConfig()
        val before = AutoModeExits.parse(content, socksPort)
        assertFalse("до правки основной путь не закреплён", before.mainPinned)
        // Общий вход конфиг объявляет сам, и до правки проба ходила через него —
        // то есть через тот выход, который выбран прямо сейчас.
        assertEquals(2412, before.localProxy?.port)

        val patched = ProbeInboundPatch.addProbeInbounds(content, before.measurable).content
        val after = AutoModeExits.parse(patched, socksPort)

        assertTrue(after.mainPinned)
        assertNotEquals("проба ушла с общего входа на свой", 2412, after.localProxy?.port)
        assertEquals(after.entryFor(after.main), after.localProxy)
        // Прямой выход тоже меряется отдельно, и другим входом.
        assertNotEquals(after.entryFor("direct"), after.entryFor(after.main))
    }

    // ------------------------------------------------------------- битые и чужие конфиги

    @Test
    fun `битый конфиг не трогаем и входов не выдумываем`() {
        val broken = listOf(
            "" to "пустой",
            "не json вовсе" to "не json",
            "{}" to "пустой объект",
            """{"outbounds":[{"tag":"direct","type":"direct"}]}""" to "нет route",
            """{"route":{"rules":[]}}""" to "нет outbounds",
            """{"outbounds":[{"tag":"direct","type":"direct"}],"route":{"rules":"чушь"}}""" to "route не тот",
        )
        for ((content, what) in broken) {
            val result = ProbeInboundPatch.addProbeInbounds(content, listOf("direct"))
            assertFalse(what, result.patched)
            assertTrue("$what: входов быть не должно", result.entries.isEmpty())
            assertEquals("$what: конфиг обязан остаться как был", content, result.content)
            assertTrue("$what: причина обязана быть словами", result.note.isNotBlank())
        }
    }

    @Test
    fun `выхода с таким тегом нет — входа не будет`() {
        val result = ProbeInboundPatch.addProbeInbounds(serverConfig(), listOf("такого выхода нет"))
        assertFalse(result.patched)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.note.contains("такого выхода нет"))
    }

    @Test
    fun `правило с лишним условием за привязку не считается`() {
        // Чужой конфиг может сам содержать правило с inbound — например, увести
        // конкретный вход в конкретный выход, но только для одного домена. Такой
        // вход НЕ меряет путь целиком, и принимать его за закреплённый нельзя.
        val content = """
            {
              "inbounds": [
                {"type":"socks","tag":"кто-то","listen":"127.0.0.1","listen_port":1080}
              ],
              "outbounds": [{"tag":"proxy","type":"direct"}],
              "route": {"rules":[
                {"inbound":["кто-то"],"outbound":"proxy","domain":["example.com"]}
              ]}
            }
        """.trimIndent()
        assertTrue(AutoModeExits.probeEntries(content).isEmpty())
    }

    // ------------------------------------------- главное правило: нечем измерить ≠ живой

    @Test
    fun `нечем измерить — путь не живой`() {
        val nothing = HonestProbe.measure(entry = null, label = "основной")
        assertEquals(HonestProbe.Verdict.Unmeasurable, nothing.verdict)
        assertFalse("«не проверено» обязано отличаться от «работает»", nothing.live)
        assertFalse(nothing.measured)
        assertNull(nothing.latencyMs)

        // И негодный вход — тоже не повод объявить путь живым.
        val nonsense = HonestProbe.measure(AutoModeExits.Endpoint("127.0.0.1", 0), "основной")
        assertEquals(HonestProbe.Verdict.Unmeasurable, nonsense.verdict)
        assertFalse(nonsense.live)
    }

    @Test
    fun `живым считается только то, что реально ответило`() {
        // Инвариант на все вердикты разом: `live` не может быть правдой без замеренной
        // задержки, а «не проверено» не может стать «работает» ни на каком пути.
        val all = listOf(
            HonestProbe.Measurement.live(140, "вход 127.0.0.1:1, цель x"),
            HonestProbe.Measurement.dead("ответа нет", "вход 127.0.0.1:1"),
            HonestProbe.Measurement.unmeasurable("входа нет"),
        )
        for (m in all) {
            assertEquals(m.verdict == HonestProbe.Verdict.Live, m.live)
            if (m.live) assertTrue("живой обязан принести задержку", (m.latencyMs ?: -1) >= 0)
            if (!m.measured) assertFalse("не проверено — значит не живой", m.live)
        }
        assertEquals(1, all.count { it.live })
        assertEquals(2, all.count { it.measured })
    }

    @Test
    fun `непроверяемый путь виден снаружи как непроверяемый`() {
        // Правка не легла (битый конфиг) — раскладка обязана честно сказать, что
        // основной путь не закреплён, а не подсунуть общий вход как «его».
        val layout = AutoModeExits.parse("{}", socksPort)
        assertFalse(layout.mainPinned)
        assertNull(layout.entryFor(layout.main))
        assertTrue(HonestProbe.measure(layout.entryFor(layout.main), "основной").verdict == HonestProbe.Verdict.Unmeasurable)
    }
}
