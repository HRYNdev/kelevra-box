package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.path.PathWords
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правка «наборы правил берём из кэша» проверяется на настоящем конфиге сервера:
 * все 22 набора там удалённые, и именно они на стенде с белым списком не давали ядру
 * стартовать вовсе.
 */
class RuleSetLocalPatchTest {
    private fun serverConfig(): String =
        javaClass.classLoader!!.getResourceAsStream("config-server.json")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun remoteTags(content: String): List<String> {
        val sets = JSONObject(content).getJSONObject("route").getJSONArray("rule_set")
        return (0 until sets.length())
            .map { sets.getJSONObject(it) }
            .filter { it.optString("type") == "remote" }
            .map { it.getString("tag") }
    }

    private fun fakeCache(tags: List<String>): Map<String, File> =
        tags.associateWith { File("/data/rule-sets/$it.srs") }

    private fun setsOf(content: String): List<JSONObject> {
        val route = JSONObject(content).getJSONObject("route")
        val sets = route.optJSONArray("rule_set") ?: JSONArray()
        return (0 until sets.length()).map { sets.getJSONObject(it) }
    }

    /** Все имена наборов, на которые ссылается любое правило конфига (включая вложенные). */
    private fun referenced(content: String): Set<String> {
        val root = JSONObject(content)
        val found = mutableSetOf<String>()
        fun walk(rules: JSONArray?) {
            rules ?: return
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                walk(rule.optJSONArray("rules"))
                when (val value = rule.opt("rule_set")) {
                    is String -> found += value
                    is JSONArray -> (0 until value.length()).forEach { found += value.getString(it) }
                }
            }
        }
        walk(root.optJSONObject("route")?.optJSONArray("rules"))
        walk(root.optJSONObject("dns")?.optJSONArray("rules"))
        return found
    }

    @Test
    fun `на настоящем конфиге все наборы удалённые`() {
        val tags = remoteTags(serverConfig())
        assertEquals("конфиг сервера изменился — тест держится за него", 22, tags.size)
        assertTrue(tags.contains("main-domains"))
    }

    @Test
    fun `кэш полный — в ядро уходят локальные файлы, правила целы`() {
        val config = serverConfig()
        val tags = remoteTags(config)
        val result = RuleSetLocalPatch.useCached(config, fakeCache(tags))

        assertTrue(result.patched)
        assertEquals(tags.size, result.ready.size)
        assertTrue("ничего выбрасывать не пришлось", result.missing.isEmpty())

        val sets = setsOf(result.content)
        assertEquals(tags.size, sets.size)
        for (set in sets) {
            assertEquals("local", set.getString("type"))
            assertTrue("путь до файла обязателен", set.getString("path").isNotBlank())
            assertFalse("скачивать локальный набор нечем", set.has("url"))
            assertFalse(set.has("update_interval"))
            assertFalse(set.has("http_client"))
        }
        assertEquals(
            "правила трогать было незачем",
            referenced(config),
            referenced(result.content),
        )
    }

    @Test
    fun `кэша нет — конфиг уходит в ядро нетронутым`() {
        // Проверено на стенде 08.08.2026: ядро при недоступном наборе не падает, тянет
        // его фоном и держит своё хранилище. Значит отнимать у него удалённые записи
        // нельзя — маршруты стали бы хуже, чем вовсе без правки.
        val config = serverConfig()
        val tags = remoteTags(config)
        val result = RuleSetLocalPatch.useCached(config, emptyMap())

        assertFalse("менять нечего", result.patched)
        assertEquals("конфиг обязан остаться дословным", config, result.content)
        assertTrue(result.ready.isEmpty())
        assertEquals(tags.size, result.missing.size)
        assertEquals("список для докачки собран целиком", tags.toSet(), result.remotes.map { it.tag }.toSet())
    }

    @Test
    fun `частичный кэш — своё локально, чужое остаётся удалённым`() {
        val config = serverConfig()
        val tags = remoteTags(config)
        val half = tags.take(tags.size / 2)
        val result = RuleSetLocalPatch.useCached(config, fakeCache(half))

        assertTrue(result.patched)
        assertEquals(half.size, result.ready.size)
        assertEquals(tags.size - half.size, result.missing.size)

        val byTag = setsOf(result.content).associateBy { it.getString("tag") }
        assertEquals("ни один набор не потерялся", tags.size, byTag.size)
        for (tag in half) assertEquals("local", byTag.getValue(tag).getString("type"))
        for (tag in tags - half.toSet()) {
            assertEquals("remote", byTag.getValue(tag).getString("type"))
            assertTrue("удалённому нужен его адрес", byTag.getValue(tag).getString("url").isNotBlank())
        }
        assertEquals("правила не трогаем вовсе", referenced(config), referenced(result.content))
    }

    @Test
    fun `правила не трогаются ни при каком составе кэша`() {
        val config = serverConfig()
        val tags = remoteTags(config)
        for (cache in listOf(emptyList(), tags.take(1), tags.take(tags.size / 2), tags)) {
            val result = RuleSetLocalPatch.useCached(config, fakeCache(cache))
            assertEquals(
                "правки маршрутов не наше дело (кэш ${cache.size})",
                referenced(config),
                referenced(result.content),
            )
        }
    }

    @Test
    fun `локальные наборы не трогаем`() {
        val config = """
            {"route":{"rule_set":[{"tag":"a","type":"local","format":"binary","path":"/tmp/a.srs"}]}}
        """.trimIndent()
        val result = RuleSetLocalPatch.useCached(config, emptyMap())

        assertFalse("трогать нечего: удалённых наборов нет", result.patched)
        assertTrue(result.remotes.isEmpty())
        assertEquals(config, result.content)
    }

    @Test
    fun `конфиг без наборов возвращается как есть`() {
        val config = """{"route":{"final":"direct"}}"""
        val result = RuleSetLocalPatch.useCached(config, emptyMap())

        assertFalse(result.patched)
        assertEquals(config, result.content)
    }

    @Test
    fun `непонятный конфиг старт не ломает`() {
        val broken = "{это не json"
        val result = RuleSetLocalPatch.useCached(broken, emptyMap())

        assertFalse(result.patched)
        assertEquals("конфиг обязан вернуться нетронутым", broken, result.content)
        assertTrue(result.remotes.isEmpty())
    }

    @Test
    fun `сборка конфига переживает повторное наложение`() {
        val config = serverConfig()
        val tags = remoteTags(config)
        val once = RuleSetLocalPatch.useCached(config, fakeCache(tags))
        val twice = RuleSetLocalPatch.useCached(once.content, fakeCache(tags))

        assertFalse("второй раз накладывать нечего", twice.patched)
        assertEquals(referenced(once.content), referenced(twice.content))
    }

    @Test
    fun `про пустые правила человеку говорится вслух`() {
        assertEquals("правила ещё не загружены", PathWords.rulesNote(total = 22, ready = 0))
        assertEquals("правила загружены не полностью (15 из 22)", PathWords.rulesNote(total = 22, ready = 15))
        assertNull("всё на месте — молчим", PathWords.rulesNote(total = 22, ready = 22))
        assertNull("конфиг ещё не читали — сказать нечего", PathWords.rulesNote(total = 0, ready = 0))
        assertNotNull(PathWords.rulesNote(total = 1, ready = 0))
    }
}
