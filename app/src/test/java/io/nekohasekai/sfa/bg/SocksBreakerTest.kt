package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Предохранитель на шторм отказов в локальный сокс: порог, окно, пауза, чужие адреса. */
class SocksBreakerTest {

    /** Настоящая строка ядра из журнала 12.09.2026. */
    private val otkaz =
        "09-12 14:07:33.566 ERROR[0423] [3944170925 1ms] connection: open connection to " +
            "149.154.167.51:443 using outbound/socks[Комната]: dial rmnet_data2 (22): " +
            "dial tcp 127.0.0.1:8808: connect: connection refused"

    private fun burst(b: SocksBreaker, count: Int, start: Long, stepMillis: Long): Int =
        (0 until count).count { b.offer(otkaz, start + it * stepMillis) }

    @Test
    fun `сорок девять отказов за две секунды ещё не шторм`() {
        assertEquals(0, burst(SocksBreaker(), 49, start = 0, stepMillis = 10))
    }

    @Test
    fun `пятьдесят отказов за две секунды срабатывают ровно один раз`() {
        assertEquals(1, burst(SocksBreaker(), 50, start = 0, stepMillis = 10))
    }

    @Test
    fun `повтор в пределах паузы не срабатывает`() {
        val b = SocksBreaker()
        assertEquals(1, burst(b, 50, start = 0, stepMillis = 10))
        assertEquals(0, burst(b, 50, start = 5_000, stepMillis = 10))
    }

    @Test
    fun `после паузы предохранитель снова взводится`() {
        val b = SocksBreaker()
        assertEquals(1, burst(b, 50, start = 0, stepMillis = 10))
        assertEquals(1, burst(b, 50, start = 40_000, stepMillis = 10))
    }

    @Test
    fun `те же пятьдесят отказов, растянутые на десять секунд, не шторм`() {
        assertEquals(0, burst(SocksBreaker(), 50, start = 0, stepMillis = 200))
    }

    @Test
    fun `отказы на чужой адрес не считаются`() {
        val chuzhoy = "ERROR open connection to 1.2.3.4:443 using outbound/direct[direct]: " +
            "dial tcp 1.2.3.4:443: connect: connection refused"
        val b = SocksBreaker()
        assertFalse((0 until 100).any { b.offer(chuzhoy, it * 10L) })
    }

    @Test
    fun `таймаут на петлю отказом сокса не считается`() {
        val taymaut = otkaz.replace("connection refused", "i/o timeout")
        val b = SocksBreaker()
        assertFalse((0 until 100).any { b.offer(taymaut, it * 10L) })
        assertTrue(burst(b, 50, start = 10_000, stepMillis = 10) == 1)
    }
}
