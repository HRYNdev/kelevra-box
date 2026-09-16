package io.nekohasekai.sfa.bg.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Таблица «состояние → капсула у камеры».
 *
 * Два вопроса: просим ли систему поднять уведомление в капсулу и что в ней написать.
 * Дома и без сети не просим; текст никогда не длиннее семи знаков — иначе система
 * покажет одну иконку.
 */
class PathChipTest {

    private val codes = mapOf("Нидерланды" to "NL", "Комната" to PathChip.ROOM)

    @Before
    fun setUp() {
        PathRegistry.clock = { 1_000_000L }
        PathRegistry.reset()
        PathRegistry.bindExits(main = "Нидерланды", room = "Комната")
    }

    private fun chip(
        chosen: PathId?,
        auto: Boolean = true,
        tunnelLive: Boolean = true,
        manualExit: String? = null,
    ): PathChip.Chip = PathChip.of(
        snapshot = PathRegistry.snapshot.value,
        chosen = chosen,
        auto = auto,
        tunnelLive = tunnelLive,
        manualExit = manualExit,
        codeOf = { codes[it] },
    )

    private fun assertChip(text: String, actual: PathChip.Chip) {
        assertTrue("должны продвигать: $actual", actual.promote)
        assertEquals(text, actual.text)
    }

    private fun assertNone(actual: PathChip.Chip) {
        assertFalse("не должны продвигать: $actual", actual.promote)
        assertEquals(null, actual.text)
    }

    @Test
    fun `основной канал жив — код выхода`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 120)
        assertChip("NL", chip(PathId.MAIN))
    }

    @Test
    fun `основной канал жив, раскладки ещё нет — «Туннель»`() {
        PathRegistry.bindExits(main = null, room = null)
        PathRegistry.alive(PathId.MAIN)
        assertChip(PathChip.TUNNEL, chip(PathId.MAIN))
    }

    @Test
    fun `основной канал меряется — «Поиск»`() {
        PathRegistry.probing(PathId.MAIN)
        assertChip(PathChip.SEARCH, chip(PathId.MAIN))
    }

    @Test
    fun `основной канал отказал — «Нет»`() {
        PathRegistry.dead(PathId.MAIN, reason = "молчит")
        assertChip(PathChip.DOWN, chip(PathId.MAIN))
    }

    @Test
    fun `комната жива — «Комната»`() {
        PathRegistry.alive(PathId.ROOM)
        assertChip("Комната", chip(PathId.ROOM))
    }

    @Test
    fun `комната поднимается — «Поиск»`() {
        PathRegistry.raising(PathId.ROOM)
        assertChip(PathChip.SEARCH, chip(PathId.ROOM))
    }

    @Test
    fun `ни на чём не стоим, автомат ищет путь — «Поиск»`() {
        PathRegistry.dead(PathId.MAIN, reason = "молчит")
        assertChip(PathChip.SEARCH, chip(chosen = null))
    }

    @Test
    fun `дома — не продвигаем, даже если ядро ещё живо`() {
        PathRegistry.alive(PathId.HOME)
        assertNone(chip(PathId.HOME, tunnelLive = true))
    }

    @Test
    fun `туннель погашен — не продвигаем`() {
        PathRegistry.alive(PathId.MAIN)
        assertNone(chip(PathId.MAIN, tunnelLive = false))
    }

    @Test
    fun `сети нет — не продвигаем`() {
        PathRegistry.unavailable(PathId.HOME, reason = "нет сети")
        assertNone(chip(PathId.MAIN))
    }

    @Test
    fun `выход выбран руками — его код, отказ — «Нет»`() {
        PathRegistry.alive(PathId.MAIN)
        assertChip("NL", chip(chosen = null, auto = false, manualExit = "Нидерланды"))
        PathRegistry.dead(PathId.MAIN, reason = "молчит")
        assertChip(PathChip.DOWN, chip(chosen = null, auto = false, manualExit = "Нидерланды"))
    }

    @Test
    fun `незнакомый выход руками — «Туннель», а не пустая капсула`() {
        assertChip(PathChip.TUNNEL, chip(chosen = null, auto = false, manualExit = "Марс"))
    }

    @Test
    fun `текст капсулы не длиннее семи знаков`() {
        val long = PathChip.of(
            snapshot = PathRegistry.snapshot.value,
            chosen = null,
            auto = false,
            tunnelLive = true,
            manualExit = "Очень длинный выход",
            codeOf = { it },
        )
        assertTrue(long.promote)
        assertEquals(PathChip.MAX, long.text!!.length)
        listOf(PathChip.ROOM, PathChip.SEARCH, PathChip.DOWN, PathChip.TUNNEL).forEach {
            assertTrue("$it длиннее ${PathChip.MAX}", it.length <= PathChip.MAX)
        }
    }

    @Test
    fun `одно и то же состояние — равная капсула, мигать нечему`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 100)
        val first = chip(PathId.MAIN)
        PathRegistry.alive(PathId.MAIN, latencyMs = 300)
        assertEquals(first, chip(PathId.MAIN))
    }
}
