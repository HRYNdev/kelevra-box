package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение logcat не кладёт в файл того, что [KLog] уже записал напрямую, и паспорт
 * называет состояние logcat.
 *
 * Повод. С 21.10.2026 KLog пишет в файл всегда (Huawei режет logcat в ноль, Honor — до E).
 * Там, где logcat жив, те же строки приходят из него второй раз: строки своего процесса
 * с тегом KLog отбрасываются, остальное (чужие библиотеки, AndroidRuntime) — пишется.
 */
class AppLogDublTest {
    private val pid = 30608
    private val tegi = setOf("AutoMode", "BoxService", "Vid", "KelevraSubscription")

    /** Строка в точности как отдаёт `logcat -v threadtime`: тег добит пробелами до 8 знаков. */
    private fun logcat(teg: String, uroven: Char = 'I', telo: String = "что-то", p: Int = pid) =
        "10-21 13:07:01.123 ${p.toString().padStart(5)} ${(p + 12).toString().padStart(5)} $uroven ${teg.padEnd(8)}: $telo"

    @Test
    fun `строка своего процесса с тегом KLog — дубль, в файл не идёт`() {
        assertFalse(AppLog.vFayl(logcat("AutoMode", telo = "заход: обычная"), pid, tegi))
        assertFalse(AppLog.vFayl(logcat("KelevraSubscription", telo = "обновление подписки: запрашиваю сводку"), pid, tegi))
        // Короткий тег logcat добивает пробелами — узнаётся всё равно.
        assertFalse(AppLog.vFayl(logcat("Vid", 'W', "вид не разобран"), pid, tegi))
        // Продолжение многострочного сообщения идёт с тем же тегом — тоже дубль.
        assertFalse(AppLog.vFayl(logcat("BoxService", 'E', "Caused by: java.io.IOException: x"), pid, tegi))
    }

    @Test
    fun `остальное из logcat своего процесса пишется как раньше`() {
        assertTrue(AppLog.vFayl(logcat("AndroidRuntime", 'E', "FATAL EXCEPTION: main"), pid, tegi))
        assertTrue(AppLog.vFayl(logcat("chromium", 'I', "[INFO:CONSOLE] загрузка"), pid, tegi))
        assertTrue(AppLog.vFayl(logcat("WM-WorkerWrapper", 'I', "Worker result SUCCESS"), pid, tegi))
    }

    @Test
    fun `тег KLog из чужого процесса не отбрасывается`() {
        assertTrue(AppLog.vFayl(logcat("AutoMode", p = 4242), pid, tegi))
    }

    @Test
    fun `шум, маркер самопроверки и непонятное`() {
        assertFalse(AppLog.vFayl(logcat("NotificationManager", telo = "notify"), pid, tegi))
        assertFalse(AppLog.vFayl(logcat("KelevraLogcatCheck", 'D', "самопроверка logcat 1a2b D"), pid, tegi))
        // Служебную строку без головы не теряем.
        assertTrue(AppLog.vFayl("--------- beginning of main", pid, tegi))
    }

    @Test
    fun `тег, которым KLog писал, отбрасывается из logcat`() {
        KLog.i("AppLogDublTestTeg", "раз")
        assertFalse(AppLog.vFayl(logcat("AppLogDublTestTeg", telo = "раз"), pid, KLog.tegi))
        assertTrue(AppLog.vFayl(logcat("AppLogDublTestDrugoy", telo = "два"), pid, KLog.tegi))
    }

    @Test
    fun `паспорт logcat`() {
        assertEquals("ok", AppLog.opisanie(setOf('D', 'I', 'W', 'E')))
        assertEquals("rezhet_DIW", AppLog.opisanie(setOf('E')))
        assertEquals("rezhet_D", AppLog.opisanie(setOf('I', 'W', 'E')))
        assertEquals("net", AppLog.opisanie(emptySet()))
        // Самопроверка в юнит-тестах не запускалась.
        assertEquals("?", AppLog.priznakLogcat())
    }
}
