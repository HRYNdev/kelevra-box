/*
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sfa.bg

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking

/**
 * Беда (краш-репорты с боевых телефонов, 15-16.08.2026): рассылка слушателям шла голым `forEach`,
 * а слушатели зовутся из [DefaultNetworkListener.networkActor] — падение любого из них
 * убивало не только его, а весь процесс, а заодно и актор: события сети переставали
 * приходить вовсе, до перезапуска приложения. Чистая функция без Android-зависимостей —
 * чтобы её было видно из JVM-теста без стенда с реальным [Network].
 */
internal fun <T> notifyListeners(
    listeners: Collection<(T?) -> Unit>,
    value: T?,
    onError: (Throwable) -> Unit = {},
) {
    for (listener in listeners) {
        runCatching { listener(value) }.onFailure(onError)
    }
}

object DefaultNetworkListener {
    private const val TAG = "DefaultNetworkListener"
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()

        class Update(val network: Network) : NetworkMessage()

        class Lost(val network: Network) : NetworkMessage()
    }

    @OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
    private val networkActor =
        GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            var network: Network? = null
            val pendingRequests = arrayListOf<NetworkMessage.Get>()
            for (message in channel) {
                when (message) {
                    is NetworkMessage.Start -> {
                        if (listeners.isEmpty()) register()
                        listeners[message.key] = message.listener
                        // Свежий слушатель зовётся из того же ConnectivityThread, что и рассылка
                        // ниже: голый вызов здесь ронял бы процесс ровно так же (fix/crashes-15-08).
                        if (network != null) notifyListeners(listOf(message.listener), network) {
                            Log.w(TAG, "слушатель сети упал на Start", it)
                        }
                    }

                    is NetworkMessage.Get -> {
                        check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                        if (network == null) {
                            pendingRequests += message
                        } else {
                            message.response.complete(
                                network,
                            )
                        }
                    }

                    is NetworkMessage.Stop ->
                        if (listeners.isNotEmpty() &&
                            // was not empty
                            listeners.remove(message.key) != null &&
                            listeners.isEmpty()
                        ) {
                            network = null
                            unregister()
                        }

                    is NetworkMessage.Put -> {
                        network = message.network
                        pendingRequests.forEach { it.response.complete(message.network) }
                        pendingRequests.clear()
                        notifyListeners(listeners.values, network) {
                            Log.w(TAG, "слушатель сети упал на Put: ${it.message}")
                        }
                    }

                    is NetworkMessage.Update ->
                        if (network == message.network) {
                            notifyListeners(listeners.values, network) {
                                Log.w(TAG, "слушатель сети упал на Update: ${it.message}")
                            }
                        }

                    is NetworkMessage.Lost ->
                        if (network == message.network) {
                            network = null
                            notifyListeners(listeners.values, null) {
                                Log.w(TAG, "слушатель сети упал на Lost: ${it.message}")
                            }
                        }
                }
            }
        }

    suspend fun start(key: Any, listener: (Network?) -> Unit) = networkActor.send(
        NetworkMessage.Start(
            key,
            listener,
        ),
    )

    suspend fun get(): Network = if (fallback) {
        @TargetApi(23)
        Application.connectivity.activeNetwork
            ?: error("missing default network") // failed to listen, return current if available
    } else {
        NetworkMessage.Get().run {
            networkActor.send(this)
            response.await()
        }
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runBlocking {
            // Появление и пропажа сети — самые дешёвые и самые надёжные отметки времени
            // в разборе «связь пропала». До сих пор они не писались вовсе, и обрыв
            // приходилось выводить из того, что автомат вдруг заговорил про «сети нет».
            Log.i(TAG, "система: сеть $network появилась")
            networkActor.send(
                NetworkMessage.Put(
                    network,
                ),
            )
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // it's a good idea to refresh capabilities
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        /**
         * Адрес и DNS-серверы приезжают на новую сеть отдельным событием, уже после
         * [onAvailable]. Для того, кто спрашивает саму сеть (например, «дома ли мы» по
         * подменным адресам от домашнего роутера), это и есть момент, когда спрашивать
         * стало осмысленно, — поэтому будим слушателей и на него.
         */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) = runBlocking {
            Log.i(TAG, "система: сеть $network пропала")
            networkActor.send(
                NetworkMessage.Lost(
                    network,
                ),
            )
        }
    }

    private var fallback = false
    private val request =
        NetworkRequest.Builder().apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
                removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
        }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        when (Build.VERSION.SDK_INT) {
            in 31..Int.MAX_VALUE ->
                @TargetApi(31)
                {
                    Application.connectivity.registerBestMatchingNetworkCallback(
                        request,
                        Callback,
                        mainHandler,
                    )
                }

            in 28 until 31 ->
                @TargetApi(28)
                { // we want REQUEST here instead of LISTEN
                    Application.connectivity.requestNetwork(request, Callback, mainHandler)
                }

            in 26 until 28 ->
                @TargetApi(26)
                {
                    Application.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }

            in 24 until 26 ->
                @TargetApi(24)
                {
                    Application.connectivity.registerDefaultNetworkCallback(Callback)
                }

            else ->
                try {
                    fallback = false
                    Application.connectivity.requestNetwork(request, Callback)
                } catch (e: RuntimeException) {
                    fallback =
                        true // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
        }
    }

    private fun unregister() {
        runCatching {
            Application.connectivity.unregisterNetworkCallback(Callback)
        }
    }
}
