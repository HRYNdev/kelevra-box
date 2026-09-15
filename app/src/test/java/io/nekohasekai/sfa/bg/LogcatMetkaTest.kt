package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перезапуск чтения logcat не пишет буфер заново.
 *
 * Повод. После каждого обрыва logcat поднимался без `-T` и отдавал весь буфер процесса
 * с начала: строки ложились в журнал второй раз и уезжали в архив дублями.
 */
class LogcatMetkaTest {
    private fun stroka(vremya: String, telo: String) = "09-13 $vremya  4242  4250 I AutoMode: $telo"

    @Test
    fun pervyy_zapusk_bez_otmetki() {
        assertEquals(listOf("logcat", "-v", "threadtime", "--pid", "4242"), LogcatMetka().komanda(4242))
        assertEquals(listOf("logcat", "-v", "threadtime"), LogcatMetka().komanda(null))
    }

    @Test
    fun perezapusk_prodolzhaet_s_posledney_stroki() {
        val metka = LogcatMetka()
        metka.uzhePisali(stroka("15:08:00.100", "раз"))
        metka.uzhePisali(stroka("15:08:01.250", "два"))
        assertEquals(
            listOf("logcat", "-v", "threadtime", "-T", "09-13 15:08:01.250", "--pid", "4242"),
            metka.komanda(4242),
        )
    }

    @Test
    fun povtor_na_otmetke_otsekaetsya_novoe_prohodit() {
        val metka = LogcatMetka()
        // Первый запуск: три строки, две из них в одну миллисекунду.
        assertFalse(metka.uzhePisali(stroka("15:08:00.100", "раз")))
        assertFalse(metka.uzhePisali(stroka("15:08:01.250", "два")))
        assertFalse(metka.uzhePisali(stroka("15:08:01.250", "три")))
        // Перезапуск — в drain() он всегда начинается именно с komanda(), она и есть
        // сигнал границы; без него это просто повтор строк в той же сессии, не рестарт.
        metka.komanda(4242)
        // С -T 15:08:01.250 logcat отдаёт эту миллисекунду ещё раз, потом новое.
        assertTrue(metka.uzhePisali(stroka("15:08:01.250", "два")))
        assertTrue(metka.uzhePisali(stroka("15:08:01.250", "три")))
        assertFalse(metka.uzhePisali(stroka("15:08:01.250", "четыре")))
        assertFalse(metka.uzhePisali(stroka("15:09:00.000", "пять")))
    }

    @Test
    fun odinakovye_stroki_v_raznoe_vremya_ne_dubli() {
        // Повтор одной и той же записи в разные моменты — настоящие события.
        val metka = LogcatMetka()
        assertFalse(metka.uzhePisali(stroka("15:08:00.100", "проба")))
        assertFalse(metka.uzhePisali(stroka("15:08:05.100", "проба")))
    }

    @Test
    fun odna_i_ta_zhe_stroka_dvazhdy_podryad_bez_perezapuska_obe_prohodyat() {
        // Без перезапуска logcat две ОДИНАКОВЫЕ строки в одну и ту же миллисекунду —
        // законное совпадение (например, два одинаковых по тексту события подряд), а
        // не повтор буфера. Дедуп по СОДЕРЖИМОМУ вторую строку тут молча теряет.
        val metka = LogcatMetka()
        assertFalse(metka.uzhePisali(stroka("15:08:01.250", "тик")))
        assertFalse(metka.uzhePisali(stroka("15:08:01.250", "тик")))
    }

    @Test
    fun stroki_bez_vremeni_ne_dvigayut_otmetku() {
        val metka = LogcatMetka()
        metka.uzhePisali(stroka("15:08:00.100", "раз"))
        assertFalse(metka.uzhePisali("--------- beginning of main"))
        assertFalse(metka.uzhePisali("--------- beginning of main"))
        assertEquals(listOf("logcat", "-v", "threadtime", "-T", "09-13 15:08:00.100"), metka.komanda(null))
    }
}
