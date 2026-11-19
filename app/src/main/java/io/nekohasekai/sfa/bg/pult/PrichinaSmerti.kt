package io.nekohasekai.sfa.bg.pult

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.nekohasekai.sfa.bg.KLog

/**
 * Почему умер прошлый процесс — по записи системы (Android 11+).
 *
 * Испытанию пульта падением считалась любая смерть процесса при живой службе. Но HONOR,
 * HyperOS и нехватка памяти убивают процесс вместе со службой и без всякого падения —
 * три таких убийства подряд откатывали хороший пульт (14.11.2026). Падение — только
 * то, что система так и записала.
 */
object PrichinaSmerti {
    /**
     * true — прошлый процесс упал: исключение, падение в native-коде, ANR, ошибка старта
     * или выход сам с ненулевым кодом (паника Go в ядре кончается `exit(2)`); false — его
     * убили или он вышел штатно; null — записи нет или Android < 11.
     */
    fun proshlyyUpal(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val zapis = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        KLog.i("KelevraPult", "прошлый процесс: причина ${zapis.reason}, код ${zapis.status}, ${zapis.description.orEmpty()}")
        return when (zapis.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            -> true

            ApplicationExitInfo.REASON_EXIT_SELF -> zapis.status != 0
            ApplicationExitInfo.REASON_UNKNOWN -> null
            else -> false
        }
    }
}
