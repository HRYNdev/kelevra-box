package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            // olcRTC поднимаем ДО sing-box: для sing-box это обычный socks-outbound
            // на 127.0.0.1, и он должен быть уже жив к моменту старта ядра.
            startOlcRtcIfEnabled()

            try {
                commandServer.startOrReloadService(
                    effectiveConfig(content),
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        applyPerAppProxy()
                    },
                )
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    /**
     * Раскладка per-app для tun.
     *
     * Апстрим намеренно заворачивает собственный пакет в свой же tun и спасается
     * только через `protect(fd)` на исходящих sing-box. Ядру olcRTC этого мало:
     * проверено 05.08 на эмуляторе — при поднятом tun медиапоток VP8 умирает через
     * ~20 с (`readVP8Track ... err=EOF`, дальше `OpenStream failed: timeout` и смерть
     * по liveness), хотя protect(fd) отработал на каждом сокете. Причина в том, что
     * ICE-кандидаты привязаны к `0.0.0.0` и адресу физического интерфейса, и защита
     * отдельного сокета не спасает от пересчёта маршрута при появлении tun.
     *
     * Поэтому при включённом olcRTC приложение целиком выводится из туннеля.
     * Для sing-box это безопасно: его исходящие и так идут через protected-сокеты
     * (`autoDetectInterfaceControl`), а не через таблицу маршрутов tun.
     */
    private fun OverrideOptions.applyPerAppProxy() {
        val self = Application.application.packageName

        // Ядро olcRTC не переживает собственный tun — выводим весь пакет наружу.
        val selfOutsideTun = Settings.olcrtcEnabled && service is VpnService

        if (!Vendor.isPerAppProxyAvailable() || !Settings.perAppProxyEnabled) {
            // Без per-app списка апстрим не задаёт ничего, и приложение остаётся в tun.
            if (selfOutsideTun) {
                excludePackage = PlatformInterfaceWrapper.StringArray(listOf(self).iterator())
                Log.i(TAG, "olcRTC: $self выведен из tun (per-app список выключен)")
            }
            return
        }

        val appList = Settings.getEffectivePerAppProxyList()
        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
            // Белый список: «вывести из tun» = просто не добавлять себя в него.
            includePackage =
                PlatformInterfaceWrapper.StringArray(
                    (if (selfOutsideTun) appList - self else appList + self).iterator(),
                )
        } else {
            excludePackage =
                PlatformInterfaceWrapper.StringArray(
                    (if (selfOutsideTun) appList + self else appList - self).iterator(),
                )
        }
        if (selfOutsideTun) Log.i(TAG, "olcRTC: $self выведен из tun")
    }

    /**
     * Поднимает ядро olcRTC, если его включили в настройках.
     *
     * Защита от петли. Ядро само ходит наружу по WebRTC, и если его сокеты попадут
     * в наш же tun, трафик туннеля пойдёт через туннель. Держится это так:
     *  1. `protect(fd)` на каждый сокет ядра — тот же вызов, что VPNService отдаёт
     *     sing-box в autoDetectInterfaceControl. Сокет намертво привязывается к
     *     физической сети, и поднятый позже tun его уже не забирает.
     *  2. Порядок: ядро стартует до openTun, то есть первые сокеты создаются, когда
     *     tun ещё не существует.
     *  3. Само ядро выкидывает интерфейсы tun/ppp/pptp из сбора ICE-кандидатов
     *     (internal/protect/pionnet.go), так что и кандидатов на нашем tun не будет.
     *  4. В режиме VPN без protect стартовать запрещено — [OlcRtcCore] откажет сам.
     *     В режиме без tun (ProxyService) петли нет, protect не нужен.
     *
     * Ошибка olcRTC не роняет сервис: этот выход просто не поднимается.
     */
    private fun startOlcRtcIfEnabled() {
        if (!Settings.olcrtcEnabled) return

        val vpn = service as? VpnService
        if (vpn != null && VpnService.prepare(service) != null) {
            Log.w(TAG, "olcRTC: разрешение на VPN не выдано, protect(fd) работать не будет")
        }
        val protector: ((Int) -> Boolean)? = vpn?.let { { fd: Int -> it.protect(fd) } }

        // Параметры комнаты приезжают с сервера, ручные значения их перебивают.
        val params = OlcRtcParams.resolve()

        val result =
            runCatching {
                OlcRtcCore.start(params, protector, requireProtector = vpn != null)
            }.getOrElse { OlcRtcCore.State.Failed(it.message ?: it.javaClass.simpleName) }

        when (result) {
            is OlcRtcCore.State.Ready -> {
                Log.i(TAG, "olcRTC: выход поднят на 127.0.0.1:${params.socksPort}")
                // Сразу спрашиваем канал: «поднят» без прошедших байтов — это ещё не выход.
                OlcRtcCore.probe(params.socksPort)
            }

            else ->
                // Честно говорим и идём дальше без этого выхода.
                Log.w(TAG, "olcRTC: выход не поднят (${OlcRtcCore.lastError}), продолжаем без него")
        }
    }

    /**
     * Конфиг, с которым реально стартует sing-box.
     *
     * Комната поднялась — добавляем в маршруты отказ по UDP/443 (см. [OlcRtcConfigPatch]).
     * Выключена или не поднялась — возвращаем ровно то, что лежит в профиле: ни байта
     * правки. Резать QUIC ради мёртвого выхода незачем, от этого только хуже.
     * Файл профиля не трогаем в любом случае, правка живёт только в памяти.
     */
    private fun effectiveConfig(content: String): String {
        if (!Settings.olcrtcEnabled || OlcRtcCore.state !is OlcRtcCore.State.Ready) return content
        val result = OlcRtcConfigPatch.addQuicReject(content, OlcRtcParams.socksPort)
        OlcRtcConfigPatch.log(result)
        return result.content
    }

    /** Гасим ПОСЛЕ sing-box: пока он жив, он может ходить в этот socks. */
    private fun stopOlcRtc() {
        runCatching { OlcRtcCore.stop() }
            .onFailure { Log.w(TAG, "olcRTC: остановка сорвалась: ${it.message}") }
    }

    override fun serviceStop() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                effectiveConfig(content),
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    applyPerAppProxy()
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (Application.powerManager.isDeviceIdleMode) {
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            closeService()
            commandServer.apply {
                close()
//                Seq.destroyRef(refnum)
            }
            stopOlcRtc()
            Settings.startedByUser = false
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        stopOlcRtc()
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val builder =
            NotificationCompat.Builder(service, notification.identifier).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.typeID, builder.build())
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
