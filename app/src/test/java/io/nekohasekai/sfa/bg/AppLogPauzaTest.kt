package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Пауза перезапуска чтения logcat.
 *
 * Повод. Handoff 28.10.2026: на Huawei чтение logcat обрывалось ~каждые 44 с
 * (1147 раз за 8 дней), а фиксированная 30-секундная пауза между КАЖДЫМ обрывом
 * держала журнал слепым полминуты при каждом из них. Проверяется чистая функция
 * [AppLog.pauzaPosle]: первый обрыв поднимаем почти сразу, дальше — рост вдвое,
 * не выше потолка.
 */
class AppLogPauzaTest {
    @Test
    fun pervyy_obryv_pochti_srazu() {
        assertEquals(2_000L, AppLog.pauzaPosle(0))
    }

    @Test
    fun podryad_idushchie_obryvy_rastut_vdvoe() {
        assertEquals(2_000L, AppLog.pauzaPosle(0))
        assertEquals(4_000L, AppLog.pauzaPosle(1))
        assertEquals(8_000L, AppLog.pauzaPosle(2))
        assertEquals(16_000L, AppLog.pauzaPosle(3))
    }

    @Test
    fun rost_ne_perelezaet_potolok() {
        // Красный контроль: если AppLog.pauzaPosle всегда возвращает фиксированные
        // 30_000 (старое поведение до правки), этот тест и test выше не различили бы
        // ступени роста — здесь явно проверяем, что потолок НЕ достигается с первого
        // же обрыва, а только после нескольких подряд.
        assertEquals(30_000L, AppLog.pauzaPosle(4))
        assertEquals(30_000L, AppLog.pauzaPosle(5))
        assertEquals(30_000L, AppLog.pauzaPosle(100))
    }

    @Test
    fun otricatelnyy_podryad_ne_lomaet_pauzu() {
        assertEquals(2_000L, AppLog.pauzaPosle(-1))
    }

    @Test
    fun korotkoe_chtenie_ne_sbrasyvaet_podryad() {
        assertEquals(false, AppLog.sbrositPodryad(5_000L))
        assertEquals(false, AppLog.sbrositPodryad(59_999L))
    }

    @Test
    fun dolgoe_chtenie_sbrasyvaet_podryad() {
        // Минута здорового чтения — серия быстрых обрывов закончилась, следующий
        // обрыв получит паузу заново с нуля (2 с), а не разогнанную.
        assertEquals(true, AppLog.sbrositPodryad(60_000L))
        assertEquals(true, AppLog.sbrositPodryad(120_000L))
    }
}
