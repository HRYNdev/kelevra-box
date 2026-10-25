package io.nekohasekai.sfa.bg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Посылка журнала не стартует, пока поднимается своя служба (разбор 24.10.2026: подъём VPN
 * через полсекунды после начала POST /logs обрывал посылку — сервер получал обрывок).
 */
class LogZhdatTunnelTest {

    private fun zhdat(nuzhen: Boolean, podnyatPosle: Int?, srokMs: Long = 20_000L): Pair<Long, Int> {
        var oprosov = 0
        var spali = 0L
        val zhdali = runBlocking {
            LogUploadWork.dozhdatsyaTunnelya(
                nuzhen = { nuzhen },
                podnyat = { oprosov++; podnyatPosle != null && oprosov > podnyatPosle },
                srokMs = srokMs,
                shagMs = 500L,
                spat = { spali += it },
            )
        }
        assertEquals("сколько ждали = сколько спали", spali, zhdali)
        return zhdali to oprosov
    }

    @Test
    fun `служба не нужна — шлём сразу`() {
        assertEquals(0L, zhdat(nuzhen = false, podnyatPosle = null).first)
    }

    @Test
    fun `туннель уже поднят — шлём сразу`() {
        assertEquals(0L, zhdat(nuzhen = true, podnyatPosle = 0).first)
    }

    @Test
    fun `туннель поднимается — ждём его и паузу на смену сети`() {
        // первый опрос «нет», после двух шагов — «да»
        val (zhdali, _) = zhdat(nuzhen = true, podnyatPosle = 2)
        assertEquals(2 * 500L + LogUploadWork.POSLE_PODYOMA_MS, zhdali)
    }

    @Test
    fun `туннель так и не поднялся — шлём по сроку, не вечно`() {
        val (zhdali, _) = zhdat(nuzhen = true, podnyatPosle = null, srokMs = 5_000L)
        assertEquals(5_000L, zhdali)
    }

    @Test
    fun `срок ноль из пульта выключает ожидание`() {
        assertEquals(0L, zhdat(nuzhen = true, podnyatPosle = null, srokMs = 0L).first)
    }
}
