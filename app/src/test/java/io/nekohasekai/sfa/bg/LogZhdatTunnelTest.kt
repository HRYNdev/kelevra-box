package io.nekohasekai.sfa.bg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Посылка журнала не стартует, пока поднимается своя служба (разбор 24.10.2026: подъём VPN
 * через полсекунды после начала POST /logs обрывал посылку — сервер получал обрывок).
 */
class LogZhdatTunnelTest {

    private fun zhdat(
        nuzhen: Boolean,
        podnyatPosle: Int?,
        srokMs: Long = 20_000L,
        domaPosle: Int? = null,
    ): Pair<Long, Int> {
        var oprosov = 0
        var spali = 0L
        val zhdali = runBlocking {
            LogUploadWork.dozhdatsyaTunnelya(
                nuzhen = { nuzhen },
                podnyat = { oprosov++; podnyatPosle != null && oprosov > podnyatPosle },
                doma = { domaPosle != null && oprosov > domaPosle },
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

    @Test
    fun `дома на входе — не ждём вовсе`() {
        // domaPosle = -1: doma() истинна уже на первом опросе (oprosov = 0)
        assertEquals(0L, zhdat(nuzhen = true, podnyatPosle = null, domaPosle = -1).first)
    }

    @Test
    fun `дома выяснилось на третьем шаге — выходим, не достаивая до срока`() {
        val (zhdali, _) = zhdat(nuzhen = true, podnyatPosle = null, domaPosle = 3)
        assertEquals(3 * 500L, zhdali)
    }
}
