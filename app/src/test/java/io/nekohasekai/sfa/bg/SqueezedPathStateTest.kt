package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Задушенный канал в реестре: для решения это отказ (идти им нельзя), но человеку
 * показывается другое — «не отвечает» про отвечающий канал читается как враньё.
 */
class SqueezedPathStateTest {

    @Test
    fun `задушенность помечается отдельно от молчания`() {
        PathRegistry.dead(PathId.MAIN, "канал задушен: 6 КБ/с", squeezed = true)
        val path = PathRegistry.snapshot.value[PathId.MAIN]
        assertEquals(PathStatus.Dead, path.status)
        assertTrue("идти таким путём нельзя", path.refused)
        assertTrue("но человеку это не «молчит»", path.squeezed)
    }

    @Test
    fun `обычный отказ задушенностью не считается`() {
        PathRegistry.dead(PathId.MAIN, "адрес узла не отвечает")
        assertFalse(PathRegistry.snapshot.value[PathId.MAIN].squeezed)
    }

    @Test
    fun `оживший путь метку задушенности не тащит`() {
        PathRegistry.dead(PathId.MAIN, "канал задушен: 6 КБ/с", squeezed = true)
        PathRegistry.alive(PathId.MAIN, 120)
        val path = PathRegistry.snapshot.value[PathId.MAIN]
        assertTrue(path.usable)
        assertFalse("после успеха метка обязана слететь", path.squeezed)
    }
}
