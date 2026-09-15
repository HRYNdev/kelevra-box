package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перепроверка после захода, на котором резолверы промолчали.
 *
 * Беда: «не узнали» уезжало в обычный ритм, и следующий заход приходил через пять минут —
 * дом при ожившем резолвере узнавался только им.
 */
class AutoModeSilentRecheckTest {

    private val mid = (AutoMode.SILENT_RECHECK_MIN_MILLIS + AutoMode.SILENT_RECHECK_MAX_MILLIS) / 2

    @Test
    fun `резолверы промолчали — следующая проверка через 20–30 секунд`() {
        val pause = AutoMode.silentRecheck(blind = true, done = 0, jitterMillis = mid)
        assertEquals(mid, pause)
        assertTrue(pause!! in 20_000L..30_000L)
    }

    @Test
    fun `резолвер ответил — обычный ритм`() {
        assertNull(AutoMode.silentRecheck(blind = false, done = 0, jitterMillis = mid))
    }

    @Test
    fun `пауза не выходит за 20–30 секунд при любом разбросе`() {
        assertEquals(20_000L, AutoMode.silentRecheck(blind = true, done = 0, jitterMillis = 0L))
        assertEquals(30_000L, AutoMode.silentRecheck(blind = true, done = 0, jitterMillis = 10 * 60_000L))
    }

    @Test
    fun `серия ограничена — молчание навсегда не превращается в пробу каждые полминуты`() {
        var done = 0
        var rechecks = 0
        repeat(100) {
            val pause = AutoMode.silentRecheck(blind = true, done = done, jitterMillis = mid)
            if (pause != null) {
                done++
                rechecks++
            }
        }
        assertEquals(AutoMode.SILENT_RECHECK_ROUNDS, rechecks)
        assertTrue("серия короткая: ${AutoMode.SILENT_RECHECK_ROUNDS}", AutoMode.SILENT_RECHECK_ROUNDS in 3..20)
        // Вся серия короче пяти минут обычного ритма, умноженных на два: цена ограничена.
        assertTrue(AutoMode.SILENT_RECHECK_ROUNDS * AutoMode.SILENT_RECHECK_MAX_MILLIS <= 10 * 60_000L)
    }

    @Test
    fun `после ответа резолвера серия начинается заново`() {
        // Счётчик обнуляет заход, на котором резолвер ответил (так делает цикл автомата).
        var done = AutoMode.SILENT_RECHECK_ROUNDS
        assertNull(AutoMode.silentRecheck(blind = true, done = done, jitterMillis = mid))
        done = 0
        assertEquals(mid, AutoMode.silentRecheck(blind = true, done = done, jitterMillis = mid))
    }

    @Test
    fun `серию перепроверок после смены сети это не ломает — неуверенный заход её не закрывает`() {
        assertFalse(AutoMode.burstClosable(changed = false, pending = false, confident = false))
        assertTrue(AutoMode.burstClosable(changed = false, pending = false, confident = true))
    }
}
