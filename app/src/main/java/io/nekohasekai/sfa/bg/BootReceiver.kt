package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val obnovilos = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        GlobalScope.launch(Dispatchers.IO) {
            if (obnovilos) {
                // Успех установки своего обновления система часто не присылает: процесс
                // завершается раньше ответа. Замена пакета — надёжная отметка исхода.
                Log.i(
                    "KelevraObnovlenie",
                    "приложение обновилось: версия ${BuildConfig.VERSION_NAME}, " +
                        if (Settings.startedByUser) "служба поднимается" else "служба не была запущена",
                )
                Settings.updateInstallFailures = ""
            }
            if (Settings.startedByUser) {
                CrashReportManager.refresh()
                if (CrashReportManager.unreadCount.value > 0) {
                    Settings.startedByUser = false
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    BoxService.start()
                }
            }
        }
    }
}
