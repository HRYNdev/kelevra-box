package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.DeviceTraffic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свой счёт трафика. Проверяется хрупкое: перезапуск ядра посреди счёта, мёртвый
 * счётчик и пустой расход. Сам поход в настройки без Android не проверяется —
 * вся арифметика вынесена в чистые функции ровно ради этого.
 */
class DeviceTrafficTest {

    @Test
    fun `прирост считается от прошлого показания`() {
        val after = DeviceTraffic.next(State(total = 1_000L, seen = 400L), reading = 900L)
        assertEquals("итог вырос ровно на прирост", 1_500L, after.total)
        assertEquals("показание запомнили", 900L, after.seen)
    }

    @Test
    fun `ядро перезапустилось посреди счёта — итог не уменьшился`() {
        // Ровно то, ради чего этот объект и нужен: счётчик ядра обнуляется на каждом
        // гашении туннеля, а расход устройства обязан только расти.
        var state = State(total = 0L, seen = 0L)
        state = DeviceTraffic.next(state, reading = 5_000L)
        state = DeviceTraffic.next(state, reading = 9_000L)
        val beforeRestart = state.total
        // ядро поднялось заново и считает с нуля
        state = DeviceTraffic.next(state, reading = 120L)
        assertTrue("итог не уменьшился", state.total >= beforeRestart)
        assertEquals("прирост после перезапуска считается от нуля", 9_120L, state.total)
        assertEquals(120L, state.seen)
    }

    @Test
    fun `подряд несколько перезапусков ничего не теряют`() {
        var state = State(total = 0L, seen = 0L)
        var previous = 0L
        for (жизньЯдра in listOf(listOf(10L, 40L, 100L), listOf(7L, 30L), listOf(1L))) {
            for (reading in жизньЯдра) {
                state = DeviceTraffic.next(state, reading)
                assertTrue("итог только растёт", state.total >= previous)
                previous = state.total
            }
        }
        assertEquals(131L, state.total)
    }

    @Test
    fun `то же показание второй раз ничего не добавляет`() {
        val state = DeviceTraffic.next(State(total = 700L, seen = 700L), reading = 700L)
        assertEquals(700L, state.total)
    }

    @Test
    fun `счётчик недоступен — падения нет и итог не двигается`() {
        // Мост через Go бросает на мёртвом ядре. Это обычное состояние выключенного
        // туннеля, а не поломка: приложение из-за счётчика байтов падать не должно.
        assertNull("исключение — значит показания нет", DeviceTraffic.reading { error("ядро мертво") })
        assertNull("отрицательное показание бессмысленно", DeviceTraffic.reading { -1L })
        assertEquals("показание есть — берём как есть", 42L, DeviceTraffic.reading { 42L })

        val before = State(total = 4_096L, seen = 512L)
        DeviceTraffic.observe { error("ядро мертво") }
        assertEquals("мусорное показание итог не трогает", before, DeviceTraffic.next(before, reading = -1L))
    }

    @Test
    fun `нулевой расход — заголовка нет вовсе`() {
        // Пустое значение хуже отсутствующего: сервер кладёт заголовок в реестр как есть.
        assertNull(DeviceTraffic.header(0L))
        assertNull(DeviceTraffic.header(-5L))
    }

    @Test
    fun `расход есть — заголовок десятичным числом`() {
        val header = DeviceTraffic.header(1_234_567_890L)
        assertEquals("X-Device-Traffic", header?.first)
        assertEquals("1234567890", header?.second)
    }

    @Test
    fun `первый ненулевой расход пишется сразу, дальше — шагами`() {
        // Без первой записи заголовка не будет вовсе, а писать каждую секунду в Room
        // ради сотни байтов незачем.
        assertTrue(DeviceTraffic.flushNeeded(saved = 0L, total = 128L))
        assertTrue(!DeviceTraffic.flushNeeded(saved = 0L, total = 0L))
        assertTrue(!DeviceTraffic.flushNeeded(saved = 4_000_000L, total = 4_000_100L))
        assertTrue(DeviceTraffic.flushNeeded(saved = 4_000_000L, total = 4_000_000L + (1L shl 20)))
    }
}
