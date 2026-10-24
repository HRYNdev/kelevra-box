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
                val tihaya = intent.getBooleanExtra(SystemPackageInstaller.EXTRA_TIHAYA, false)
                val telefon = SystemPackageInstaller.telefon()
                Log.i(TAG_ISHOD, "установка обновления прошла, установка ${IshodUstanovki.rezhim(tihaya)}, телефон $telefon")
                runCatching {
                    Zapisi.zapisat(
                        "obnovlenie",
                        mapOf("ishod" to "proshla", "telefon" to telefon, "rezhim" to IshodUstanovki.rezhim(tihaya)),
                    )
                }
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
                val tihaya = intent.getBooleanExtra(SystemPackageInstaller.EXTRA_TIHAYA, false)
                val telefon = SystemPackageInstaller.telefon()
                val prichina = IshodUstanovki.razobrat(status, message)
                // Повтор с окном решается до показа отказа: человек, нажавший «Обновить»,
                // должен увидеть окно системы, а не «не разрешил» на долю секунды.
                val mozhetBytPovtor = IshodUstanovki.povtoritSOknom(status, message, tihaya, uzhePovtoryali = false)
                if (!mozhetBytPovtor) {
                    // Состояние ставится сразу: окно отказа должно появиться, пока человек смотрит.
                    UpdateState.setInstallStatus(UpdateState.InstallStatus.Failed(IshodUstanovki.tekst(prichina), prichina))
                }
                val pending = goAsync()
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        // Ответ может прийти в свежий процесс, где сведения о версии ещё не подняты.
                        val info = UpdateState.updateInfo.value ?: run {
                            UpdateState.loadFromCache()
                            UpdateState.updateInfo.value
                        }
                        Log.w(
                            TAG_ISHOD,
                            IshodUstanovki.strokaZhurnala(info?.versionName ?: "неизвестна", prichina, status, message, telefon, tihaya),
                        )
                        runCatching {
                            Zapisi.zapisat(
                                "obnovlenie",
                                mapOf(
                                    "ishod" to "ne_proshla",
                                    "versiya" to info?.versionName,
                                    "prichina" to prichina.name,
                                    "kod" to status,
                                    "sistema" to message,
                                    "telefon" to telefon,
                                    "rezhim" to IshodUstanovki.rezhim(tihaya),
                                ),
                            )
                        }
                        if (mozhetBytPovtor && povtoritSOknom(context, info?.versionCode ?: 0, telefon)) {
                            return@launch
                        }
                        if (mozhetBytPovtor) {
                            UpdateState.setInstallStatus(UpdateState.InstallStatus.Failed(IshodUstanovki.tekst(prichina), prichina))
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

    /**
     * Один немедленный повтор тем же файлом, но с окном подтверждения системы
     * (IshodUstanovki.povtoritSOknom). Признак «уже пробовали» — номер версии в Settings:
     * второй отказ той же версии идёт обычным путём (напоминание раз в сутки).
     * false — повтора не было (уже пробовали, нет файла или сессия не создалась).
     */
    private fun povtoritSOknom(context: Context, versionCodeIzSvedeniy: Int, telefon: String): Boolean {
        val apk = UpdateState.cachedApkFile.value ?: Settings.cachedApkPath.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
        if (apk == null || !apk.exists() || apk.length() <= 0L) {
            Log.w(TAG_ISHOD, "повтор с окном невозможен: файла обновления нет")
            return false
        }
        // Сведения о версии могли не подняться (свежий процесс) — тогда номер из имени файла.
        val versionCode = versionCodeIzSvedeniy.takeIf { it > 0 } ?: IshodUstanovki.versiyaIzImeni(apk.name)
        // Номер не узнать — признак не поставить; зацикливания всё равно нет: сессия с окном
        // приходит с EXTRA_TIHAYA=false и второй раз не повторяется.
        if (versionCode > 0 && Settings.updatePovtorSOknom == versionCode) {
            Log.i(TAG_ISHOD, "повтор с окном для этой версии уже был, второй раз не повторяю")
            return false
        }
        // Признак ставится ДО повтора: если процесс умрёт посреди сессии, второго круга не будет.
        Settings.updatePovtorSOknom = versionCode
        Log.i(TAG_ISHOD, "тихая установка отклонена проверкой системы, повторяю с окном подтверждения, телефон $telefon")
        runCatching { Zapisi.zapisat("obnovlenie", mapOf("ishod" to "povtor_s_oknom", "telefon" to telefon)) }
        UpdateState.setInstallStatus(UpdateState.InstallStatus.Installing)
        return runCatching { SystemPackageInstaller.install(context.applicationContext, apk, sOknom = true) }
            .onFailure { e -> Log.w(TAG_ISHOD, "повтор с окном не начался: ${e.message}") }
            .isSuccess
    }
}
