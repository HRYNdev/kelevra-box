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
import io.nekohasekai.sfa.bg.path.HonestProbe
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.ProbeSocket
import io.nekohasekai.sfa.bg.path.RoomNote
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
import java.net.DatagramSocket
import java.net.Socket
import kotlin.concurrent.thread

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

    /**
     * Туннель погашен автоматом: ядро и tun сняты, а сам сервис жив.
     *
     * Живой сервис здесь — не мелочь, а условие всей затеи. Он держит подписку на смену
     * сети, поэтому уход из дома виден бесплатно, и он же держит выданное разрешение на
     * VPN, поэтому второй раз его не спрашивают.
     */
    @Volatile
    private var tunnelSuspended = false

    private val tunnelLock = Any()

    /**
     * Нужна ли комната прямо сейчас.
     *
     * Раньше ядро olcRTC поднималось вместе с сервисом и держало видеозвонок круглосуточно.
     * Это и батарея, и — важнее — постоянная сессия в чужой комнате, тогда как вся защита
     * схемы держится ровно на обратном: звонок живёт столько, сколько нужен. Теперь решение
     * принимает автомат (или человек, выбравший комнату руками), а сервис только исполняет.
     */
    @Volatile
    private var roomWanted = false

    /**
     * Подъём комнаты идёт прямо сейчас, в потоке `olcrtc-raise`.
     *
     * Раньше подъём шёл в потоке того, кто попросил, — то есть в потоке автомата, и держал
     * его замер 08.08.2026 на 69 секунд. Всё это время автомат был слеп: круг не крутился,
     * экран и шторка стояли на том, что было до подъёма. Теперь просьба возвращается сразу
     * с [AutoMode.RoomAck.Raising], а этот флаг не даёт следующим заходам просить второй раз.
     */
    @Volatile
    private var roomRaising = false

    /** Что автомат умеет сделать с сервисом. Больше он ни во что не лезет. */
    private val autoModeHost = object : AutoMode.Host {
        override fun suspendTunnel(reason: String): Boolean = this@BoxService.suspendTunnel(reason)

        override fun resumeTunnel(reason: String): Boolean = this@BoxService.resumeTunnel(reason)

        override fun tunnelLive(): Boolean = !tunnelSuspended

        // Отдаём тот конфиг, который ядро исполняет сейчас, а не файл профиля: правки
        // живут только в памяти, и по файлу автомат не увидел бы ни запрета QUIC, ни
        // входов, закреплённых за путями. Ядра ещё нет — остаётся файл, как раньше.
        override fun profileConfig(): String? = runningConfig ?: runCatching {
            val profile = runBlocking { ProfileManager.get(Settings.selectedProfile) } ?: return null
            File(profile.typed.path).readText()
        }.getOrNull()

        override fun selectExit(group: String, tag: String) {
            // Локальный клиент к своему же командному серверу: тот же путь, которым
            // выход переключает человек с главного экрана.
            Libbox.newStandaloneCommandClient().selectOutbound(group, tag)
        }

        override fun setRoomWanted(wanted: Boolean, reason: String): AutoMode.RoomAck =
            this@BoxService.setRoomWanted(wanted, reason)
    }

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
            // Автомат слушает ту же смену сети, что и sing-box. Отдельного колбэка не
            // заводим: он стоит денег, а этот уже зарегистрирован.
            DefaultNetworkListener.start(AutoMode) { AutoMode.onNetworkChanged(it) }

            // Дома обход делает роутер, и своя обёртка поверх него только грузит телефон.
            // Смотрим ДО старта ядра: поднять туннель и через секунду погасить — хуже,
            // чем не поднимать вовсе.
            if (AutoMode.homeRightNow()) {
                tunnelSuspended = true
                status.postValue(Status.Started)
                withContext(Dispatchers.Main) {
                    notification.show(lastProfileName, R.string.status_home)
                }
                AutoMode.start(autoModeHost, AutoMode.Situation.Home)
                // Ядра тут нет, тиков статуса не будет — но обстановка меняться будет,
                // и шторка обязана идти за ней. Запускаем ПОСЛЕ AutoMode.start: первое же
                // значение придёт уже настоящим, без мигания текста. Тики подключит
                // resumeTunnel, когда автомат поднимет туннель.
                notification.start(coreLive = false)
                // Лучшего момента наполнить кэш наборов правил не будет: дома интернет
                // открыт и наш домен достижим напрямую, а нужен кэш ровно там, где его
                // уже не наполнить — в урезанной сети. Ядра здесь нет, поэтому список
                // наборов читаем прямо из профиля.
                fillRuleSetsAtHome(content)
                return
            }

            // Комната на старте НЕ поднимается — она нужна не всегда, а держать чужой
            // видеозвонок круглосуточно и есть то, чего схема избегает. Единственное
            // исключение: человек уже выбрал комнату руками, и этот выбор пережил
            // перезапуск сервиса — тогда поднимаем сразу, ДО sing-box, потому что для
            // sing-box это обычный socks-outbound на 127.0.0.1.
            roomWanted = !Settings.autoModeEnabled && Settings.autoModeManualRoom
            if (roomWanted) {
                // Поднимаем ПАРАЛЛЕЛЬНО старту ядра, а не до него. Подъём комнаты стоит
                // до полутора минут (вход в чужой видеозвонок), и пока он шёл, человек
                // смотрел на «Подключаюсь» и считал, что приложение стало медленнее.
                // Для sing-box комната — обычный socks-выход на петле: пока её нет, он
                // просто не может через неё ходить, а круг честно пишет «Поднимаю комнату».
                Log.i(TAG, "комната выбрана человеком — поднимаю её параллельно старту ядра")
                thread(name = "olcrtc-warmup", isDaemon = true) { startOlcRtcIfEnabled() }
            }

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
            checkPathsHonestly("старт сервиса")

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
            notification.start(coreLive = true)
            AutoMode.start(autoModeHost)
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
        //
        // Смотрим на саму комнату, а не на тумблер: тумблер стал аварийным выключателем
        // и по умолчанию разрешает комнату, а вывод приложения из туннеля нужен ровно
        // тогда, когда комната живёт. Пересборка ядра идёт в обе стороны (см.
        // [setRoomWanted]), поэтому исключение появляется и снимается вместе с ней.
        val roomLive = roomWanted || OlcRtcCore.state is OlcRtcCore.State.Ready
        val selfOutsideTun = roomLive && service is VpnService

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
     * Отдаёт пробам защиту от нашего же tun.
     *
     * Пробы ходят по физической сети, но привязки к ней мало: правило per-uid для VPN
     * стоит выше неё, и при поднятом туннеле «прямая» проба уходила в наш собственный
     * tun — то есть подтверждала дом сама себе. `protect(fd)` есть только у `VpnService`,
     * поэтому крючок ставит сервис, а [ProbeSocket] его только зовёт.
     *
     * Общий обход (`allowBypass`) для этого не годится: он открыл бы дорогу мимо туннеля
     * всем приложениям сразу. Постоянный вывод себя из tun — тоже: тогда мимо туннеля
     * пойдёт весь наш трафик, а не одна проба.
     */
    private fun installProbeProtector() {
        val vpn = service as? VpnService ?: return
        ProbeSocket.useProtector(
            object : ProbeSocket.Protector {
                override fun protect(socket: Socket): Boolean = note(vpn.protect(socket))

                override fun protect(socket: DatagramSocket): Boolean = note(vpn.protect(socket))

                /** Незащищённая проба меряет наш же туннель — это должно быть видно в логе. */
                private fun note(ok: Boolean): Boolean {
                    if (!ok) Log.w(TAG, "проба: сокет защитить не вышло, замер пойдёт через наш же tun")
                    return ok
                }
            },
        )
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
        // Ни параметров комнаты, ни разрешения — стартовать нечем и незачем.
        if (!OlcRtcParams.roomAllowed) return

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
                // Комната умирает молча — дальше за каналом следит присмотр и поднимает
                // ядро сам, если байты перестали ходить.
                OlcRtcWatchdog.start(protector, requireProtector = vpn != null)
            }

            else ->
                // Честно говорим и идём дальше без этого выхода.
                Log.w(TAG, "olcRTC: выход не поднят (${OlcRtcCore.lastError}), продолжаем без него")
        }
    }

    /**
     * Конфиг, с которым реально стартует sing-box.
     *
     * Три правки, все только в памяти — файл профиля приходит с сервера и не наш:
     *  1. Наборы правил берутся из своего кэша ([RuleSetLocalPatch]). Идёт первой: без неё
     *     ядро на старте лезет за 22 наборами на наш домен, и в сети с белым списком старт
     *     не проходит вовсе — а значит не проходит и всё остальное.
     *  2. Комната поднялась — в маршруты идёт отказ по UDP/443 ([OlcRtcConfigPatch]).
     *     Выключена или не поднялась — не трогаем: резать QUIC ради мёртвого выхода
     *     незачем, от этого только хуже.
     *  3. На каждый путь — свой локальный вход и правило, которое привязывает вход к
     *     выходу ([ProbeInboundPatch]). Без этого спросить «жив ли путь» можно было
     *     только через общий вход, то есть через тот выход, который выбран прямо сейчас,
     *     и ради замера приходилось переставлять селектор живым людям под руку.
     *
     * Не легла третья правка — путь просто останется без входа, и проба скажет
     * «не проверено». Врать «работает» она в этом случае не имеет права.
     */
    private fun effectiveConfig(content: String): String {
        var result = content

        val rules = RuleSetLocalPatch.useCached(result, RuleSetCache.cached())
        RuleSetLocalPatch.log(rules)
        RuleSetCache.report(rules)
        // Список нужен докачке: после правки удалённых наборов в конфиге уже нет,
        // и спросить «что вообще положено иметь» будет не у кого.
        ruleSetRemotes = rules.remotes
        result = rules.content

        // Страховка идёт сразу после наборов и до всего остального: если правила не
        // доехали, дальше править было бы уже нечего — трафик ушёл бы в `final: direct`.
        val lifeline = LifelinePatch.addLifeline(result)
        LifelinePatch.log(lifeline)
        result = lifeline.content

        // Стек туннеля и предел ожидания распознавания правим всегда, а не только под
        // комнатой: и порты трансляции, и бесконечное ожидание первых байт бьют по
        // любому выходу — в комнате это было просто заметнее.
        val stack = OlcRtcConfigPatch.tunnelStack(result)
        OlcRtcConfigPatch.log(stack)
        result = stack.content

        val sniff = OlcRtcConfigPatch.sniffTimeout(result)
        OlcRtcConfigPatch.log(sniff)
        result = sniff.content

        if (Settings.olcrtcEnabled && OlcRtcCore.state is OlcRtcCore.State.Ready) {
            val v4 = OlcRtcConfigPatch.onlyIpv4(result)
            OlcRtcConfigPatch.log(v4)
            result = v4.content
        }


        if (Settings.olcrtcEnabled && OlcRtcCore.state is OlcRtcCore.State.Ready) {
            val quic = OlcRtcConfigPatch.addQuicReject(result, OlcRtcParams.socksPort)
            OlcRtcConfigPatch.log(quic)
            result = quic.content
        }

        val layout = AutoModeExits.parse(result, OlcRtcParams.socksPort)
        val probe = ProbeInboundPatch.addProbeInbounds(result, layout.measurable)
        ProbeInboundPatch.log(probe)
        result = probe.content

        runningConfig = result
        return result
    }

    /**
     * Конфиг, который ядро исполняет прямо сейчас. Автомату нужен именно он, а не файл
     * профиля: закреплённые за путями входы живут только в памяти, и по файлу их не видно.
     */
    @Volatile
    private var runningConfig: String? = null

    /**
     * Наборы правил, которые просит конфиг сервера. Заполняется при каждой сборке конфига
     * и живёт до следующей: докачке нужно знать, чего в кэше не хватает, а из готового
     * конфига это уже не видно — удалённые наборы оттуда убраны.
     */
    @Volatile
    private var ruleSetRemotes: List<RuleSetLocalPatch.Remote> = emptyList()

    /**
     * Разовый честный опрос всех путей: сразу после полного старта сервиса и при каждом
     * подъёме туннеля из погашенного состояния ([resumeTunnel]).
     *
     * Это и проверка самой правки (входы поднялись, привязка работает), и первый честный
     * снимок: какой путь несёт трафик, а какой нет. Селектор при этом не трогается вообще —
     * каждый путь спрашивается через свой вход, поэтому пути можно мерить один за другим,
     * ничего не переключая.
     *
     * Результат идёт не только в лог, но и в [PathRegistry]: список выходов на экране и
     * ручной режим круга читают именно его, а не строку лога. Без этого проба честно
     * находила мёртвый путь, а человек всё равно видел «не проверяли» или «Подключено»
     * (поймано на стенде 08.08.2026).
     */
    private fun checkPathsHonestly(reason: String) {
        val content = runningConfig ?: return
        val layout = AutoModeExits.parse(content, OlcRtcParams.socksPort)
        val entries = layout.probeEntries
        if (entries.isEmpty()) {
            Log.w(TAG, "проверка путей ($reason): закреплённых входов нет, мерить нечем")
            // Мерить нечем — но наборы правил всё равно нужны, и своя дорога у докачки
            // есть: socks самой комнаты. Раньше сюда доходила проба прямого выхода, и
            // докачка ехала на ней; выхода этого больше не меряем (см. измеряемые пути
            // в [AutoModeExits]), и без этого вызова конфиг без селектора остался бы
            // вообще без наборов.
            refreshRuleSets("проверка путей: $reason", liveProbePort = null)
            return
        }
        // Реестру нужны имена выходов, чтобы было куда положить замер. Имена обычно
        // привязывает AutoMode.start(), но при подъёме из погашенного состояния и на
        // самом первом холодном старте этот заход может случиться раньше него —
        // привязываем сами по тому же конфигу. Вызов безопасный: bindExits не трогает
        // уже собранные замеры, только имена (см. его комментарий).
        PathRegistry.bindExits(main = layout.main, room = layout.room)
        thread(name = "path-selfcheck", isDaemon = true) {
            Log.i(TAG, "проверка путей ($reason): ${entries.size} шт., селектор не трогаем")
            // Первый путь, который проба назвала живым: по нему пойдёт докачка наборов.
            var liveEntry: AutoModeExits.Endpoint? = null
            for ((exit, entry) in entries) {
                // Цель берём из обычной ротации, а не диагностическую: раньше эта
                // проверка шла только на холодном старте, и разовый вопрос «каким
                // адресом меня видно» был дёшев. Теперь она идёт при каждом подъёме
                // туннеля, а такой запрос — редкий и потому приметный: обычные
                // проверки связи в трафике теряются, ifconfig.me нет.
                val measurement = HonestProbe.measure(entry, exit)
                Log.i(TAG, "путь «$exit»: $measurement")
                val id = PathRegistry.snapshot.value.byExit(exit)?.def?.id
                if (id == null) {
                    // Сюда попадать больше нечему: мерим ровно те выходы, которые реестр
                    // умеет помнить (см. [AutoModeExits.Layout.measurable]). Проверка
                    // остаётся сторожем — потраченная наружу проба, которую некуда
                    // положить, должна быть видна сразу, а не выясняться разбором.
                    Log.w(TAG, "путь «$exit»: реестр не знает такого выхода, замер потерян")
                    continue
                }
                if (measurement.live) {
                    PathRegistry.alive(id, measurement.latencyMs)
                    if (liveEntry == null) liveEntry = entry
                } else if (measurement.measured) {
                    PathRegistry.dead(id, measurement.reason)
                }
                // Unmeasurable сюда не попадает: реестру сказать нечего, прошлое знание
                // не трогаем — своя же гарантия HonestProbe, повторять её тут незачем.
            }
            // Наборы правил докачиваем ровно здесь: связь уже есть и она только что
            // померена, а не предположена.
            refreshRuleSets("проверка путей: $reason", liveEntry?.port)
        }
    }

    /**
     * Достаёт недостающие наборы правил — если есть чем.
     *
     * Путь выбирается сам и в этом весь смысл. Через общий вход идти нельзя: маршруты
     * ведут как раз те правила, которых у нас ещё нет, и наш домен ушёл бы «напрямую» —
     * туда, где в урезанной сети его и срезали. Поэтому берём либо закреплённый за живым
     * путём вход ([ProbeInboundPatch]), либо socks самой комнаты: комната ходит наружу
     * своим ходом и в такой сети остаётся единственной живой дорогой.
     *
     * Отдельным потоком: зовут в том числе из-под `tunnelLock`, а качать под замком нельзя.
     */
    private fun refreshRuleSets(reason: String, liveProbePort: Int?) {
        val remotes = ruleSetRemotes
        if (remotes.isEmpty()) return
        val roomUp = OlcRtcCore.state is OlcRtcCore.State.Ready && OlcRtcCore.isRunning()
        val port = liveProbePort ?: OlcRtcParams.socksPort.takeIf { roomUp && it > 0 }
        if (port == null) {
            Log.i(TAG, "наборы правил не докачиваем ($reason): живого пути нет, комната не поднята")
            return
        }
        thread(name = "ruleset-refresh", isDaemon = true) {
            RuleSetCache.refresh(remotes, port, reason)
        }
    }

    /**
     * Наполнить кэш наборов, пока мы дома.
     *
     * Дома ядра нет и путей нет — мерить нечего, но интернет открыт и наш домен достижим
     * напрямую ([RuleSetCache.DIRECT]). Момент важный: кэш нужен в урезанной сети, а там
     * его уже не наполнить. Что просит конфиг — читаем из самого профиля: правку конфига
     * тут никто не накладывал, и списка наборов иначе взять неоткуда.
     */
    private fun fillRuleSetsAtHome(content: String) {
        val known = RuleSetLocalPatch.useCached(content, RuleSetCache.cached())
        if (known.remotes.isEmpty()) return
        RuleSetCache.report(known)
        ruleSetRemotes = known.remotes
        thread(name = "ruleset-refresh-home", isDaemon = true) {
            RuleSetCache.refresh(known.remotes, RuleSetCache.DIRECT, "дома, напрямую")
        }
    }

    /**
     * Гасит ядро и tun, оставляя сервис жить.
     *
     * Именно этим отличается от остановки: `stopSelf` убил бы и подписку на смену сети,
     * а тогда возвращение туннеля при уходе из дома пришлось бы чем-то будить.
     *
     * Гасить или нет — решаем по делу, а не по флагу ([TunnelFacts]): пока решал флаг,
     * любой подъём ядра мимо автомата запирал систему намертво. Флаг говорил «погашено»,
     * tun при этом висел, и каждый заход автомата упирался в честное «нечего делать».
     *
     * @return false, если гасить нечего: и флаг говорит «погашено», и на деле ничего
     *   не живо. Автомату это значит «ничего не делал».
     */
    private fun suspendTunnel(reason: String): Boolean {
        synchronized(tunnelLock) {
            val tunOpen = fileDescriptor != null
            val roomLive = OlcRtcCore.isRunning()
            if (!TunnelFacts.suspendNeeded(tunnelSuspended, tunOpen, roomLive)) return false
            if (tunnelSuspended) {
                val alive = listOfNotNull("tun".takeIf { tunOpen }, "комната".takeIf { roomLive })
                Log.w(TAG, "туннель числился погашенным, а живо: ${alive.joinToString(", ")} — гашу по факту")
            }
            Log.i(TAG, "туннель гасим: $reason")
            // Комната без туннеля бессмысленна: дома обход делает роутер.
            roomWanted = false
            stopOlcRtc()
            val pfd = fileDescriptor
            if (pfd != null) {
                runCatching { pfd.close() }
                fileDescriptor = null
            }
            closeService()
            tunnelSuspended = true
        }
        // Ядро снято — тиков не будет, и прошлые скорости мерили уже несуществующий путь.
        notification.detachCore()
        runBlocking(Dispatchers.Main) { notification.refresh(R.string.status_home) }
        return true
    }

    /** @return false, если туннель и так был поднят. */
    private fun resumeTunnel(reason: String): Boolean {
        synchronized(tunnelLock) {
            if (!tunnelSuspended) return false
            Log.i(TAG, "туннель поднимаем: $reason")
        }
        return try {
            // Комнату вместе с туннелем не поднимаем: понадобится — попросят отдельно.
            if (roomWanted) startOlcRtcIfEnabled()
            restartCore()
            // Ядро только что поднялось начисто — реестр путей пуст или помнит прошлую
            // сессию. Тот же честный опрос, что и на холодном старте: без него реестр
            // оставался бы пустым до первого обычного захода автомата, а тот при ручном
            // выборе выхода основной канал вообще не мерит (round() при выключенном
            // автомате трогает только комнату). Раньше эта проверка шла лишь при полном
            // старте сервиса — при подъёме из погашенного состояния не запускалась ни разу
            // (лог «туннель поднимаем: автомат выключен человеком», реестр пуст).
            checkPathsHonestly("подъём из погашенного: $reason")
            synchronized(tunnelLock) { tunnelSuspended = false }
            // Ядро снова живо — цепляемся к его тикам. Без этого сессия, начатая дома,
            // так и осталась бы без скоростей, а текст замер бы на «Работает».
            notification.attachCore()
            runBlocking(Dispatchers.Main) { notification.refresh(R.string.status_started) }
            true
        } catch (e: Exception) {
            // Сервис не роняем: сеть могла ещё не устояться, следующий заход попробует снова.
            Log.w(TAG, "туннель не поднялся ($reason): ${e.message}")
            false
        }
    }

    /**
     * Пересобирает ядро с текущим конфигом. В отличие от [serviceReload0] не роняет
     * сервис при неудаче: сюда приходят из автомата, где неудача — это просто «ещё раз
     * через минуту», а не повод выключить всё.
     */
    private fun restartCore() {
        val profile = runBlocking { ProfileManager.get(Settings.selectedProfile) }
            ?: error("профиль не выбран")
        val content = File(profile.typed.path).readText()
        if (content.isBlank()) error("конфиг пуст")
        lastProfileName = profile.name
        commandServer.startOrReloadService(
            effectiveConfig(content),
            OverrideOptions().apply {
                autoRedirect = Settings.autoRedirect
                applyPerAppProxy()
            },
        )
    }

    /**
     * Поднимает или гасит ядро комнаты по требованию.
     *
     * Вызов идемпотентный: если комната уже в нужном состоянии, ничего не происходит и
     * возвращается false. Это важно — автомат зовёт этот метод каждым заходом, и мигать
     * видеозвонком от повторного «оставь как есть» нельзя.
     *
     * Ядро мало поднять (и мало погасить): пока комната работает, конфигу нужен запрет
     * QUIC — через SOCKS5 комнаты UDP не ходит. Значит правка живёт ровно столько же,
     * сколько сама комната, и пересборка ядра обязательна в обе стороны.
     *
     * Подъём при этом **не блокирует того, кто попросил**: вход в чужой видеозвонок
     * стоит до полутора минут, и пока он шёл в потоке автомата, автомат не делал ни одного
     * захода — экран и шторка замирали на 69 секунд (замер 08.08.2026). Поэтому подъём
     * уходит в свой поток, наверх сразу возвращается [AutoMode.RoomAck.Raising], а итог
     * приезжает двумя путями: в реестр путей — через [RoomNote], и в автомат — вызовом
     * [AutoMode.onCoreRebuilt], который будит заход, не дожидаясь его ритма.
     *
     * Гашение осталось синхронным: оно быстрое, и ждать его не больно.
     *
     * @return чем кончилась просьба. Разница между «не стал спрашивать» и «не встала»
     *   стоит двух минут простоя — см. [AutoMode.RoomAck].
     */
    private fun setRoomWanted(wanted: Boolean, reason: String): AutoMode.RoomAck {
        // Поднимать нечего, если параметров комнаты нет или человек нажал аварийный
        // выключатель. Гасить — можно всегда.
        if (wanted && !OlcRtcParams.roomAllowed) {
            Log.i(TAG, "комната не поднимается ($reason): комната выключена или нет её параметров")
            return AutoMode.RoomAck.Unavailable
        }
        synchronized(tunnelLock) {
            if (tunnelSuspended) {
                // Дома туннеля нет, поднимать комнату некуда и незачем.
                // Причину пишем вслух: этот отказ выглядит в логе как «попробовал поднять
                // и через семь миллисекунд не встала», и час разбирательств 08.08.2026
                // ушёл ровно на то, чтобы понять — комнату никто и не пробовал поднимать.
                Log.i(TAG, "комната не поднимается ($reason): туннель погашен")
                roomWanted = false
                return AutoMode.RoomAck.NoTunnel
            }
            if (wanted && roomRaising) {
                Log.i(TAG, "комната уже поднимается ($reason) — второй раз не прошу")
                return AutoMode.RoomAck.Raising
            }
            val up = OlcRtcCore.state is OlcRtcCore.State.Ready && OlcRtcCore.isRunning()
            roomWanted = wanted
            if (wanted == up) {
                Log.i(TAG, "комната уже ${if (up) "поднята" else "погашена"} ($reason) — оставляю как есть")
                return AutoMode.RoomAck.Unchanged
            }

            if (wanted) {
                Log.i(TAG, "комната нужна ($reason) — поднимаю ядро отдельным потоком")
                roomRaising = true
                // Реестр узнаёт про подъём сразу, а не когда автомат дойдёт до своей записи:
                // на круге и в шторке это и есть «Поднимаю комнату».
                RoomNote.raising()
                thread(name = "olcrtc-raise", isDaemon = true) { raiseRoom(reason) }
                return AutoMode.RoomAck.Raising
            }

            Log.i(TAG, "комната больше не нужна ($reason) — гашу ядро")
            stopOlcRtc()
            return runCatching {
                restartCore()
                AutoMode.RoomAck.Changed
            }.getOrElse {
                Log.w(TAG, "пересборка ядра под комнату не удалась: ${it.message}")
                AutoMode.RoomAck.Failed
            }
        }
    }

    /**
     * Собственно подъём комнаты — целиком в своём потоке.
     *
     * Замок берётся только на пересборку ядра, а не на сам подъём: держать его полторы
     * минуты значило бы заморозить и гашение туннеля, и уход домой.
     */
    private fun raiseRoom(reason: String) {
        startOlcRtcIfEnabled()
        val rebuilt = synchronized(tunnelLock) {
            roomRaising = false
            when {
                OlcRtcCore.state !is OlcRtcCore.State.Ready -> {
                    Log.w(TAG, "комната не встала ($reason): ${OlcRtcCore.lastError}")
                    roomWanted = false
                    false
                }

                // Пока поднимались, туннель успели погасить (ушли домой, выключили автомат).
                // Комната без туннеля бессмысленна, и оставить её висеть нельзя — это ровно
                // тот круглосуточный чужой видеозвонок, которого схема избегает.
                tunnelSuspended -> {
                    Log.i(TAG, "комната встала, но туннель за это время погасили ($reason) — гашу её")
                    roomWanted = false
                    stopOlcRtc()
                    false
                }

                else -> runCatching {
                    restartCore()
                    true
                }.getOrElse {
                    Log.w(TAG, "пересборка ядра под комнату не удалась: ${it.message}")
                    false
                }
            }
        }
        // Что бы ни вышло — реестр узнаёт об этом сразу, не дожидаясь круга автомата.
        RoomNote.note()
        if (!rebuilt) return
        // Комната встала — в урезанной сети это единственная дорога наружу, и именно
        // сейчас появляется возможность дотянуть недостающие наборы правил.
        refreshRuleSets("комната поднята ($reason)", null)
        // Ядро пересобрано у автомата за спиной: его выбор в селекторе сброшен, и ждать
        // очередного шага ритма незачем — комната уже стоит.
        AutoMode.onCoreRebuilt("комната поднята ($reason)")
    }

    /**
     * Гасим ПОСЛЕ sing-box: пока он жив, он может ходить в этот socks.
     *
     * Присмотр снимаем первым: иначе обычная остановка выглядит для него как упавший
     * канал, и он полезет поднимать ядро обратно ровно в момент выключения.
     */
    private fun stopOlcRtc() {
        runCatching { OlcRtcWatchdog.stop() }
            .onFailure { Log.w(TAG, "olcRTC: присмотр не снялся: ${it.message}") }
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
        // Дома ядра нет намеренно: обход делает роутер, туннель на телефоне лишний.
        // Перечитывание конфига (расписание UpdateProfileWork, обновление подписки,
        // экран настроек) поднимало ядро обратно мимо автомата и мимо флага. Дальше
        // автомат считал туннель погашенным, на каждом заходе звал гашение, а то честно
        // отвечало «уже погашено» и ничего не делало — tun висел до ручного выключения,
        // и выглядело это как «сам включился и не выключается».
        // Новый конфиг не теряется: при следующем подъёме restartCore читает файл заново.
        if (synchronized(tunnelLock) { tunnelSuspended }) {
            Log.i(TAG, "конфиг перечитан, но мы дома — ядро не поднимаем")
            fillRuleSetsAtHome(content)
            return
        }
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
        // Автомат снимаем первым: иначе обычное выключение выглядит для него как
        // обстановка, в которой надо срочно что-то поднять.
        AutoMode.stop()
        // Сервис уходит — его protect(fd) больше ничего не делает, и держать крючок
        // значит обещать пробам защиту, которой уже нет.
        ProbeSocket.useProtector(null)
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkListener.stop(AutoMode)
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
        AutoMode.stop()
        ProbeSocket.useProtector(null)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkListener.stop(AutoMode)
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
        // Ставим до всего остального: дома ядро не поднимается вовсе, а проба «мы дома»
        // идёт с первого же захода — и защита ей нужна ровно тогда же.
        installProbeProtector()

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
