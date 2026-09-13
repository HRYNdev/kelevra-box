package io.nekohasekai.sfa.vendor

import android.content.Context
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.xposed.XposedActivation
import kotlinx.coroutines.delay
import java.io.File

enum class InstallMethod {
    PACKAGE_INSTALLER,
    SHIZUKU,
    ROOT,
}

object ApkInstaller {
    private const val TAG = "KelevraObnovlenie"

    /** true, если служба работала и её пришлось погасить. */
    private suspend fun stopServiceIfRunning(): Boolean {
        val commandSocket = File(Application.application.filesDir, "command.sock")
        if (!commandSocket.exists()) {
            return false
        }
        BoxService.stop()
        repeat(20) {
            delay(100)
            if (!commandSocket.exists()) {
                return true
            }
        }
        return true
    }

    /**
     * Остановка службы снимает признак «запущено человеком», а по нему служба поднимается
     * после замены пакета. Снимается он в самом конце остановки, позже исчезновения сокета,
     * поэтому сначала дожидаемся снятия, потом возвращаем.
     */
    private suspend fun vernutPriznakZapuska() {
        for (i in 0 until 30) {
            if (!Settings.startedByUser) break
            delay(100)
        }
        Settings.startedByUser = true
    }

    fun getConfiguredMethod(): InstallMethod {
        if (HookStatusClient.status.value?.active == true ||
            XposedActivation.isActivated(Application.application)
        ) {
            return InstallMethod.ROOT
        }
        return if (Settings.silentInstallEnabled) {
            InstallMethod.valueOf(Settings.silentInstallMethod)
        } else {
            InstallMethod.PACKAGE_INSTALLER
        }
    }

    suspend fun install(context: Context, apkFile: File, method: InstallMethod = getConfiguredMethod()) {
        if (method == InstallMethod.PACKAGE_INSTALLER) {
            // Через системный установщик службу не гасим. До проверки и подтверждения она
            // установке не мешает, при замене пакета система сама завершает процесс, а после
            // замены служба поднимается по MY_PACKAGE_REPLACED: признак «запущено человеком»
            // остаётся на месте. Раньше служба гасилась до передачи файла, остановка снимала
            // этот признак, и любой исход оставлял телефон без туннеля: при отказе код
            // обновления службу не поднимал, при успехе её не поднимал и приёмник замены.
            Log.i(TAG, "ставлю обновление системным установщиком, службу не гашу")
            SystemPackageInstaller.install(context, apkFile)
            return
        }
        // Root и Shizuku ставят мимо системного окна и роняют процесс посреди работы ядра,
        // поэтому здесь служба по-прежнему гасится, но признак запуска и сама служба
        // возвращаются.
        val bylaVklyuchena = stopServiceIfRunning()
        if (bylaVklyuchena) {
            vernutPriznakZapuska()
        }
        try {
            when (method) {
                InstallMethod.SHIZUKU -> ShizukuInstaller.install(apkFile)
                InstallMethod.ROOT -> RootInstaller.install(apkFile)
                InstallMethod.PACKAGE_INSTALLER -> Unit
            }
        } catch (e: Exception) {
            if (bylaVklyuchena) {
                Log.w(TAG, "установка не прошла, поднимаю службу обратно: ${e.message}")
                runCatching { BoxService.start() }.onFailure { Log.w(TAG, "служба не поднялась: ${it.message}") }
            }
            throw e
        }
    }

    fun canSystemSilentInstall(): Boolean = SystemPackageInstaller.canSystemSilentInstall()

    suspend fun canSilentInstall(): Boolean {
        val method = getConfiguredMethod()
        return when (method) {
            InstallMethod.PACKAGE_INSTALLER -> canSystemSilentInstall()
            InstallMethod.SHIZUKU -> ShizukuInstaller.isAvailable() && ShizukuInstaller.checkPermission()
            InstallMethod.ROOT -> RootClient.checkRootAvailable()
        }
    }
}
