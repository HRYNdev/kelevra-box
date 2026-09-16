package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Будильник цикла автомата: событие не теряется, если цикл его не ждал.
 *
 * Сроки взяты с большим запасом в обе стороны: «сразу» — это меньше 2 секунд при сне
 * на минуту, «ждёт» — не раньше срока. Медленная машина CI это не ломает.
 */
class AutoModeAlarmTest {

    private fun millisOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun `побудка до сна не теряется — сон на минуту возвращается сразу`() {
        // Ровно баг 16.09.2026: человек выбрал выход, пока цикл был занят заходом.
        val alarm = AutoModeAlarm()
        alarm.wake()
        var woken = false
        val spent = millisOf { woken = alarm.sleep(60_000L) }
        assertTrue("разбудило событие", woken)
        assertTrue("спал $spent мс", spent < 2_000L)
    }

    @Test
    fun `побудка до вечного сна тоже не теряется`() {
        val alarm = AutoModeAlarm()
        alarm.wake()
        var woken = false
        val spent = millisOf { woken = alarm.sleep(Long.MAX_VALUE) }
        assertTrue(woken)
        assertTrue("спал $spent мс", spent < 2_000L)
    }

    @Test
    fun `побудка во время сна будит`() {
        val alarm = AutoModeAlarm()
        val asleep = CountDownLatch(1)
        val done = CountDownLatch(1)
        val woken = AtomicBoolean(false)
        val spent = AtomicLong(-1)
        val sleeper = Thread {
            asleep.countDown()
            val start = System.nanoTime()
            woken.set(alarm.sleep(60_000L))
            spent.set((System.nanoTime() - start) / 1_000_000L)
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(asleep.await(10, TimeUnit.SECONDS))
        // Даём потоку действительно заснуть; даже если не успел — отметка его всё равно разбудит.
        Thread.sleep(200)
        alarm.wake()
        assertTrue("не проснулся за 10 с", done.await(10, TimeUnit.SECONDS))
        assertTrue(woken.get())
        assertTrue("спал ${spent.get()} мс", spent.get() < 10_000L)
        sleeper.join(1_000)
    }

    @Test
    fun `вечный сон будится побудкой`() {
        val alarm = AutoModeAlarm()
        val done = CountDownLatch(1)
        Thread {
            alarm.sleep(Long.MAX_VALUE)
            done.countDown()
        }.apply { isDaemon = true; start() }
        Thread.sleep(200)
        alarm.wake()
        assertTrue("не проснулся за 10 с", done.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `без побудки сон ждёт свой срок`() {
        val alarm = AutoModeAlarm()
        var woken = true
        val spent = millisOf { woken = alarm.sleep(150L) }
        assertFalse("вышел срок, а не событие", woken)
        assertTrue("спал $spent мс, а срок 150", spent >= 140L)
        assertTrue("спал $spent мс", spent < 5_000L)
    }

    @Test
    fun `несколько побудок подряд дают один немедленный сон, а не вечное бодрствование`() {
        val alarm = AutoModeAlarm()
        repeat(5) { alarm.wake() }
        var first = false
        val firstSpent = millisOf { first = alarm.sleep(60_000L) }
        assertTrue(first)
        assertTrue("первый сон $firstSpent мс", firstSpent < 2_000L)

        var second = true
        val secondSpent = millisOf { second = alarm.sleep(150L) }
        assertFalse("второй сон должен дождаться срока", second)
        assertTrue("второй сон $secondSpent мс, а срок 150", secondSpent >= 140L)
    }

    @Test
    fun `сброс забывает несъеденную побудку`() {
        val alarm = AutoModeAlarm()
        alarm.wake()
        alarm.reset()
        var woken = true
        val spent = millisOf { woken = alarm.sleep(150L) }
        assertFalse(woken)
        assertTrue("спал $spent мс", spent >= 140L)
    }
}
