package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.LogUploadWork.ИтогПопытки
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * Квитанция пульта на «пришли журнал»: `ok` значит «сервер принял», как у Desktop,
 * а не «просьба встала в очередь».
 */
class ZhurnalKvitanciyaTest {
    private val ITOG_SERVERA = Regex("""^(ok(-[A-Za-z0-9_.\-]{1,40})?|ne_umeyu|istekla|oshibka(-[A-Za-z0-9_.\-]{1,40})?)$""")

    @Test
    fun ok_tolko_kogda_server_prinyal_ili_nechego() {
        assertEquals("ok", LogUploadWork.slovoKvitancii(ИтогПопытки.Отправлено))
        assertEquals("ok", LogUploadWork.slovoKvitancii(ИтогПопытки.Нечего))
    }

    @Test
    fun ne_doehali_ili_ne_dozhdalis__oshibka_set() {
        assertEquals("oshibka-set", LogUploadWork.slovoKvitancii(ИтогПопытки.НетСвязи))
        assertEquals("oshibka-set", LogUploadWork.slovoKvitancii(null))
    }

    @Test
    fun server_otvetil_no_ne_prinyal__oshibka_otkaz() {
        assertEquals("oshibka-otkaz", LogUploadWork.slovoKvitancii(ИтогПопытки.Отвергнуто))
        assertEquals("oshibka-otkaz", LogUploadWork.slovoKvitancii(ИтогПопытки.Выброшено))
        assertEquals("oshibka-otkaz", LogUploadWork.slovoKvitancii(ИтогПопытки.Сбой))
    }

    @Test
    fun vse_slova_prohodyat_razbor_servera() {
        (ИтогПопытки.entries + listOf<ИтогПопытки?>(null)).forEach {
            assertTrue("$it", ITOG_SERVERA.matches(LogUploadWork.slovoKvitancii(it)))
        }
    }

    /** Повтор работы — прежний: как runOnce до правки (true/false). */
    @Test
    fun povtor_raboty_kak_ranshe() {
        assertTrue(LogUploadWork.razobrano(ИтогПопытки.Отправлено))
        assertTrue(LogUploadWork.razobrano(ИтогПопытки.Нечего))
        assertTrue(LogUploadWork.razobrano(ИтогПопытки.Выброшено))
        assertFalse(LogUploadWork.razobrano(ИтогПопытки.Отвергнуто))
        assertFalse(LogUploadWork.razobrano(ИтогПопытки.Сбой))
        assertFalse(LogUploadWork.razobrano(ИтогПопытки.НетСвязи))
    }

    /** Подстраховка WorkManager ставится до попытки и ровно один раз — и при неудаче тоже. */
    @Test
    fun podstrahovka_do_popytki_i_odna() = runBlocking {
        for (ishod in ИтогПопытки.entries) {
            val poryadok = mutableListOf<String>()
            val slovo = LogUploadWork.otpravitDlyaPulta(
                "тест",
                srokMs = 5_000,
                podstrahovka = { poryadok += "ochered" },
                poprobovat = { poryadok += "popytka"; ishod },
            )
            assertEquals("$ishod", listOf("ochered", "popytka"), poryadok)
            assertEquals(LogUploadWork.slovoKvitancii(ishod), slovo)
        }
    }

    /** Сервер молчит дольше срока — квитанция oshibka-set, лишней постановки нет. */
    @Test
    fun ne_dozhdalis__oshibka_set() = runBlocking {
        var postavleno = 0
        val nikogda = CompletableDeferred<ИтогПопытки>()
        val slovo = LogUploadWork.otpravitDlyaPulta(
            "тест",
            srokMs = 100,
            podstrahovka = { postavleno++ },
            poprobovat = { nikogda.await() },
        )
        nikogda.complete(ИтогПопытки.Отправлено)
        assertEquals("oshibka-set", slovo)
        assertEquals(1, postavleno)
    }

    /** Попытка упала исключением — до сервера не доехали. */
    @Test
    fun popytka_upala__oshibka_set() = runBlocking {
        val slovo = LogUploadWork.otpravitDlyaPulta("тест", srokMs = 5_000, podstrahovka = {}, poprobovat = { error("сбой") })
        assertEquals("oshibka-set", slovo)
    }
}
