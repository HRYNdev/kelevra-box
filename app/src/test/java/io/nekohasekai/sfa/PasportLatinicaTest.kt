package io.nekohasekai.sfa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Оператор в паспорте устройства: заголовок обязан быть ASCII, и «МТС» без латиницы
 * превращался бы в «???».
 */
class PasportLatinicaTest {
    @Test
    fun `кириллица латиницей, латиница как есть`() {
        assertEquals("MTS", Kelevra.latinica("МТС"))
        assertEquals("Bilayn", Kelevra.latinica("Билайн"))
        assertEquals("MegaFon", Kelevra.latinica("MegaFon"))
        assertEquals("Tele2", Kelevra.latinica("Tele2"))
        assertEquals("Shch", Kelevra.latinica("Шч"))
    }
}
