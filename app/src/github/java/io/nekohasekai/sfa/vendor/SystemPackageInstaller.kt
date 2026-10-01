package io.nekohasekai.sfa.vendor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import android.content.pm.PackageInstaller as AndroidPackageInstaller

object SystemPackageInstaller {

    fun canSystemSilentInstall(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hyperOsBlokiruetTihuyuUstanovku()

    /**
     * HyperOS/MIUI рубит тихий self-update (USER_ACTION_NOT_REQUIRED) ещё до системного окна:
     * `PKMSImpl.assertCallerAndPackage: ... msg=Permission denied` → сессия падает кодом
     * STATUS_FAILURE_ABORTED «INSTALL_FAILED_ABORTED: Permission denied», хотя
     * REQUEST_INSTALL_PACKAGES выдано и install_non_market_apps=1 — это проверка установщика
     * в прошивке, а не настройка телефона. На чистом AOSP тот же вызов ставится без окна.
     * Источник с тем же логом и диагнозом: github.com/sofianeelhor/PKForge/issues/25.
     */
    private fun hyperOsBlokiruetTihuyuUstanovku(): Boolean {
        val proizvoditel = Build.MANUFACTURER.lowercase()
        val brend = Build.BRAND.lowercase()
        return "xiaomi" in proizvoditel || "xiaomi" in brend || "redmi" in brend || "poco" in brend
    }

    fun install(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = AndroidPackageInstaller.SessionParams(AndroidPackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (canSystemSilentInstall()) {
            // На Xiaomi/Redmi/POCO эту строку не ставим вовсе: без неё требование
            // подтверждения остаётся системным умолчанием, и ответ придёт как
            // STATUS_PENDING_USER_ACTION — его InstallResultReceiver уже открывает.
            params.setRequireUserAction(AndroidPackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apkFile.length()).use { outputStream ->
                FileInputStream(apkFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }

            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_COMPLETE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            Log.i("KelevraObnovlenie", "файл обновления передан системе: ${apkFile.length()} байт")
            session.commit(pendingIntent.intentSender)
        }
    }
}
