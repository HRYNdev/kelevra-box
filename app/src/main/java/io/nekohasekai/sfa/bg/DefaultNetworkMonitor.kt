package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Только не-VPN сеть. Слушатель ниже отдаёт именно такие
            // (`NetworkRequest` по умолчанию требует `NOT_VPN`), а этот прямой вопрос
            // системе на старте мог вернуть наш собственный tun — и тогда ядру называют
            // интерфейсом по умолчанию его же туннель. В конфиге при этом стоит
            // `override_android_vpn: true`, то есть штатной защиты от петли нет.
            Application.connectivity.activeNetwork?.takeIf { network ->
                Application.connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
            }
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            for (times in 0 until 10) {
                val linkProperties = Application.connectivity.getLinkProperties(newNetwork)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                // Цикл был нужен, чтобы дождаться готовности свойств сети, а не чтобы
                // повторять само сообщение. Без выхода ядро получало «интерфейс сменился»
                // десять раз на каждое шевеление соты, и каждое такое сообщение запускает
                // полную перепроверку всех узлов группы (таймаут узла до 15 с).
                break
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
}
