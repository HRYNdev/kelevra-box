package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пароль служебного порта ядра: свой у устройства, пароль профиля главнее, старый шаблон работает. */
class ClashSecretPatchTest {

    private fun config(clash: String?): String {
        val exp = clash?.let { """"experimental":{"clash_api":$it,"cache_file":{"enabled":true}},""" } ?: ""
        return """{$exp"outbounds":[{"tag":"a","type":"direct"}]}"""
    }

    private fun secretOf(content: String): String =
        JSONObject(content).getJSONObject("experimental").getJSONObject("clash_api").optString("secret", "")

    @Test
    fun `шаблон без пароля получает пароль устройства`() {
        val r = ClashSecretPatch.addSecret(config("""{"external_controller":"127.0.0.1:9090"}""")) { "dev-secret" }
        assertTrue(r.patched)
        assertEquals("dev-secret", r.secret)
        assertEquals("dev-secret", secretOf(r.content))
    }

    @Test
    fun `остальной конфиг не трогается`() {
        val r = ClashSecretPatch.addSecret(config("""{"external_controller":"127.0.0.1:9090"}""")) { "x" }
        val exp = JSONObject(r.content).getJSONObject("experimental")
        assertEquals("127.0.0.1:9090", exp.getJSONObject("clash_api").getString("external_controller"))
        assertTrue(exp.getJSONObject("cache_file").getBoolean("enabled"))
    }

    @Test
    fun `пароль профиля главнее и свой не создаётся`() {
        var asked = false
        val r = ClashSecretPatch.addSecret(
            config("""{"external_controller":"127.0.0.1:9090","secret":"from-profile"}"""),
        ) {
            asked = true
            "dev-secret"
        }
        assertFalse(r.patched)
        assertEquals("from-profile", r.secret)
        assertFalse(asked)
    }

    @Test
    fun `нет секции clash_api - ничего не создаём`() {
        val r = ClashSecretPatch.addSecret(config(null)) { "dev-secret" }
        assertFalse(r.patched)
        assertEquals("", r.secret)
        assertFalse(r.content.contains("clash_api"))
    }

    @Test
    fun `битый конфиг остаётся как был`() {
        val r = ClashSecretPatch.addSecret("не json") { "dev-secret" }
        assertFalse(r.patched)
        assertEquals("не json", r.content)
        assertEquals("", r.secret)
    }

    @Test
    fun `пароль не создан - порт как раньше`() {
        val r = ClashSecretPatch.addSecret(config("""{"external_controller":"127.0.0.1:9090"}""")) { "" }
        assertFalse(r.patched)
        assertEquals("", secretOf(r.content))
    }
}
