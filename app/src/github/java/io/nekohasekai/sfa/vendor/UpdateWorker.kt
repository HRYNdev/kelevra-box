package io.nekohasekai.sfa.vendor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.ObnovleniyaPult
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.update.checkFDroidUpdate
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class UpdateWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "AutoUpdate"
        private const val WORK_NAME_SEYCHAS = "AutoUpdateSeychas"
        private const val TAG = "UpdateWorker"

        fun schedule(context: Context) {
            if (!Settings.autoUpdateEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                KLog.d(TAG, "Auto update disabled, cancelled scheduled work")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                6,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
            KLog.d(TAG, "Auto update scheduled")
        }

        /**
         * Проверить обновление сейчас: команда пульта `proverit_obnovlenie` и новый блок
         * `obnovlenie` в пульте. Разовой работой, суточное расписание не трогается.
         */
        fun seychas(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_SEYCHAS,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!Settings.autoUpdateEnabled) {
            KLog.d(TAG, "Auto update disabled, skipping")
            return Result.success()
        }

        KLog.d(TAG, "Checking for updates...")

        // Выпуски GitHub/F-Droid как раньше; при пульте — сборка из блока `obnovlenie`
        // (главнее выпусков) или ничего (канал proba без блока, stop).
        val updateInfo = try {
            when (val istochnik = ObnovleniyaPult.vybratSeychas(io.nekohasekai.sfa.BuildConfig.VERSION_CODE)) {
                is ObnovleniyaPult.Istochnik.IzPulta -> istochnik.info.also {
                    KLog.i(TAG, "обновление из пульта: сборка ${it.versionCode}")
                }
                ObnovleniyaPult.Istochnik.Nichego -> {
                    KLog.i(TAG, "канал обновлений «${io.nekohasekai.sfa.bg.pult.Pult.vybor("obnovleniya.kanal")}»: выпуски не проверяю")
                    null
                }
                ObnovleniyaPult.Istochnik.Vypuski -> when (UpdateSource.fromString(Settings.updateSource)) {
                    UpdateSource.FDROID -> checkFDroidUpdate(appContext)
                    UpdateSource.GITHUB -> {
                        val track = UpdateTrack.fromString(Settings.updateTrack)
                        GitHubUpdateChecker().use { it.checkUpdate(track, Settings.githubToken) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(TAG, "Auto update failed", e)
            return Result.retry()
        }

        if (updateInfo == null) {
            KLog.d(TAG, "No update available")
            return Result.success()
        }

        KLog.d(TAG, "Update available: ${updateInfo.versionName}")
        UpdateState.setUpdate(updateInfo)

        // Файл качаем заранее в любом случае: и для тихой установки, и для «одного нажатия».
        val apkFile = try {
            ApkDownloader().use { it.download(updateInfo.downloadUrl, updateInfo) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.w(TAG, "обновление: скачать не вышло: ${e.message}")
            // Сборка из пульта с неверным хешем больше не берётся — иначе повтор по кругу.
            if (ObnovleniyaPult.posleSboya(updateInfo, e)) {
                if (e is io.nekohasekai.sfa.update.FaylNeGoden) UpdateState.setUpdate(null)
                return Result.success()
            }
            return Result.retry()
        }

        if (ObnovleniyaPult.tiho(Settings.silentInstallEnabled) && ApkInstaller.canSilentInstall()) {
            KLog.d(TAG, "Installing update...")
            try {
                ApkInstaller.install(appContext, apkFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.e(TAG, "Auto update failed", e)
                return Result.retry()
            }
            KLog.d(TAG, "Update installed successfully")
        } else {
            // Тихой установки нет: файл уже лежит, человеку остаётся одно нажатие.
            KLog.i(TAG, "тихая установка недоступна — файл скачан, показываю «Обновление готово»")
            UpdatePovtorWork.pokazatGotovo(appContext, updateInfo)
        }
        return Result.success()
    }
}
