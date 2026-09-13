package io.nekohasekai.sfa.vendor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.nekohasekai.sfa.bg.Zapisi
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.OtkazUstanovki
import io.nekohasekai.sfa.update.UpdateState
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_COMPLETE = "io.nekohasekai.sfa.INSTALL_COMPLETE"
        private const val TAG = "InstallResultReceiver"
        private const val TAG_ISHOD = "KelevraObnovlenie"
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_COMPLETE) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.d(TAG, "Install result: status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG_ISHOD, "установка обновления ждёт подтверждения человека")
                runCatching { Zapisi.zapisat("obnovlenie", mapOf("ishod" to "zhdyot_podtverzhdeniya")) }
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent == null) {
                    Log.w(TAG_ISHOD, "система не приложила окно подтверждения")
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Из фона новые версии Android окно молча не открывают; исключение
                    // ловится только на случай, когда запуск отвергнут явно.
                    runCatching { context.startActivity(it) }
                        .onFailure { e -> Log.w(TAG_ISHOD, "окно подтверждения не открылось: ${e.message}") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Installation successful")
                Log.i(TAG_ISHOD, "установка обновления прошла")
                runCatching { Zapisi.zapisat("obnovlenie", mapOf("ishod" to "proshla")) }
                UpdateState.setInstallStatus(UpdateState.InstallStatus.Success)
                val pending = goAsync()
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        UpdatePovtorWork.sbrosit(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> {
                Log.e(TAG, "Installation failed: $status - $message")
                val prichina = IshodUstanovki.razobrat(status, message)
                // Состояние ставится сразу: окно отказа должно появиться, пока человек смотрит.
                UpdateState.setInstallStatus(UpdateState.InstallStatus.Failed(IshodUstanovki.tekst(prichina), prichina))
                val pending = goAsync()
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        // Ответ может прийти в свежий процесс, где сведения о версии ещё не подняты.
                        val info = UpdateState.updateInfo.value ?: run {
                            UpdateState.loadFromCache()
                            UpdateState.updateInfo.value
                        }
                        Log.w(TAG_ISHOD, IshodUstanovki.strokaZhurnala(info?.versionName ?: "неизвестна", prichina, status, message))
                        runCatching {
                            Zapisi.zapisat(
                                "obnovlenie",
                                mapOf(
                                    "ishod" to "ne_proshla",
                                    "versiya" to info?.versionName,
                                    "prichina" to prichina.name,
                                    "kod" to status,
                                    "sistema" to message,
                                ),
                            )
                        }
                        if (prichina == OtkazUstanovki.BITYY_FAYL) {
                            // Файл, который система не разобрала, второй раз ставить бессмысленно.
                            UpdateState.cachedApkFile.value?.delete()
                            UpdateState.cachedApkFile.value = null
                            Settings.cachedApkPath = ""
                        }
                        UpdatePovtorWork.posleOtkaza(context.applicationContext, info, prichina)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
