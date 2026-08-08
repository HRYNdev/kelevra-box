package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle(service.getString(R.string.app_name)).setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        service.startForeground(
            notificationId,
            notificationBuilder
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(service.getString(contentTextId)).build(),
        )
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    /**
     * Что показывать в шторке кроме скорости.
     *
     * «Работает» не говорит человеку ничего: он не видит ни выхода, ни того, что канал
     * через комнату ещё поднимается. Пишем состояние словами, как на главном экране.
     */
    private fun состояние(): String {
        val auto = AutoMode.state.value
        return when {
            auto.situation == AutoMode.Situation.Home -> service.getString(R.string.status_home)
            auto.situation == AutoMode.Situation.NoNetwork -> "Нет сети"
            Settings.autoModeManualRoom || auto.situation == AutoMode.Situation.Room -> when (OlcRtcCore.state) {
                is OlcRtcCore.State.Ready -> "Комната"
                is OlcRtcCore.State.Starting -> "Поднимаю комнату"
                else -> "Комната не отвечает"
            }
            // Выход выбран человеком: автомат отошёл до смены сети, мерять некому.
            Settings.manualExitName.isNotBlank() && !auto.auto -> Settings.manualExitName
            // Ветки для «ничего не поднимается» тут не было вовсе: шторка писала
            // «Выход выбирается сам» и на мёртвом канале (06.08.2026).
            auto.situation == AutoMode.Situation.Searching -> "Ищу путь"
            auto.auto -> when (auto.link) {
                AutoMode.Link.Dead -> "Связи нет"
                AutoMode.Link.Alive -> "Выход выбирается сам"
                else -> "Проверяю связь"
            }
            else -> service.getString(R.string.status_started)
        }
    }

    override fun updateStatus(status: StatusMessage) {
        // нули в шторке выглядят как поломка: пока трафика нет, показываем состояние
        val content = if (status.uplink == 0L && status.downlink == 0L) {
            состояние()
        } else {
            состояние() + " · " +
                Libbox.formatBytes(status.uplink) + "/s ↑ " + Libbox.formatBytes(status.downlink) + "/s ↓"
        }
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                commandClient.connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
            }
        }
    }

    fun close() {
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
