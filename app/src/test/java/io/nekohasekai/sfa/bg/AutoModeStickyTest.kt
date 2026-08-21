package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Беда (стенд `zalipanie.sh`, 21.08.2026): sing-box хранит выбор селектора в своём кэше и
 * накатывает его поверх `default` при каждом старте — переживает даже временный выбор
 * автомата. [AutoModeSticky.restoreTarget] — чистая логика решения «куда вернуть селектор»,
 * без неё не разобраться, залипший тег — это выбор автомата или человека.
 */
class AutoModeStickyTest {
    private fun config(default: String, members: List<String>): String {
        val outbounds = members.joinToString(",") { "\"$it\"" }
        return """
            {"outbounds":[
                {"tag":"main","type":"selector","default":"$default","outbounds":[$outbounds]}
            ]}
        """.trimIndent()
    }

    @Test
    fun `обычный возврат на default`() {
        val cfg = config(default = "ru", members = listOf("ru", "nl", "de"))
        assertEquals("ru", AutoModeSticky.restoreTarget(cfg, "main", stickyTag = "nl"))
    }

    @Test
    fun `залипший тег совпадает с default — возвращать некуда`() {
        val cfg = config(default = "ru", members = listOf("ru", "nl", "de"))
        assertNull(AutoModeSticky.restoreTarget(cfg, "main", stickyTag = "ru"))
    }

    @Test
    fun `группы нет в конфиге`() {
        val cfg = config(default = "ru", members = listOf("ru", "nl"))
        assertNull(AutoModeSticky.restoreTarget(cfg, "no-such-group", stickyTag = "nl"))
    }

    @Test
    fun `у селектора нет default`() {
        val cfg = """{"outbounds":[{"tag":"main","type":"selector","outbounds":["ru","nl"]}]}"""
        assertNull(AutoModeSticky.restoreTarget(cfg, "main", stickyTag = "nl"))
    }

    @Test
    fun `default есть, но его нет в списке outbounds группы`() {
        val cfg = config(default = "fr", members = listOf("ru", "nl"))
        assertNull(AutoModeSticky.restoreTarget(cfg, "main", stickyTag = "nl"))
    }

    @Test
    fun `битый JSON не роняет вызов`() {
        val broken = "{это не json"
        assertNull(AutoModeSticky.restoreTarget(broken, "main", stickyTag = "nl"))
    }
}
