package io.nekohasekai.sfa.compose.screen.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.bg.ServiceNotification
import io.nekohasekai.sfa.compose.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Напоминания об оплате: локальные уведомления «Подписка до ДД.ММ · 249 ₽» и «Подписка закончилась · 249 ₽».
 *
 * Когда — решает [napominaniya] (Oplata.kt). Ставятся отложенной разовой работой WorkManager (переживает
 * перезагрузку, постоянной фоновой работы нет) после каждого удачного `/oplata` ([OplataSostoyanie]):
 * продлили срок — время сменилось, работа заменяется; заявка ждёт, бессрочно, неплатящий — снимается.
 * Что уже поставлено, помним в настройках, чтобы опрос раз в 30 с не переставлял одно и то же.
 */
object OplataNapominaniya {
    private const val TAG = "KelevraOplata"
    private const val CHANNEL_ID = "oplata"
    private const val PREFS = "kelevra_oplata_napominaniya"
    private const val DATA_VID = "vid"
    private const val DATA_SROK = "srok"
    private const val DATA_CENA = "cena"

    /** Нажатие на напоминание: открыть главный экран и сразу спросить `/oplata`. */
    const val ACTION_OPLATA = "io.nekohasekai.sfa.OPLATA"

    private fun imyaRaboty(vid: VidNapominaniya) = "KelevraOplataNapominanie${vid.name}"

    private fun idUvedomleniya(vid: VidNapominaniya) = when (vid) {
        VidNapominaniya.Skoro -> 7301
        VidNapominaniya.Konec -> 7302
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Перепланировать по свежему ответу `/oplata`; null — оплаты нет (неплатящий, другой профиль). */
    fun perestavit(info: OplataInfo?) {
        runCatching { perestavit0(Application.application, info) }
            .onFailure { KLog.w(TAG, "напоминания не переставлены: ${it.javaClass.simpleName}") }
    }

    private fun perestavit0(ctx: Context, info: OplataInfo?) {
        val now = System.currentTimeMillis()
        val plan = napominaniya(info, now).associateBy { it.vid }
        val p = prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        for (vid in VidNapominaniya.entries) {
            val klyuch = "kogda_${vid.name}"
            val bylo = p.getLong(klyuch, 0L)
            val n = plan[vid]
            if (n == null) {
                if (bylo != 0L) {
                    wm.cancelUniqueWork(imyaRaboty(vid))
                    p.edit().remove(klyuch).apply()
                    KLog.i(TAG, "напоминание ${vid.name} снято")
                }
                continue
            }
            if (n.kogdaMs == bylo) continue
            val dannye = Data.Builder()
                .putString(DATA_VID, vid.name)
                .putLong(DATA_SROK, info?.srokMs ?: 0L)
                .putInt(DATA_CENA, info?.cena ?: 0)
                .build()
            wm.enqueueUniqueWork(
                imyaRaboty(vid),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(NapominanieTask::class.java)
                    .setInitialDelay((n.kogdaMs - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .setInputData(dannye)
                    .build(),
            )
            p.edit().putLong(klyuch, n.kogdaMs).apply()
            KLog.i(TAG, "напоминание ${vid.name} через ${(n.kogdaMs - now) / 60_000} мин")
        }
        // Заявка ждёт, срок продлён или оплаты нет — висящие напоминания больше не правда.
        val neNado = info == null || !info.platit || info.zhdet ||
            srokOplaty(info.srokMs, now) == SrokOplaty.Obychnyj
        if (neNado) ubrat(ctx)
    }

    /** Убрать показанные напоминания (нажали, продлили). */
    fun ubrat(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        VidNapominaniya.entries.forEach { nm.cancel(idUvedomleniya(it)) }
    }

    private fun pokazat(ctx: Context, vid: VidNapominaniya, srokMs: Long, cena: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) {
            KLog.w(TAG, "уведомления запрещены — напоминание ${vid.name} не показано")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.oplata_podpiska), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tekst = when (vid) {
            VidNapominaniya.Skoro -> ctx.getString(R.string.oplata_napominanie_skoro, srokDdMm(srokMs), cena)
            VidNapominaniya.Konec -> ctx.getString(R.string.oplata_napominanie_konec, cena)
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_OPLATA
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val id = idUvedomleniya(vid)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or ServiceNotification.flags)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kelevra)
            .setContentTitle(tekst)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Другое напоминание о том же сроке уже не правда: «до» сменяется «закончилась».
        VidNapominaniya.entries.filter { it != vid }.forEach { nm.cancel(idUvedomleniya(it)) }
        runCatching { nm.notify(id, n) }
            .onSuccess { KLog.i(TAG, "показано напоминание «$tekst»") }
            .onFailure { KLog.w(TAG, "напоминание не показалось: ${it.javaClass.simpleName}") }
    }

    /** Отложенная работа: время пришло — показать напоминание. */
    class NapominanieTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val vid = runCatching { VidNapominaniya.valueOf(inputData.getString(DATA_VID).orEmpty()) }
                .getOrNull() ?: return Result.success()
            val srok = inputData.getLong(DATA_SROK, 0L)
            val cena = inputData.getInt(DATA_CENA, 0)
            prefs(applicationContext).edit().remove("kogda_${vid.name}").apply()
            if (srok <= 0L) return Result.success()
            // Телефон спал дольше срока — «Подписка до» уже неправда.
            if (vid == VidNapominaniya.Skoro && System.currentTimeMillis() >= srok) return Result.success()
            pokazat(applicationContext, vid, srok, cena)
            return Result.success()
        }
    }
}
