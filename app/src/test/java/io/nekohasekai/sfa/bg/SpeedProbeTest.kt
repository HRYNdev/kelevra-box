package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.path.SpeedProbe
import io.nekohasekai.sfa.bg.path.SpeedProbe.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замер полосы. Обычная проба весит двести байт и проходит сквозь троттлинг целой,
 * поэтому «жив за 254 мс» ничего не говорит о том, можно ли этим каналом пользоваться.
 */
class SpeedProbeTest {

    @Test
    fun `скорость считается из объёма и времени`() {
        assertEquals(100 * 1024L, Speed.Measured(bytes = 100 * 1024, millis = 1000).bytesPerSecond)
        assertEquals(50 * 1024L, Speed.Measured(bytes = 100 * 1024, millis = 2000).bytesPerSecond)
    }

    @Test
    fun `замер за нулевое время не выдаёт бесконечность`() {
        assertEquals(0L, Speed.Measured(bytes = 4096, millis = 0).bytesPerSecond)
    }

    @Test
    fun `задушенный канал виден`() {
        // 96 КБ за 15 секунд — это 6 КБ/с, то самое «ничего не грузится».
        assertTrue(SpeedProbe.squeezed(Speed.Measured(bytes = 96 * 1024, millis = 15_000)))
    }

    @Test
    fun `нормальный канал задушенным не считается`() {
        // 96 КБ за 400 мс — 240 КБ/с, обычная мобильная сеть через туннель.
        assertFalse(SpeedProbe.squeezed(Speed.Measured(bytes = 96 * 1024, millis = 400)))
    }

    @Test
    fun `граница порога не срабатывает на самом пороге`() {
        val exactly = Speed.Measured(bytes = SpeedProbe.SQUEEZED_BYTES_PER_SEC.toInt(), millis = 1000)
        assertFalse("ровно на пороге канал ещё годен", SpeedProbe.squeezed(exactly))
    }

    @Test
    fun `несостоявшийся замер не объявляет канал задушенным`() {
        // Иначе одна осечка увела бы человека с рабочего канала.
        assertFalse(SpeedProbe.squeezed(Speed.Failed("тело не пришло вовсе")))
        assertFalse(SpeedProbe.squeezed(Speed.Unmeasurable("нет входа")))
    }

    @Test
    fun `первый замер разрешён, повторный сразу — нет`() {
        assertTrue("на холодную мерить можно", SpeedProbe.due(now = 1_000L))
    }
}
