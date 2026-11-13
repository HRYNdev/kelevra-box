package io.nekohasekai.sfa.vendor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.KLog
import kotlinx.coroutines.CancellationException

/**
 * Фоновая загрузка обновления на переднем плане, с уведомлением «Загружается обновление».
 *
 * Обычную фоновую работу система вправе остановить в любой момент и не дольше чем через
 * 10 минут останавливает всегда; на Honor и подобных фон режется ещё раньше. Файл в 55 МБ
 * на медленной сети так не докачивался никогда. Работа на переднем плане живёт, пока качает.
 */
internal object ZagruzkaNaPerednem {
    private const val TAG = "KelevraObnovlenie"
    private const val CHANNEL_ID = "kelevra_update_zagruzka"
    private const val NOTIFICATION_ID = 0x5F21

    suspend fun zakrepit(worker: CoroutineWorker, versiya: String) {
        val context = worker.applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Загрузка обновления", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle("Загружается обновление")
                .setContentText("Версия $versiya")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
            worker.setForeground(info)
            KLog.i(TAG, "загрузка обновления идёт на переднем плане")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ может не пустить на передний план из фона. Тогда качаем обычной
            // работой, как раньше: оборвётся — недокачанное продолжит следующая.
            KLog.w(TAG, "загрузка обновления идёт без переднего плана: ${e.message}")
        }
    }
}
