package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки ритма приглядки дома.
 *
 * Смысл проверок не в арифметике, а в двух обещаниях, которые тут легко нарушить правкой
 * одной константы: окно слепоты дома не длиннее полутора минут, а платим мы за это
 * меньше, чем уже платим за приглядку из комнаты.
 */
class HomeWatchTest {

    // --------------------------------------------------------------- ритм и его цена

    @Test
    fun `дома слепнем не дольше полутора минут`() {
        // Ради этого всё и делается: было до пяти минут своего ритма (а на стенде
        // 10.08.2026 вышло 2 минуты 44 секунды), стало не больше одной паузы.
        assertTrue("худший случай ${HomeWatch.MAX_MILLIS} мс", HomeWatch.MAX_MILLIS <= 90_000L)
    }

    @Test
    fun `радио не будим чаще, чем это уже делает приглядка из комнаты`() {
        // Приглядка из комнаты ходит каждые 20-40 секунд, то есть около 120 раз в час,
        // и по мобильной сети. Дома должно быть дешевле, иначе экономия на заходах
        // съедена самими пробами.
        assertTrue("проб в час: ${HomeWatch.peeksPerHour()}", HomeWatch.peeksPerHour() < 120.0)
        // И не вырождаться в метроном раз в десять секунд.
        assertTrue(HomeWatch.peeksPerHour() < 100.0)
        assertTrue(HomeWatch.MIN_MILLIS >= 30_000L)
    }

    @Test
    fun `пауза не ровная — метронома не получается`() {
        assertTrue(HomeWatch.MAX_MILLIS > HomeWatch.MIN_MILLIS)
    }

    // -------------------------------------------------------------------- нарезка паузы

    @Test
    fun `кусок паузы не выходит за границы`() {
        val left = 5 * 60_000L
        assertEquals(HomeWatch.MIN_MILLIS, HomeWatch.slice(left, HomeWatch.MIN_MILLIS))
        assertEquals(HomeWatch.MAX_MILLIS, HomeWatch.slice(left, HomeWatch.MAX_MILLIS))
        // Вызывающему на слово не верим: слишком мелкий или слишком крупный кусок
        // означал бы либо метроном по радио, либо дыру в наблюдении.
        assertEquals(HomeWatch.MIN_MILLIS, HomeWatch.slice(left, 0L))
        assertEquals(HomeWatch.MAX_MILLIS, HomeWatch.slice(left, 10 * 60_000L))
    }

    @Test
    fun `проба не сдвигает сам заход`() {
        // Остаток круга меньше куска — досиживаем остаток, а не перешагиваем через заход:
        // иначе приглядка чинила бы одно отставание, заводя другое.
        assertEquals(5_000L, HomeWatch.slice(5_000L, HomeWatch.MAX_MILLIS))
        assertEquals(1L, HomeWatch.slice(1L, HomeWatch.MIN_MILLIS))
    }

    @Test
    fun `на полный круг дома приходится несколько проб, а не одна и не десяток`() {
        val round = 5 * 60_000L
        var left = round
        var peeks = 0
        while (left > 0) {
            left -= HomeWatch.slice(left, (HomeWatch.MIN_MILLIS + HomeWatch.MAX_MILLIS) / 2)
            if (left > 0) peeks++
        }
        assertTrue("проб за круг: $peeks", peeks in 3..8)
    }

    // ------------------------------------------------------------------- повод будить

    @Test
    fun `трафик проходит — заход не будим`() {
        assertFalse(HomeWatch.wake(trafficFlows = true))
    }

    @Test
    fun `трафик не прошёл — будим сразу, не копя отказы`() {
        // Проба ничего не переключает, она только зовёт заход: решение остаётся за
        // задвижкой с её подтверждениями. Копить отказы тут значило бы платить лишней
        // минутой слепоты за то, что и так защищено.
        assertTrue(HomeWatch.wake(trafficFlows = false))
    }
}
