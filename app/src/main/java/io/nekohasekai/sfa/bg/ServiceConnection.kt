package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sfa.aidl.IService
import io.nekohasekai.sfa.aidl.IServiceCallback
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ServiceConnection(private val context: Context, callback: Callback, private val register: Boolean = true) : ServiceConnection {
    companion object {
        private const val TAG = "ServiceConnection"
    }

    private val callback = ServiceCallback(callback)
    private var service: IService? = null

    val status get() = service?.status?.let { Status.values()[it] } ?: Status.Stopped

    fun connect() {
        val intent =
            runBlocking {
                withContext(Dispatchers.IO) {
                    Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
                }
            }
        context.bindService(intent, this, AppCompatActivity.BIND_AUTO_CREATE)
        KLog.d(TAG, "request connect")
    }

    fun disconnect() {
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }
        KLog.d(TAG, "request disconnect")
    }

    fun reconnect() {
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }
        val intent =
            runBlocking {
                withContext(Dispatchers.IO) {
                    Intent(context, Settings.serviceClass()).setAction(Action.SERVICE)
                }
            }
        context.bindService(intent, this, AppCompatActivity.BIND_AUTO_CREATE)
        KLog.d(TAG, "request reconnect")
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = IService.Stub.asInterface(binder)
        this.service = service
        try {
            if (register) service.registerCallback(callback)
            callback.onServiceStatusChanged(service.status)
        } catch (e: RemoteException) {
            KLog.e(TAG, "initialize service connection", e)
        }
        KLog.d(TAG, "service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            KLog.e(TAG, "cleanup service connection", e)
        }
        KLog.d(TAG, "service disconnected")
    }

    override fun onBindingDied(name: ComponentName?) {
        reconnect()
        KLog.d(TAG, "service dead")
    }

    interface Callback {
        fun onServiceStatusChanged(status: Status)

        fun onServiceAlert(type: Alert, message: String?) {
        }
    }

    class ServiceCallback(private val callback: Callback) : IServiceCallback.Stub() {
        override fun onServiceStatusChanged(status: Int) {
            callback.onServiceStatusChanged(Status.values()[status])
        }

        override fun onServiceAlert(type: Int, message: String?) {
            callback.onServiceAlert(Alert.values()[type], message)
        }
    }
}
