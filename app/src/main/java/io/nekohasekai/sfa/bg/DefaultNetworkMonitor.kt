package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    private const val TAG = "DefaultNetworkMonitor"

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    /** Догоняет сеть, свойства которой система ещё не выдала. Живёт вне ConnectivityThread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chase: Job? = null

    /**
     * Что ядру сказали последним. Нужно двум вещам: не повторять одно и то же
     * (каждое сообщение ядру = полная перепроверка всех узлов группы, таймаут узла до 15 с)
     * и знать, что ядро сейчас живёт вообще без интерфейса.
     */
    private var reported: Iface? = null

    internal data class Iface(val name: String, val index: Int)

    /**
     * Лестница пауз между попытками узнать интерфейс вернувшейся сети. Первые попытки
     * частые (обычно свойства готовы за десятые доли секунды), дальше реже. Сеть после
     * полного пропадания связи поднимается секундами, поэтому хвост длинный.
     */
    internal fun retryDelayMs(attempt: Int): Long = when {
        attempt < 5 -> 100L
        attempt < 10 -> 250L
        attempt < 20 -> 500L
        else -> 2000L
    }

    /** Суммарно около минуты ожидания: дольше держать погоню смысла нет. */
    internal const val CHASE_ATTEMPTS = 45

    /** Слать ли ядру новое состояние интерфейса. Ответ «нет» экономит полную перепроверку узлов. */
    internal fun shouldReport(previous: Iface?, next: Iface?): Boolean = previous != next

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
        chase?.cancel()
        chase = null
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
        // Ядро подняли заново — оно про сеть не знает ничего, дедупликация начинается с чистого.
        reported = null
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        chase?.cancel()
        chase = null
        val listener = listener ?: return

        if (newNetwork == null) {
            if (shouldReport(reported, null)) {
                reported = null
                listener.updateDefaultInterface("", -1, false, false)
            }
            return
        }

        resolve(newNetwork)?.let { iface ->
            report(listener, iface)
            return
        }

        // Сеть вернулась, но система ещё не выдала её свойства. Раньше здесь стояла
        // секунда Thread.sleep прямо в ConnectivityThread, а по её истечении — молчание:
        // ядро оставалось с «интерфейса нет» до следующего шевеления сети, и трафик
        // приложений с долгими сессиями (карты, банки) не оживал вовсе.
        // Поймано хозяином 22.08.2026 после полного пропадания связи.
        chase = scope.launch {
            for (attempt in 0 until CHASE_ATTEMPTS) {
                delay(retryDelayMs(attempt))
                if (!isActive) return@launch
                // Сеть успела смениться — гнаться за старой незачем, приедет своё событие.
                if (defaultNetwork != newNetwork) return@launch
                val iface = resolve(newNetwork) ?: continue
                report(listener, iface)
                return@launch
            }
            Log.w(TAG, "свойства сети не появились за $CHASE_ATTEMPTS попыток, ядро осталось без интерфейса")
        }
    }

    private fun resolve(network: Network): Iface? {
        val name = Application.connectivity.getLinkProperties(network)?.interfaceName ?: return null
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return null
        return Iface(name, index)
    }

    private fun report(listener: InterfaceUpdateListener, iface: Iface) {
        if (!shouldReport(reported, iface)) return
        reported = iface
        listener.updateDefaultInterface(iface.name, iface.index, false, false)
    }
}
