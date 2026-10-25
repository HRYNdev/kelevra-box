package io.nekohasekai.sfa.compose.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Стрелка шапки панели сети ведёт себя как системный «назад»: сначала история, потом выход. */
class PanelNazadTest {
    private fun shag(estIstoriya: Boolean): String {
        var chto = ""
        nazadPaneli(estIstoriya, { chto = "istoriya" }, { chto = "zakryt" })
        return chto
    }

    @Test
    fun `на подстранице — шаг назад по истории, панель не закрывается`() {
        assertEquals("istoriya", shag(true))
    }

    @Test
    fun `на первой странице — панель закрывается`() {
        assertEquals("zakryt", shag(false))
    }
}
