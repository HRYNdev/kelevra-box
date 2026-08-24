package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.constant.Status

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    companion object {
        private const val TAG = "TileService"
    }

    private val connection = ServiceConnection(this, this)

    override fun onServiceStatusChanged(status: Status) {
        qsTile?.apply {
            state =
                when (status) {
                    Status.Started -> Tile.STATE_ACTIVE
                    Status.Stopped -> Tile.STATE_INACTIVE
                    else -> Tile.STATE_UNAVAILABLE
                }
            updateTile()
        }
    }

    /**
     * Разблокировано ли шифрованное хранилище пользователя.
     *
     * Плитка объявлена `directBootAware` (так в апстриме), поэтому система поднимает её
     * ещё ДО первой разблокировки телефона — а до неё CE-хранилища не существует и любое
     * обращение к Room отвечает `SQLiteCantOpenDatabaseException`.
     */
    private fun userUnlocked(): Boolean =
        (getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

    override fun onStartListening() {
        super.onStartListening()
        // connect() читает Settings.serviceClass(), то есть Room. До разблокировки это
        // роняло ВЕСЬ процесс приложения, а система тут же поднимала плитку заново —
        // получался круг падений каждые три с половиной секунды (замерено в эмуляторе
        // 17.08.2026: девять падений за двадцать восемь секунд). Пока хранилища нет,
        // плитку просто показываем недоступной: нажать на неё всё равно нельзя, а
        // разблокировка приведёт сюда снова.
        if (!userUnlocked()) {
            Log.i(TAG, "телефон ещё не разблокирован — состояние плитки спросим после разблокировки")
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                updateTile()
            }
            return
        }
        connection.connect()
    }

    override fun onStopListening() {
        connection.disconnect()
        super.onStopListening()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        when (connection.status) {
            Status.Stopped -> BoxService.start()
            Status.Started -> BoxService.stop()
            else -> {}
        }
    }
}
