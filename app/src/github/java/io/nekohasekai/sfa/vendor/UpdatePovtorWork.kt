package io.nekohasekai.sfa.vendor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.ServiceNotification
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.OtkazUstanovki
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Повтор обновления после отказа.
 *
 * Разовая работа по лестнице [IshodUstanovki.zaderzhkaMinut]: заново скачивает и проверяет
 * файл, чтобы следующее нажатие не упиралось в сеть, и напоминает уведомлением. Поставить
 * версию сама может только при тихой установке — без неё системное окно подтверждения из
 * фона не откроется. Лестница конечная, счётчик отказов на версию живёт в настройках, так
 * что зациклиться работа не может.
 */
class UpdatePovtorWork(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "KelevraObnovleniePovtor"
        private const val TAG = "KelevraObnovlenie"
        private const val CHANNEL_ID = "kelevra_update"
        private const val NOTIFICATION_ID = 0x5F20

        /**
         * Отметить отказ и поставить повтор, если лестница не кончилась. Ходит в настройки,
         * поэтому звать не с главного потока.
         */
        fun posleOtkaza(context: Context, info: UpdateInfo?, prichina: OtkazUstanovki) {
            val versionCode = info?.versionCode ?: return
            val zapis = Settings.updateInstallFailures
            val otkazov = IshodUstanovki.prochestSchetchik(zapis, versionCode, prichina) + 1
            Settings.updateInstallFailures = IshodUstanovki.zapisatSchetchik(zapis, versionCode, prichina, otkazov)
            // Окно «Есть обновление» показывается раз на версию и при нажатии «Обновить»
            // считается показанным. После отказа оно должно появиться снова.
            Settings.lastShownUpdateVersion = 0
            val minut = IshodUstanovki.zaderzhkaMinut(otkazov, prichina)
            if (minut == null) {
                Log.w(TAG, "повтор обновления не ставлю: версия ${info.versionName}, причина $prichina, отказов этой причины $otkazov")
                return
            }
            zaplanirovat(context, minut)
            Log.i(TAG, "повтор обновления через $minut мин: версия ${info.versionName}, причина $prichina, отказов этой причины $otkazov")
        }

        /** Поставить разовую работу повтора; прежняя заменяется, так что в очереди она одна. */
        private fun zaplanirovat(context: Context, minut: Long) {
            val request = OneTimeWorkRequestBuilder<UpdatePovtorWork>()
                .setInitialDelay(minut, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Обновление встало или больше не нужно: счётчик, повтор и напоминание снимаются. */
        fun sbrosit(context: Context) {
            Settings.updateInstallFailures = ""
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun napomnit(context: Context, info: UpdateInfo) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.i(TAG, "напоминание об обновлении не показано: уведомления выключены")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or ServiceNotification.flags,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle("Обновление ждёт установки")
                .setContentText("Версия ${info.versionName} не установилась. Нажмите, чтобы попробовать ещё раз.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
                .onFailure { Log.w(TAG, "напоминание об обновлении не показано: ${it.message}") }
        }
    }

    override suspend fun doWork(): Result {
        val info = UpdateState.updateInfo.value ?: run {
            UpdateState.loadFromCache()
            UpdateState.updateInfo.value
        }
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            Log.i(TAG, "повтор обновления не нужен: новая версия уже стоит или не найдена")
            sbrosit(applicationContext)
            return Result.success()
        }
        Log.i(TAG, "повтор обновления: версия ${info.versionName}")
        val apkFile = try {
            ApkDownloader().use { it.download(info.downloadUrl, info) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "повтор обновления: скачать не вышло: ${e.message}")
            posleOtkaza(applicationContext, info, OtkazUstanovki.DRUGOE)
            return Result.success()
        }
        if (Settings.silentInstallEnabled && ApkInstaller.canSilentInstall()) {
            Log.i(TAG, "повтор обновления: ставлю без подтверждения")
            UpdateState.setInstallStatus(UpdateState.InstallStatus.Installing)
            try {
                ApkInstaller.install(applicationContext, apkFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "повтор обновления: установка не запустилась: ${e.message}")
                posleOtkaza(applicationContext, info, OtkazUstanovki.DRUGOE)
            }
            return Result.success()
        }
        napomnit(applicationContext, info)
        // Без тихой установки версию ставит только человек. Напоминание повторяется раз в
        // сутки, пока версия не встанет: успех установки снимает работу через sbrosit.
        zaplanirovat(applicationContext, IshodUstanovki.NAPOMINANIE_MINUT)
        Log.i(TAG, "следующее напоминание об обновлении через ${IshodUstanovki.NAPOMINANIE_MINUT} мин")
        return Result.success()
    }
}
