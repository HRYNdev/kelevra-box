package io.nekohasekai.sfa.bg

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"
        private const val WORK_NAME_SEYCHAS = "UpdateProfileSeychas"
        private const val KLYUCH_SILA = "sila"

        /**
         * Интервал профиля в минутах: свой у профиля, а если пульт задал `opros.info_min` —
         * его значение для всех наших профилей. Ключа нет — ровно как раньше.
         */
        internal fun intervalMinut(svoy: Int): Int =
            if (io.nekohasekai.sfa.bg.pult.Pult.zadan("opros.info_min")) io.nekohasekai.sfa.bg.pult.Pult.int("opros.info_min") else svoy

        /**
         * Перечитать профиль и сводку сейчас, не глядя на интервал (команда пульта `obnovi_profil`).
         * Отдельной разовой работой: экран тут ни при чём, а движок обновления живёт вне него.
         */
        fun seychas() {
            WorkManager.getInstance(Application.application).enqueueUniqueWork(
                WORK_NAME_SEYCHAS,
                androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequest.Builder(UpdateTask::class.java)
                    .setInputData(androidx.work.Data.Builder().putBoolean(KLYUCH_SILA, true).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                KLog.e(TAG, "reconfigureUpdater", it)
            }
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            var minDelay =
                intervalMinut(remoteProfiles.minByOrNull { it.typed.autoUpdateInterval }!!.typed.autoUpdateInterval).toLong()
            val nowSeconds = System.currentTimeMillis() / 1000L
            val minInitDelay =
                remoteProfiles.minOf { (intervalMinut(it.typed.autoUpdateInterval) * 60) - (nowSeconds - (it.typed.lastUpdated.time / 1000L)) }
            if (minDelay < 15) minDelay = 15
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                        // Без сети обновлять профиль нечем, а попытка всё равно считается
                        // и уводит работу в откат. Требования к сети у этой работы не было
                        // вовсе — в отличие от отправки журнала рядом.
                        setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            io.nekohasekai.sfa.bg.pult.Pult.zagruzitEsliNado()
            var selectedProfileUpdated = false
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) return Result.success()
            var success = true
            val selectedProfile = Settings.selectedProfile
            val sila = inputData.getBoolean(KLYUCH_SILA, false)
            for (profile in remoteProfiles) {
                val lastSeconds =
                    (System.currentTimeMillis() - profile.typed.lastUpdated.time) / 1000L
                if (!sila && lastSeconds < intervalMinut(profile.typed.autoUpdateInterval) * 60) {
                    continue
                }
                try {
                    val content = HTTPClient().use {
                        it.getString(io.nekohasekai.sfa.Kelevra.adresSeti(profile.typed.remoteURL))
                    }
                    Libbox.checkConfig(content)
                    val file = File(profile.typed.path)
                    if (file.readText() != content) {
                        File(profile.typed.path).writeText(content)
                        if (profile.id == selectedProfile) {
                            selectedProfileUpdated = true
                        }
                    }
                    profile.typed.lastUpdated = Date()
                    ProfileManager.update(profile)
                    // Отметка для паспорта устройства: по ней сервер видит, доезжают ли
                    // до человека наши правки правил вообще. Читать базу профилей в
                    // момент сборки заголовка нельзя, поэтому храним отдельно.
                    runCatching { Settings.profilObnovlenV = System.currentTimeMillis() }
                } catch (e: Exception) {
                    // Адрес подписки несёт код доступа целиком, а текст ошибки его часто
                    // повторяет — в лог идёт замаскированная копия исключения.
                    KLog.e(TAG, "update profile ${profile.name}", io.nekohasekai.sfa.Kelevra.maskThrowable(e))
                    success = false
                }
            }
            // Список выходов приезжает конфигом, а состояние подписки, исключения и
            // параметры комнаты — отдельной сводкой. Раньше расписание тянуло только
            // конфиг, и новая комната появлялась в телефоне лишь после захода на главный
            // экран. Сводка тут не обязательна: не пришла — прошлые значения остаются.
            runCatching {
                io.nekohasekai.sfa.compose.screen.home.SubscriptionRefresh.loadInfo()
            }.onFailure {
                KLog.w(TAG, "сводка подписки не обновилась", it)
            }

            if (selectedProfileUpdated) {
                runCatching {
                    Libbox.newStandaloneCommandClient().serviceReload()
                }
            }
            return if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
