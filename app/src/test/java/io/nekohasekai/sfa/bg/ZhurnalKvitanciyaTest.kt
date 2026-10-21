package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.LogUploadWork.ИтогПопытки
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
