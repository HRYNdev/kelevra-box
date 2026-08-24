package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.DefaultNetworkMonitor.Iface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Погоня за интерфейсом вернувшейся сети. Прежний код ждал свойства сети секунду
 * (десять раз по 100 мс прямо в ConnectivityThread) и, не дождавшись, замолкал навсегда:
 * ядро оставалось с «интерфейса нет», приложения с долгими сессиями не оживали до
 * следующего шевеления сети. Здесь проверяется чистая часть новой погони.
 */
class DefaultNetworkMonitorTest {

    @Test
    fun `окно погони перекрывает подъём сети после полного пропадания`() {
        val total = (0 until DefaultNetworkMonitor.CHASE_ATTEMPTS)
            .sumOf { DefaultNetworkMonitor.retryDelayMs(it) }
        assertTrue(
            "погоня должна ждать хотя бы полминуты, а ждёт $total мс",
            total >= 30_000,
        )
    }

    @Test
    fun `первая секунда опрашивается часто`() {
        val firstFive = (0 until 5).sumOf { DefaultNetworkMonitor.retryDelayMs(it) }
        assertEquals(500L, firstFive)
    }

    @Test
    fun `паузы не убывают`() {
        var previous = 0L
        for (attempt in 0 until DefaultNetworkMonitor.CHASE_ATTEMPTS) {
            val delay = DefaultNetworkMonitor.retryDelayMs(attempt)
            assertTrue("пауза уменьшилась на попытке $attempt", delay >= previous)
            previous = delay
        }
    }

    @Test
    fun `тот же интерфейс ядру повторно не шлём`() {
        val iface = Iface("wlan0", 12)
        assertFalse(DefaultNetworkMonitor.shouldReport(iface, iface))
        assertFalse(DefaultNetworkMonitor.shouldReport(iface, Iface("wlan0", 12)))
    }

    @Test
    fun `смена интерфейса и пропажа сети доезжают до ядра`() {
        val wifi = Iface("wlan0", 12)
        val cellular = Iface("rmnet0", 30)
        assertTrue(DefaultNetworkMonitor.shouldReport(wifi, cellular))
        assertTrue("пропажу сети ядро обязано узнать", DefaultNetworkMonitor.shouldReport(wifi, null))
        assertTrue("возврат сети ядро обязано узнать", DefaultNetworkMonitor.shouldReport(null, wifi))
    }

    @Test
    fun `индекс интерфейса различается при том же имени`() {
        // Имя переиспользуется системой, индекс — нет: ядру важен именно индекс.
        assertTrue(DefaultNetworkMonitor.shouldReport(Iface("rmnet0", 30), Iface("rmnet0", 31)))
    }
}
