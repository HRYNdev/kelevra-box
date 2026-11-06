package io.nekohasekai.sfa.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import android.net.IpPrefix
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    companion object {
        private const val TAG = "VPNService"

        /**
         * Сколько ждём, пока ядро ОС уберёт прежний tun, прежде чем поднимать новый.
         *
         * Три секунды: наблюдавшиеся честные уборки укладывались в 1665 мс (замеры
         * 13.08.2026: 1, 4, 5, 6, 528, 673, 738, 1312, 1665 мс), а всё, что дольше, на
         * стенде не заканчивалось и через 10 секунд — это уже не медленная уборка, а
         * прижатый дескриптор (см. ниже). Ждать бесконечно нельзя: одна такая уборка
         * навсегда оставила бы человека без туннеля. По истечении срока поднимаем новый
         * и пишем это в лог.
         */
        private const val OLD_TUN_WAIT_MILLIS = 3_000L

        /**
         * Жив ли ещё интерфейс с таким именем, по данным ядра ОС.
         *
         * Спрашиваем sysfs: там интерфейс лежит ровно столько, сколько живёт в ядре.
         * `/proc/net/dev` для этого не годится — приложению его читать не дают
         * (`avc: denied { read } ... tcontext=u:object_r:proc_net:s0`, поймано в логе
         * 13.08.2026), а `runCatching` вокруг превращал отказ в «интерфейсов нет вовсе».
         * Запасной путь — `java.net`, на случай если и sysfs однажды закроют.
         */
        private fun interfaceAlive(name: String): Boolean =
            if (File("/sys/class/net/lo").exists()) {
                File("/sys/class/net/$name").exists()
            } else {
                runCatching { NetworkInterface.getByName(name) != null }.getOrDefault(false)
            }
    }

    private val service = BoxService(this, this)

    /**
     * Имя интерфейса, поднятого прошлым [establish]. Нужно, чтобы дождаться его уборки
     * перед следующим подъёмом — см. [awaitOldTunGone].
     *
     * Имя приходит от самого ядра sing-box ([registerMyInterface]): оно спрашивает его у
     * ядра ОС через `TUNGETIFF` по нашему же дескриптору, а из Kotlin имя интерфейса по
     * дескриптору взять нечем.
     */
    @Volatile
    private var lastTunName: String? = null

    override fun registerMyInterface(name: String?) {
        lastTunName = name
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand(intent)

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    /**
     * Ждёт, пока ядро ОС уберёт tun прошлой сессии, и только потом отдаёт ход новому
     * [establish].
     *
     * ЗАЧЕМ. `VpnService.Builder.establish()` каждым вызовом заводит НОВЫЙ интерфейс, а
     * прежний живёт в ядре ОС до тех пор, пока ядро не отпустит его файл. Дескрипторов у
     * этого файла два: наш ([BoxService.fileDescriptor]) и копия нативной стороны — libbox
     * делает `dup()` от отданного нами номера (`experimental/libbox/service.go`,
     * `OpenInterface`) и закрывает её сам, гася прежний экземпляр. Порядок закрытия у нас
     * верный: к этому вызову оба номера из таблицы дескрипторов уже сняты.
     *
     * ЧТО РАЗОБРАНО ПРИБОРНО (эмулятор, 13.08.2026). Снятие номера — ещё не освобождение
     * файла: последнее делает `____fput`, а он ставится задачей на ТОТ ПОТОК, который
     * уронил последнюю ссылку, и исполняется, когда поток выйдет в пользовательский режим.
     * kprobe на `tun_chr_close`/`__tun_detach` показал: на чистом переходе освобождение
     * приходит через 150-180 мс, на утёкшем — не приходит вовсе, а стек в момент, когда
     * оно всё-таки случилось, был такой: `tun_chr_close ← ____fput ← task_work_run ←
     * get_signal` — то есть отложенная задача сработала только когда процессу прислали
     * SIGKILL. До этого интерфейс висел при НУЛЕ открытых `/dev/tun` во всей системе
     * (перепись по всем `/proc/<pid>/fd`: ни у нас, ни у `system_server`).
     *
     * ОТСЮДА ЧЕСТНАЯ ГРАНИЦА. Ожидание лечит те переходы, где освобождение просто
     * запаздывает, и не лечит те, где нативный поток держит файл прижатым: там счётчик
     * растёт на единицу, и это видно в логе строкой «не убран за N мс». Корень — в том,
     * что ядро sing-box не выводит своего читателя tun из ядра ОС до закрытия файла; из
     * Kotlin это не чинится, нужна пересборка libbox. Второй путь, который убирает утечку
     * по построению, — вообще не пересобирать ядро на переключении комнаты (тогда
     * `establish` не зовётся ни разу и новых интерфейсов не появляется); он требует
     * сделать постоянными и запрет QUIC, и вывод своего пакета из tun.
     */
    private fun awaitOldTunGone() {
        val old = lastTunName ?: return
        val started = SystemClock.elapsedRealtime()
        while (interfaceAlive(old)) {
            if (SystemClock.elapsedRealtime() - started >= OLD_TUN_WAIT_MILLIS) {
                KLog.w(TAG, "прежний tun $old не убран за $OLD_TUN_WAIT_MILLIS мс — поднимаю новый поверх")
                return
            }
            Thread.sleep(20)
        }
        lastTunName = null
        KLog.i(TAG, "прежний tun $old убран ядром ОС за ${SystemClock.elapsedRealtime() - started} мс")
    }

    /**
     * Снимок того, что просит ядро: читается из [TunOptions] один раз (его итераторы
     * одноразовые) и дальше служит и для сравнения с прежним tun, и для сборки [Builder].
     * Побочно выставляет [systemProxyAvailable]/[systemProxyEnabled] — как раньше.
     */
    private fun snimok(options: TunOptions): TunSnimok {
        val addresses = mutableListOf<TunSnimok.Prefix>()
        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            addresses += TunSnimok.Prefix(address.address(), address.prefix())
        }
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            addresses += TunSnimok.Prefix(address.address(), address.prefix())
        }
        val hasInet4 = addresses.any { !it.address.contains(':') }
        val hasInet6 = addresses.any { it.address.contains(':') }

        val dns = mutableListOf<String>()
        val routes = mutableListOf<TunSnimok.Prefix>()
        val excludeRoutes = mutableListOf<TunSnimok.Prefix>()
        val includePackages = mutableListOf<String>()
        val excludePackages = mutableListOf<String>()

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) dns += dnsServerAddress.next()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val r = inet4RouteAddress.next()
                        routes += TunSnimok.Prefix(r.address(), r.prefix())
                    }
                } else if (hasInet4) {
                    routes += TunSnimok.Prefix("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val r = inet6RouteAddress.next()
                        routes += TunSnimok.Prefix(r.address(), r.prefix())
                    }
                } else if (hasInet6) {
                    routes += TunSnimok.Prefix("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    val r = inet4RouteExcludeAddress.next()
                    excludeRoutes += TunSnimok.Prefix(r.address(), r.prefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    val r = inet6RouteExcludeAddress.next()
                    excludeRoutes += TunSnimok.Prefix(r.address(), r.prefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                while (inet4RouteAddress.hasNext()) {
                    val r = inet4RouteAddress.next()
                    routes += TunSnimok.Prefix(r.address(), r.prefix())
                }

                val inet6RouteAddress = options.inet6RouteRange
                while (inet6RouteAddress.hasNext()) {
                    val r = inet6RouteAddress.next()
                    routes += TunSnimok.Prefix(r.address(), r.prefix())
                }
            }

            val includePackage = options.includePackage
            while (includePackage.hasNext()) includePackages += includePackage.next()

            val excludePackage = options.excludePackage
            while (excludePackage.hasNext()) excludePackages += excludePackage.next()
        }

        var httpProxy: TunSnimok.HttpProxy? = null
        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                httpProxy = TunSnimok.HttpProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toList(),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        return TunSnimok.of(
            mtu = options.mtu,
            addresses = addresses,
            dns = dns,
            routes = routes,
            excludeRoutes = excludeRoutes,
            includePackages = includePackages,
            excludePackages = excludePackages,
            httpProxy = httpProxy,
            allowBypass = Settings.allowBypass,
        )
    }

    /** [Builder] из снимка — ровно то, что раньше собиралось прямо из [TunOptions]. */
    private fun builderIz(s: TunSnimok): Builder {
        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(s.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (s.allowBypass) {
            builder.allowBypass()
        }

        for (a in s.addresses) builder.addAddress(a.address, a.prefix)
        for (d in s.dns) builder.addDnsServer(d)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (r in s.routes) builder.addRoute(IpPrefix(InetAddress.getByName(r.address), r.prefix))
            for (r in s.excludeRoutes) builder.excludeRoute(IpPrefix(InetAddress.getByName(r.address), r.prefix))
        } else {
            for (r in s.routes) builder.addRoute(r.address, r.prefix)
        }

        for (nextPackage in s.includePackages) {
            try {
                builder.addAllowedApplication(nextPackage)
                KLog.d("VPNService", "addAllowedApplication: $nextPackage")
            } catch (e: NameNotFoundException) {
                KLog.e("VPNService", "addAllowedApplication failed", e)
            }
        }

        for (nextPackage in s.excludePackages) {
            try {
                builder.addDisallowedApplication(nextPackage)
                KLog.d("VPNService", "addDisallowedApplication: $nextPackage")
            } catch (e: NameNotFoundException) {
                KLog.e("VPNService", "addDisallowedApplication failed", e)
            }
        }

        s.httpProxy?.let { p ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(p.host, p.port, p.bypass))
            }
        }
        return builder
    }

    /**
     * Отдать ядру интерфейс tun.
     *
     * ПОРЯДОК «СНАЧАЛА НОВЫЙ, ПОТОМ СТАРЫЙ» (make-before-break). libbox при пересборке
     * сначала закрывает прежний экземпляр (его `dup()` нашего дескриптора), потом зовёт
     * `openTun` для нового (`daemon/started_service.go: StartOrReloadService`); сам наш
     * дескриптор ядро не трогает никогда — оно работает с копией. Поэтому к этому вызову
     * прежний tun ещё жив, и у нас три исхода ([TunSnimok.reshenie]):
     *  - просят то же самое (адреса, маршруты, DNS, MTU, списки приложений, прокси) — отдаём
     *    **прежний** дескриптор, `establish()` не зовём: интерфейс и VPN-сеть Android не
     *    трогаются вовсе, соединения приложений живут дальше. Так проходят перечитывание
     *    конфига, правки под комнату и WB — в tun они ничего не меняют;
     *  - просят другое (чаще всего список приложений мимо tun под комнату) — `establish()`
     *    нового ПРИ ОТКРЫТОМ прежнем: Android активирует новый и снимает прежний без паузы
     *    (документация `Builder.establish`: «seamless handover … if the new interface cannot
     *    be created, the existing interface and its file descriptor remain untouched»), после
     *    чего прежний закрываем мы;
     *  - прежнего нет (старт, подъём из погашенного дома) — как раньше, с ожиданием уборки
     *    прошлого интерфейса ([awaitOldTunGone]).
     *
     * Про застрявший читатель прежнего экземпляра (см. [awaitOldTunGone]): при переиспользовании
     * он делит с новым экземпляром тот же файл tun и, проснувшись на первом же пакете, отдаст
     * его в никуда и выйдет — цена один пакет, а интерфейс не утекает: файл больше никто не держит.
     */
    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val snimok = snimok(options)
        val prezhniy = service.fileDescriptor?.takeIf { pfd -> runCatching { pfd.fileDescriptor.valid() }.getOrDefault(false) }
        val prezhniySnimok = service.tunSnimok

        when (TunSnimok.reshenie(prezhniySnimok, snimok, prezhniyZhiv = prezhniy != null)) {
            TunSnimok.Reshenie.PEREISPOLZOVAT -> {
                KLog.i("VPNService", "tun тот же (${prezhniy!!.fd}) — отдаю ядру прежний интерфейс, establish не зову")
                return prezhniy.fd
            }

            TunSnimok.Reshenie.ZAMENIT -> {
                val chem = prezhniySnimok?.raznica(snimok)?.takeIf { it.isNotBlank() } ?: "снимка прежнего нет"
                KLog.i("VPNService", "tun нужен другой ($chem) — поднимаю новый при открытом прежнем (${prezhniy!!.fd})")
            }

            TunSnimok.Reshenie.NOVYY ->
                // Прежнего нет — уборка прошлого интерфейса и подъём нового по очереди, как раньше.
                awaitOldTunGone()
        }

        val builder = builderIz(snimok)
        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        // Имя нового интерфейса придёт следом, в [registerMyInterface]; прежнее с этой
        // секунды недействительно.
        lastTunName = null
        // Прежний tun закрываем ПОСЛЕ того, как новый установлен: Android уже перевёл VPN-сеть
        // на него, и файл прежнего больше никому не нужен. Раньше (12.08.2026) незакрытый
        // дескриптор копил интерфейсы в ядре системы — по одному на каждое переключение.
        service.fileDescriptor?.let { old ->
            if (old !== pfd) {
                val oldFd = runCatching { old.fd }.getOrNull()
                val closed = runCatching { old.close() }.isSuccess
                KLog.i("VPNService", "прежний tun-дескриптор ($oldFd) закрыт после замены: $closed, новый ${pfd.fd}")
            }
        }
        service.fileDescriptor = pfd
        service.tunSnimok = snimok
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)
    override fun cancelNotification(identifier: String, typeID: Int) =
        service.cancelNotification(identifier, typeID)
}
