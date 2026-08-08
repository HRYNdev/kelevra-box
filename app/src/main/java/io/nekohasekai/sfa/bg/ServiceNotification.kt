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
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathWords
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * Своя жизнь у текста в шторке.
     *
     * Тики статуса приходят от ядра, а ядро живёт не всегда: дома автомат гасит туннель,
     * без сети гасить нечего, но и мерять нечего тоже. Тиков нет — [updateStatus] никто
     * не зовёт — шторка держит прошлый текст, хотя обстановка давно другая. Живьём
     * 08.08.2026: круг на экране уже писал «Нет сети», а шторка больше минуты держала
     * «Дома, обход на роутере». Поэтому слушаем сам источник правды — [AutoMode.state],
     * тот же, что и главный экран.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var watcher: Job? = null

    /** Уведомление уже снято: поздний тик не должен воскресить его обратно. */
    @Volatile
    private var closed = false

    /**
     * Подключены ли мы к тикам живого ядра.
     *
     * Нужен потому, что ядро под нами включается и гаснет: дома автомат его снимает,
     * при уходе из дома поднимает заново. Без этого флага повторный [attachCore] рвал бы
     * рабочее соединение, а приёмник экрана будил бы клиента к ядру, которого нет.
     */
    @Volatile
    private var attached = false

    /** Последний тик ядра. Держим, чтобы перерисовать текст между тиками, не потеряв скорость. */
    @Volatile
    private var uplink = 0L

    @Volatile
    private var downlink = 0L

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

    /** Человек хочет подробное уведомление и разрешил их вообще. */
    private fun dynamic(): Boolean = Settings.dynamicNotification && checkPermission()

    /**
     * Уведомление начинает жить: подписка на обстановку и приёмник экрана.
     *
     * Тики ядра подключаются отдельно ([attachCore]), потому что ядра может ещё не быть:
     * когда сервис поднимается уже дома, туннель не запускается вовсе. Раньше этот заход
     * до `start()` вообще не доходил, и вся динамика уведомления в такой сессии не
     * включалась никогда — ни текст, ни скорости.
     *
     * @param coreLive поднято ли ядро прямо сейчас.
     */
    suspend fun start(coreLive: Boolean) {
        if (!dynamic()) return
        closed = false
        watch()
        withContext(Dispatchers.Main) {
            registerReceiver()
        }
        if (coreLive) attachCore()
    }

    /**
     * Ядро поднялось — цепляемся к его тикам.
     *
     * Зовётся и на старте сервиса, и когда автомат вернул погашенный туннель. Второе
     * обязательно: сессия, начатая дома, иначе оставалась без тиков навсегда — человек
     * уходил из дома, туннель вставал, а шторка держала статическое «Работает» и не
     * показывала ни состояния, ни скоростей.
     */
    fun attachCore() {
        if (!dynamic()) return
        closed = false
        watch()
        if (attached) return
        attached = true
        commandClient.connect()
    }

    /** Туннель погасили: тиков больше не будет, и прошлые скорости врут. */
    fun detachCore() {
        uplink = 0L
        downlink = 0L
        if (!attached) return
        attached = false
        commandClient.disconnect()
    }

    /**
     * Показать состояние прямо сейчас — когда обстановка не менялась, но повод есть
     * (сняли или подняли туннель).
     *
     * @param fallback статическая строка на случай, когда подробное уведомление
     *   выключено человеком: тогда ведём себя ровно как раньше.
     */
    fun refresh(@StringRes fallback: Int) {
        if (closed) return
        if (dynamic()) render() else show("", fallback)
    }

    /**
     * Подписка на обстановку.
     *
     * Звать можно сколько угодно раз: лишней подписки не заведётся. [AutoMode.state] —
     * StateFlow, одинаковых значений он не повторяет, поэтому перерисовываем ровно на
     * изменениях, а не по таймеру.
     */
    fun watch() {
        if (!dynamic()) return
        closed = false
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            launch {
                AutoMode.state.collect {
                    // Путь сменился — прошлые байты в секунду мерили прошлый путь и врут.
                    // Следующий тик ядра (если оно живо) вернёт настоящие числа.
                    uplink = 0L
                    downlink = 0L
                    render()
                }
            }
            // Обстановка может не меняться, а знание о путях — да: комната поднялась,
            // задержка пересчиталась, отказ получил причину. Шторка это показывает,
            // значит и просыпаться должна на это тоже.
            launch { PathRegistry.snapshot.collect { render() } }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
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
     * через комнату ещё поднимается. Пишем состояние словами, как на главном экране, —
     * и теперь буквально теми же: обе стороны читают один снимок [PathRegistry] через
     * одну таблицу [PathWords.headline]. Своей цепочки условий у шторки больше нет, а
     * значит нет и способа разойтись с кругом.
     */
    private fun состояние(): String = PathWords.headline(
        snapshot = PathRegistry.snapshot.value,
        chosen = AutoMode.standingOn(),
        auto = AutoMode.state.value.auto,
        manualExit = Settings.manualExitName.takeIf { it.isNotBlank() },
    )

    override fun updateStatus(status: StatusMessage) {
        uplink = status.uplink
        downlink = status.downlink
        render()
    }

    /** Собирает текст и кладёт его в шторку. Зовётся и по тику ядра, и по смене обстановки. */
    private fun render() {
        if (closed) return
        // нули в шторке выглядят как поломка: пока трафика нет, показываем состояние
        val content = if (uplink == 0L && downlink == 0L) {
            состояние()
        } else {
            состояние() + " · " +
                Libbox.formatBytes(uplink) + "/s ↑ " + Libbox.formatBytes(downlink) + "/s ↓"
        }
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Будим клиента только если ядро под нами живо: пока туннель погашен (мы дома),
            // подключаться некуда, а текст в шторке и так ведёт подписка на обстановку.
            Intent.ACTION_SCREEN_ON -> {
                if (attached) commandClient.connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
            }
        }
    }

    fun close() {
        closed = true
        attached = false
        watcher?.cancel()
        watcher = null
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
