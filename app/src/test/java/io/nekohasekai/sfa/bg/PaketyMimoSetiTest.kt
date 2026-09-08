package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет разбор списка «мимо сети» из конфига.
 *
 * Тест закрывает поломку, которую 08.09 нашли по жалобе: отмеченные приложения
 * до ядра не доезжали, и настройка выглядела мёртвой. Разбор конфига — та половина
 * лечения, которую можно проверить без телефона.
 */
class PaketyMimoSetiTest {

    private fun konfigSTunom(pakety: String) = """
        {
          "inbounds": [
            {
              "tag": "tun-in",
              "type": "tun",
              "exclude_package": [$pakety]
            },
            { "tag": "mixed-in", "type": "mixed", "listen_port": 2412 }
          ]
        }
    """.trimIndent()

    @Test
    fun `берёт пакеты из входа tun`() {
        val itog = PaketyMimoSeti.izKonfiga(
            konfigSTunom(""""ru.gosuslugi.dom", "ru.burgerking", "com.ru.verniy""""),
        )
        assertEquals(setOf("ru.gosuslugi.dom", "ru.burgerking", "com.ru.verniy"), itog)
    }

    @Test
    fun `порядок сохраняется, пустые строки отбрасываются`() {
        val itog = PaketyMimoSeti.izKonfiga(konfigSTunom(""""ru.nalog.app", "", "ru.burgerking""""))
        assertEquals(listOf("ru.nalog.app", "ru.burgerking"), itog.toList())
    }

    @Test
    fun `нет поля — пустой список, а не отказ`() {
        val bezPolya = """{"inbounds":[{"tag":"tun-in","type":"tun"}]}"""
        assertTrue(PaketyMimoSeti.izKonfiga(bezPolya).isEmpty())
    }

    @Test
    fun `чужие входы не учитываются`() {
        val chuzhoy = """
            {"inbounds":[{"type":"mixed","exclude_package":["ru.burgerking"]}]}
        """.trimIndent()
        assertTrue(PaketyMimoSeti.izKonfiga(chuzhoy).isEmpty())
    }

    @Test
    fun `битый конфиг не роняет подъём туннеля`() {
        assertTrue(PaketyMimoSeti.izKonfiga("это вообще не json").isEmpty())
        assertTrue(PaketyMimoSeti.izKonfiga("").isEmpty())
    }
}
