package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Удаление окон сырья ([CoreLog.staryeFajlyKUdaleniyu]).
 *
 * Повод. Наряд 0912: `sohranitOknoSyrya` держала не больше двух файлов и вычищала
 * остальные, глядя ТОЛЬКО на возраст (`dropLast(2)` после сортировки по имени). При
 * устойчивом эпизоде — тревога каждый час («ядро валит соединения»), а отправка не
 * проходит много часов подряд («VPN без сети») — третий файл вытеснял ПЕРВЫЙ, ещё не
 * уехавший. Терялось ровно то окно, ради которого механизм и написан.
 */
class CoreLogOknoSyryaTest {

    /** Три тревоги подряд, отправка недоступна: ни один файл не уехал. */
    private val triTrevogiBezOtpravki = listOf(
        "kelevra-syrye-0910-090000.log",
        "kelevra-syrye-0910-100000.log",
        "kelevra-syrye-0910-110000.log",
    )

    @Test
    fun nichego_ne_otpravleno_pervyy_fayl_dolzhen_vyzhit() {
        // Сценарий «VPN без сети»: sohranitOknoSyrya не знает ни про один отправленный
        // файл. Прежняя формула (dropLast(2), удалить остальное) стёрла бы первый файл
        // безусловно — этот тест доказывает, что новая так не делает.
        val kUdaleniyu = CoreLog.staryeFajlyKUdaleniyu(
            imena = triTrevogiBezOtpravki,
            otpravleny = emptySet(),
        )
        assertTrue(
            "первый (ещё не отправленный) файл не должен уходить в удаление: $kUdaleniyu",
            "kelevra-syrye-0910-090000.log" !in kUdaleniyu,
        )
        assertEquals(emptyList<String>(), kUdaleniyu)
    }

    @Test
    fun otpravlennyy_staryy_fayl_udalyaetsya() {
        // Первый файл честно уехал — держать его больше незачем, третий (новее) занял
        // его место в кэпе.
        val kUdaleniyu = CoreLog.staryeFajlyKUdaleniyu(
            imena = triTrevogiBezOtpravki,
            otpravleny = setOf("kelevra-syrye-0910-090000.log"),
        )
        assertEquals(listOf("kelevra-syrye-0910-090000.log"), kUdaleniyu)
    }

    /** Тревога раз в час, связи нет сутками: столько окон накопится на диске. */
    private fun okna(skolko: Int) =
        (0 until skolko).map { "kelevra-syrye-0910-%02d0000.log".format(it) }

    @Test
    fun pod_potolkom_neotpravlennoe_zhivet_vsyo() {
        // Восемь окон без единой отправки — это ~2 МБ, терпимо: не трогаем ничего.
        assertEquals(
            emptyList<String>(),
            CoreLog.staryeFajlyKUdaleniyu(imena = okna(8), otpravleny = emptySet()),
        )
    }

    @Test
    fun sverh_potolka_uhodyat_samye_starye_dazhe_neotpravlennye() {
        // Десять окон, отправки не было ни одной: жалеть неотправленное дальше нельзя —
        // иначе хранилище телефона забьётся ровно так, как боялись до починки. Уходят
        // два самых старых, самое свежее (ближе к беде) остаётся.
        val kUdaleniyu = CoreLog.staryeFajlyKUdaleniyu(imena = okna(10), otpravleny = emptySet())
        assertEquals(
            listOf("kelevra-syrye-0910-000000.log", "kelevra-syrye-0910-010000.log"),
            kUdaleniyu,
        )
        assertTrue(
            "самое свежее окно не удаляем никогда: $kUdaleniyu",
            "kelevra-syrye-0910-090000.log" !in kUdaleniyu,
        )
    }

    @Test
    fun v_predelah_kepa_nichego_ne_trogaem() {
        assertEquals(
            emptyList<String>(),
            CoreLog.staryeFajlyKUdaleniyu(
                imena = listOf("kelevra-syrye-0910-090000.log", "kelevra-syrye-0910-100000.log"),
                otpravleny = setOf("kelevra-syrye-0910-090000.log", "kelevra-syrye-0910-100000.log"),
            ),
        )
    }
}
