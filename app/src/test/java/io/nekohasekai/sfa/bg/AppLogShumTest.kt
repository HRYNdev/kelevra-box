package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отсев чужого шума из журнала приложения.
 *
 * Повод. Замер 10.09.2026 по суточному журналу телефона: из 19 520 строк 11 853 — это
 * системный `NotificationManager` про перерисовку нашего же значка в шторке, ещё
 * 3 219 — `ViewRootImpl` про включение экрана. Полезного нашего оставалось около
 * десятой части, и журнал занимал все свои 14 МБ, вытесняя историю.
 *
 * Проверяется именно чистая функция, а не поведение с живым logcat: важно, что
 * НАШИ строки остаются, включая исходы фоновых задач, по которым и разбирается
 * «профиль не обновился».
 */
class AppLogShumTest {
    private fun stroka(teg: String, uroven: String = "D", telo: String = "что-то") =
        "09-10 21:05:00.123 30608 30608 $uroven $teg: $telo"

    @Test
    fun chuzhoy_shum_otsekaetsya() {
        assertTrue(AppLog.shumnaya(stroka("NotificationManager", telo = "notify(1, null)")))
        assertTrue(AppLog.shumnaya(stroka("ViewRootImpl", telo = "onDisplayChanged")))
        assertTrue(AppLog.shumnaya(stroka("InsetsSource")))
        assertTrue(AppLog.shumnaya(stroka("HWUI")))
        assertTrue(AppLog.shumnaya(stroka("System.err", uroven = "W")))
    }

    @Test
    fun svoi_stroki_ostayutsya() {
        assertFalse(AppLog.shumnaya(stroka("AutoMode", "I", "вердикт «дома»: да")))
        assertFalse(AppLog.shumnaya(stroka("BoxService", "I", "сервис запущен")))
        assertFalse(AppLog.shumnaya(stroka("VPNService", "D", "addDisallowedApplication: ru.nalog.app")))
        assertFalse(AppLog.shumnaya(stroka("UpdateProfileWork", "E", "java.lang.RuntimeException")))
        assertFalse(AppLog.shumnaya(stroka("KelevraLogUpload", "I", "логи отправлены")))
    }

    @Test
    fun ishod_fonovoy_zadachi_ostaetsya() {
        // Именно по этим строкам 10.09.2026 выяснилось, что профиль в итоге
        // забирается повторами, а не теряется навсегда.
        assertFalse(AppLog.shumnaya(stroka("WM-WorkerWrapper", "I", "Worker result RETRY")))
        assertFalse(AppLog.shumnaya(stroka("WM-WorkerWrapper", "I", "Worker result SUCCESS")))
    }

    @Test
    fun prodolzhenie_stektreysa_otsekaetsya() {
        assertTrue(AppLog.shumnaya("09-10 21:05:00.123 30608 30608 E X: \tat io.nekohasekai.sfa.bg.X"))
    }

    @Test
    fun neponyatnaya_stroka_ostaetsya() {
        // Потерять непонятное хуже, чем возить лишнее: свой формат, шапка журнала,
        // строка ядра — всё это должно доехать.
        assertFalse(AppLog.shumnaya("=== запись журнала начата, Xiaomi, pid 123 ==="))
        assertFalse(AppLog.shumnaya("-- сводка за 10 мин: соединений 100, отказов 3 (3%)"))
    }
}
