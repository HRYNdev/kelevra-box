package io.nekohasekai.sfa.bg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор списка соединений ядра: адрес → имя сайта.
 *
 * Повод. В строке отказа ядро печатает только адрес, и ось «сайт» в журнале телефона
 * отсутствовала. Диагноз 10.09.2026 приходилось начинать с обратного запроса руками:
 * сначала выяснять, что 2a02:6b8:a::a — это yandex.ru.
 */
class ImenaSaytovTest {
    @After
    fun posle() = ImenaSaytov.zabyt()

    @Test
    fun imya_beretsya_iz_metadata() {
        ImenaSaytov.razobrat(
            """
            {"connections":[
              {"metadata":{"host":"yandex.ru","destinationIP":"2a02:6b8:a::a"}},
              {"metadata":{"host":"mc.yandex.ru","destinationIP":"77.88.55.60"}}
            ]}
            """.trimIndent(),
        )
        assertEquals("yandex.ru", ImenaSaytov.imya("2a02:6b8:a::a"))
        assertEquals("mc.yandex.ru", ImenaSaytov.imya("77.88.55.60"))
    }

    @Test
    fun bez_imeni_ne_vydumyvaem() {
        // Пустой host — соединение шло по адресу, имени нет. Врать в журнал нельзя:
        // выдуманное имя хуже отсутствующего, по нему поставят неверный диагноз.
        ImenaSaytov.razobrat("""{"connections":[{"metadata":{"host":"","destinationIP":"1.2.3.4"}}]}""")
        assertEquals("", ImenaSaytov.imya("1.2.3.4"))
        assertEquals(0, ImenaSaytov.skolkoPomnim())
    }

    @Test
    fun pustoy_i_bityy_otvet_ne_valyat() {
        ImenaSaytov.razobrat("")
        ImenaSaytov.razobrat("{}")
        ImenaSaytov.razobrat("""{"connections":[]}""")
        assertEquals(0, ImenaSaytov.skolkoPomnim())
    }

    @Test
    fun zapominaetsya_i_podmennyy_adres() {
        // При подмене адресов ядро называет оба: по какому пошли и какой отдал резолвер.
        ImenaSaytov.razobrat(
            """{"connections":[{"metadata":{"host":"ya.ru","destinationIP":"198.18.0.7","remoteDestination":"5.255.255.242"}}]}""",
        )
        assertEquals("ya.ru", ImenaSaytov.imya("198.18.0.7"))
        assertEquals("ya.ru", ImenaSaytov.imya("5.255.255.242"))
    }
}
