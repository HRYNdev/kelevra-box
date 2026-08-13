package io.nekohasekai.sfa.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.toIpPrefix
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

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
                Log.w(TAG, "прежний tun $old не убран за $OLD_TUN_WAIT_MILLIS мс — поднимаю новый поверх")
                return
            }
            Thread.sleep(20)
        }
        lastTunName = null
        Log.i(TAG, "прежний tun $old убран ядром ОС за ${SystemClock.elapsedRealtime() - started} мс")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Settings.allowBypass) {
            builder.allowBypass()
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) {
                    builder.addDnsServer(dnsServerAddress.next())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        val nextPackage = includePackage.next()
                        builder.addAllowedApplication(nextPackage)
                        Log.d("VPNService", "addAllowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e("VPNService", "addAllowedApplication failed", e)
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        val nextPackage = excludePackage.next()
                        builder.addDisallowedApplication(nextPackage)
                        Log.d("VPNService", "addDisallowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e("VPNService", "addDisallowedApplication failed", e)
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        // Уборка прежнего интерфейса и подъём нового — по очереди, а не наперегонки.
        awaitOldTunGone()
        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        // Имя нового интерфейса придёт следом, в [registerMyInterface]; прежнее с этой
        // секунды недействительно.
        lastTunName = null
        // Пересборка ядра зовёт openTun заново, не проходя через гашение туннеля, и прежний
        // дескриптор оставался незакрытым — интерфейс жил в ядре системы без маршрутов и
        // копился до перезапуска приложения. Замер 12.08.2026 на телефоне: восемь tun за
        // вечер, по одному на каждое переключение выхода.
        service.fileDescriptor?.let { old ->
            if (old !== pfd) {
                val oldFd = runCatching { old.fd }.getOrNull()
                val closed = runCatching { old.close() }.isSuccess
                Log.i("VPNService", "прежний tun-дескриптор ($oldFd) закрыт: $closed, новый ${pfd.fd}")
            }
        }
        service.fileDescriptor = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)
}
