package io.nekohasekai.sfa.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отвязка выбранного выхода от кэша ядра. 15.08.2026 выбор «Комната», записанный
 * в `cache_file`, пережил починку канала и вставал обратно при каждом старте,
 * перекрывая `default` с сервера.
 */
class CacheSelectPatchTest {

    private fun config(cacheFile: String?): String {
        val experimental = cacheFile?.let { """"experimental":{"cache_file":$it},""" } ?: ""
        return """{$experimental"outbounds":[{"tag":"Выбор","type":"selector","default":"Нидерланды"}]}"""
    }

    @Test
    fun `хранение выбора выключается`() {
        val result = CacheSelectPatch.dontStoreSelected(
            config("""{"enabled":true,"store_selected":true,"path":"remnawave.db"}"""),
        )
        assertTrue(result.patched)
        val cache = JSONObject(result.content).getJSONObject("experimental").getJSONObject("cache_file")
        assertFalse(cache.getBoolean("store_selected"))
    }

    @Test
    fun `само хранилище остаётся на месте`() {
        // В cache_file лежат не только выборы, но и наборы правил: гасим одно поле, не файл.
        val result = CacheSelectPatch.dontStoreSelected(
            config("""{"enabled":true,"store_selected":true,"path":"remnawave.db"}"""),
        )
        val cache = JSONObject(result.content).getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("enabled"))
        assertEquals("remnawave.db", cache.getString("path"))
    }

    @Test
    fun `выход по умолчанию с сервера не трогаем`() {
        val result = CacheSelectPatch.dontStoreSelected(
            config("""{"enabled":true,"store_selected":true}"""),
        )
        val selector = JSONObject(result.content).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("Нидерланды", selector.getString("default"))
    }

    @Test
    fun `выключённый кэш не трогаем`() {
        val result = CacheSelectPatch.dontStoreSelected(config("""{"enabled":false}"""))
        assertFalse(result.patched)
    }

    @Test
    fun `уже выключённое хранение не трогаем`() {
        val result = CacheSelectPatch.dontStoreSelected(
            config("""{"enabled":true,"store_selected":false}"""),
        )
        assertFalse(result.patched)
    }

    @Test
    fun `конфиг без experimental проходит как есть`() {
        val original = config(null)
        val result = CacheSelectPatch.dontStoreSelected(original)
        assertFalse(result.patched)
        assertEquals(original, result.content)
    }

    @Test
    fun `битый конфиг не роняет старт`() {
        val result = CacheSelectPatch.dontStoreSelected("не json вовсе")
        assertFalse(result.patched)
        assertEquals("не json вовсе", result.content)
    }
}
