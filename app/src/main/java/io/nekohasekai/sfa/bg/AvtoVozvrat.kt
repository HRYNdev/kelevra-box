package io.nekohasekai.sfa.bg

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Возврат службы, которую человек включил, но которой сейчас нет: после обновления
 * приложения, после перезагрузки телефона, после того как система убила процесс.
 *
 * Поднимаем сами и ждём подтверждения от самой службы ([BoxService.komandaPrishlaAt]).
 * Не подтвердилась — в журнал причина целиком (исключение со стеком или «команда ушла
 * без ответа» с обстановкой процесса), а человеку уведомление «нажмите, чтобы включить»:
 * тап — это действие человека, из него Android запускать службу разрешает всегда.
 *
 * Разбор 21.10.2026: телефон Android 14 после самообновления писал «служба поднимается»,
 * и больше ничего — ни команды на запуск, ни исключения, ни падения. Тот же путь молча
 * выходил, если на телефоне лежал хоть один непрочитанный отчёт о падении (защита от
 * круга падений из апстрима), и при этом ещё и стирал отметку «служба включена».
 * Четыре обновления подряд на этом телефоне кончились выключенной службой.
 */
object AvtoVozvrat {
    private const val TAG = "KelevraVozvrat"

    /** Тап по уведомлению открывает приложение с этим действием — оно включает службу. */
    const val ACTION_VKLYUCHIT = "io.nekohasekai.sfa.VKLYUCHIT_SLUZHBU"

    private const val CHANNEL_ID = "sluzhba_ne_vklyuchilas"
    private const val NOTIFICATION_ID = 0x4B56

    /** Сколько ждём, пока служба подтвердит запуск. Обычно десятки миллисекунд. */
    const val ZHDAT_MS = 15_000L

    /** Попыток на один заход: вторая — на случай, если первую съело замораживание процесса. */
    const val POPYTOK = 2

    /** Пауза между попытками. */
    private const val PAUZA_MS = 2_000L

    /**
     * Служба снова исчезла быстрее этого после нашего же возврата — дальше не поднимаем,
     * а зовём человека: иначе процесс, который падает на старте, ходил бы по кругу.
     */
    const val OKNO_KRUGA_MS = 10L * 60 * 1000

    /** Смахнутое уведомление показываем снова не раньше, чем через столько. */
    const val POVTOR_UVEDOMLENIYA_MS = 6L * 60 * 60 * 1000

    /** Через столько после подъёма процесса проверяем, вернулась ли служба. */
    private const val PROVERKA_PROCESSA_MS = 20_000L

    private const val PREFS = "kelevra_vozvrat"
    private const val KEY_POKAZ = "pokaz_at"
    private const val KEY_POPYTKA = "popytka_at"

    enum class Povod(val slovo: String) {
        OBNOVLENIE("после обновления"),
        ZAGRUZKA("после перезагрузки"),
        UBITA("после остановки системой"),
        OTOZVANO("после отзыва разрешения на VPN"),
    }

    enum class Shag { NICHEGO, PODNYAT, UVEDOMIT }

    /**
     * Всё, из чего складывается решение. Сами факты собирает Android-часть, решение —
     * чистая функция [reshit] (проверяется тестами).
     *
     * @param hotel человек включал службу и не выключал (`Settings.startedByUser`).
     * @param zhiva служба в этом процессе запущена.
     * @param vpnRazreshenie разрешение VPN на месте (или режим без VPN).
     * @param popytok сколько попыток уже сделано в этом заходе.
     * @param nedavnoPodnimali прошлый наш возврат был меньше [OKNO_KRUGA_MS] назад.
     */
    data class Fakty(
        val povod: Povod,
        val hotel: Boolean,
        val zhiva: Boolean,
        val vpnRazreshenie: Boolean,
        val popytok: Int,
        val maxPopytok: Int = POPYTOK,
        val nedavnoPodnimali: Boolean = false,
    )

    fun reshit(f: Fakty): Shag = when {
        !f.hotel -> Shag.NICHEGO
        f.zhiva -> Shag.NICHEGO
        // Без разрешения VPN служба поднимется и тут же встанет аварийно — сразу к человеку:
        // разрешение даётся только из окна приложения.
        !f.vpnRazreshenie -> Shag.UVEDOMIT
        // Круг защищаем только для «убита системой»: обновление и перезагрузка — новые
        // события, после них поднимаем всегда.
        f.povod == Povod.UBITA && f.nedavnoPodnimali && f.popytok == 0 -> Shag.UVEDOMIT
        f.popytok < f.maxPopytok -> Shag.PODNYAT
        else -> Shag.UVEDOMIT
    }

    /** Прошлый возврат был недавно: часы не шли назад и окно не прошло. */
    fun nedavno(seychasMs: Long, proshlayaMs: Long, oknoMs: Long = OKNO_KRUGA_MS): Boolean =
        proshlayaMs > 0 && seychasMs >= proshlayaMs && seychasMs - proshlayaMs < oknoMs

    /**
     * Показывать ли уведомление. Уже висит — не дублируем; смахнули — напоминаем не чаще
     * [POVTOR_UVEDOMLENIYA_MS]; часы ушли назад — показываем.
     */
    fun pokazyvat(seychasMs: Long, proshlyPokazMs: Long, uzheVisit: Boolean): Boolean = when {
        uzheVisit -> false
        proshlyPokazMs <= 0 -> true
        seychasMs < proshlyPokazMs -> true
        else -> seychasMs - proshlyPokazMs >= POVTOR_UVEDOMLENIYA_MS
    }

    /** Подтвердила ли служба запуск: команда дошла после того, как мы её отправили. */
    fun podtverdila(komandaPrishlaAt: Long, otpravlenoAt: Long, zhiva: Boolean): Boolean =
        zhiva || (komandaPrishlaAt > 0 && komandaPrishlaAt >= otpravlenoAt)

    /** Заголовок и текст уведомления. Без «защиты» и без разговорных слов. */
    fun tekst(povod: Povod): Pair<String, String> = when (povod) {
        Povod.OBNOVLENIE -> "Kelevra не включилась после обновления" to "Нажмите, чтобы включить"
        Povod.ZAGRUZKA -> "Kelevra не включилась после перезагрузки" to "Нажмите, чтобы включить"
        Povod.UBITA -> "Kelevra выключилась" to "Система остановила Kelevra. Нажмите, чтобы включить"
        Povod.OTOZVANO -> "Kelevra выключилась" to "Разрешение на VPN отозвано. Нажмите, чтобы включить"
    }

    /** Строка причины для журнала: что именно не дало подняться. */
    fun prichina(f: Fakty, oshibka: Throwable?): String = when {
        !f.vpnRazreshenie -> "нет разрешения VPN (VpnService.prepare вернул окно)"
        oshibka != null -> "запуск отклонён: ${oshibka.javaClass.name}: ${oshibka.message}"
        f.popytok == 0 && f.nedavnoPodnimali -> "служба снова остановилась меньше чем через ${OKNO_KRUGA_MS / 60000} мин после возврата"
        else -> "команда на запуск ушла без исключения, но служба её не получила"
    }

    private val idet = AtomicBoolean(false)

    /** В этом процессе заход уже был — проверка процесса второй раз не идёт. */
    @Volatile
    private var zakhodBylo = false

    /**
     * Поднять службу, если человек её включал, а её нет. Ждёт подтверждения до
     * [ZHDAT_MS] на попытку; не дождался — уведомление. Возвращает, жива ли служба.
     * Второй одновременный заход не начинается.
     *
     * @param maxPopytok сколько попыток разрешено в этом заходе. [Povod.OTOZVANO] зовёт
     *   с единицей: разрешение могло забрать другое VPN-приложение, и крутиться в ответ
     *   на каждый его захват — спорить с ним, а не включаться самим.
     * @param hotelBylo «человек включал» на момент события, если флаг к этому времени уже
     *   стёрт: stopService сам сбрасывает [Settings.startedByUser], и после отзыва без
     *   этого параметра возврат видел «не включали» и молчал.
     */
    suspend fun vernut(povod: Povod, zhdatMs: Long = ZHDAT_MS, maxPopytok: Int = POPYTOK, hotelBylo: Boolean? = null): Boolean {
        if (!idet.compareAndSet(false, true)) {
            KLog.i(TAG, "${povod.slovo}: возврат службы уже идёт — второй не начинаю")
            return BoxService.zhiva
        }
        zakhodBylo = true
        try {
            val ctx = Application.application
            var popytok = 0
            var oshibka: Throwable? = null
            val nedavnoPodnimali = nedavno(System.currentTimeMillis(), prefs(ctx).getLong(KEY_POPYTKA, 0L))
            while (true) {
                val f = Fakty(
                    povod = povod,
                    hotel = hotelBylo ?: Settings.startedByUser,
                    zhiva = BoxService.zhiva,
                    vpnRazreshenie = vpnRazreshenie(ctx),
                    popytok = popytok,
                    maxPopytok = maxPopytok,
                    nedavnoPodnimali = nedavnoPodnimali,
                )
                when (reshit(f)) {
                    Shag.NICHEGO -> {
                        if (popytok == 0 && !f.hotel) KLog.i(TAG, "${povod.slovo}: служба не была включена — не поднимаю")
                        return f.zhiva
                    }

                    Shag.PODNYAT -> {
                        if (popytok > 0) delay(PAUZA_MS)
                        popytok++
                        if (povod == Povod.UBITA) prefs(ctx).edit().putLong(KEY_POPYTKA, System.currentTimeMillis()).apply()
                        KLog.i(TAG, "${povod.slovo}: поднимаю службу, попытка $popytok из ${f.maxPopytok} (${obstanovka(ctx)})")
                        val otpravleno = SystemClock.elapsedRealtime()
                        oshibka = runCatching { withContext(Dispatchers.Main) { BoxService.start() } }.exceptionOrNull()
                        if (oshibka != null) {
                            // Исключение целиком, со стеком: ради этой строки всё и затевалось.
                            KLog.e(TAG, "${povod.slovo}: команда на запуск службы не прошла: ${oshibka.javaClass.name}: ${oshibka.message}", oshibka)
                            continue
                        }
                        val zhdali = dozhdatsya(otpravleno, zhdatMs)
                        if (zhdali >= 0) {
                            KLog.i(TAG, "${povod.slovo}: служба подтвердила запуск через $zhdali мс")
                            return true
                        }
                        KLog.w(TAG, "${povod.slovo}: служба не подтвердила запуск за ${zhdatMs / 1000} с — команда ушла без исключения (${obstanovka(ctx)})")
                    }

                    Shag.UVEDOMIT -> {
                        KLog.w(TAG, "${povod.slovo}: служба не вернулась — ${prichina(f, oshibka)}; показываю уведомление")
                        pokazat(ctx, povod)
                        return false
                    }
                }
            }
        } catch (e: Throwable) {
            KLog.e(TAG, "${povod.slovo}: сбой возврата службы: ${e.javaClass.name}: ${e.message}", e)
            return BoxService.zhiva
        } finally {
            idet.set(false)
        }
    }

    /** -1 — не дождались; иначе сколько мс ждали подтверждения. */
    private suspend fun dozhdatsya(otpravleno: Long, zhdatMs: Long): Long {
        while (true) {
            val proshlo = SystemClock.elapsedRealtime() - otpravleno
            if (podtverdila(BoxService.komandaPrishlaAt, otpravleno, BoxService.zhiva)) return proshlo
            if (proshlo >= zhdatMs) return -1
            delay(200)
        }
    }

    /**
     * Процесс поднялся (фоновая работа, плитка, экран) — через [PROVERKA_PROCESSA_MS]
     * проверить, на месте ли служба, которую человек включал. Пропадает она без следа,
     * когда система убивает процесс: служба не «липкая» и сама не возвращается.
     *
     * Проверка — отдельной фоновой работой, а не задержкой внутри процесса: фоновый
     * процесс после своей работы замораживается (эмулятор Android 14, 22.10.2026:
     * задержка в 20 с не кончилась и через минуту), и проверка не случилась бы никогда.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun proveritPosleStartaProcessa(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            // До первой разблокировки настроек не прочитать — тогда проверит следующий процесс.
            val unlocked = runCatching {
                (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
            }.getOrDefault(false)
            if (!unlocked) return@launch
            if (!runCatching { Settings.startedByUser }.getOrDefault(false)) return@launch
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_PROVERKA,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequest.Builder(ProverkaTask::class.java)
                        .setInitialDelay(PROVERKA_PROCESSA_MS, TimeUnit.MILLISECONDS)
                        .build(),
                )
            }.onFailure { KLog.w(TAG, "проверка службы не поставилась: ${it.message}") }
        }
    }

    private const val WORK_PROVERKA = "KelevraProverkaSluzhby"

    /** Фоновая работа: служба, которую человек включал, на месте? Нет — поднять или позвать. */
    class ProverkaTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (zakhodBylo || BoxService.zhiva) return Result.success()
            if (!runCatching { Settings.startedByUser }.getOrDefault(false)) return Result.success()
            KLog.w(TAG, "включённой человеком службы нет, хотя её не останавливали — поднимаю")
            // Не отменяемо: WorkManager на Android 14 снимал работу ровно в момент, когда
            // служба поднималась (эмулятор, 22.10.2026), и заход обрывался посреди ожидания.
            // Заход ограничен сам (две попытки по 15 с).
            withContext(NonCancellable) { vernut(Povod.UBITA) }
            return Result.success()
        }
    }

    /**
     * Служба уничтожена без нашей остановки, процесс жив. Через фоновую работу, а не
     * задержкой: процесс без службы система вправе тут же заморозить.
     */
    fun posleGibeli() {
        runCatching {
            WorkManager.getInstance(Application.application).enqueueUniqueWork(
                WORK_PROVERKA,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(ProverkaPosleGibeliTask::class.java)
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build(),
            )
        }.onFailure { KLog.w(TAG, "проверка после гибели службы не поставилась: ${it.message}") }
    }

    /** Как [ProverkaTask], но без оглядки на прошлый заход в этом процессе. */
    class ProverkaPosleGibeliTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (BoxService.zhiva) return Result.success()
            withContext(NonCancellable) { vernut(Povod.UBITA) }
            return Result.success()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun vpnRazreshenie(ctx: Context): Boolean {
        val vpn = runCatching { Settings.serviceMode == ServiceMode.VPN }.getOrDefault(true)
        if (!vpn) return true
        return runCatching { VpnService.prepare(ctx) == null }.getOrDefault(true)
    }

    /**
     * Обстановка процесса — то, от чего на Android 12+ зависит, пустят ли службу из фона:
     * важность процесса, исключение из экономии батареи, фоновые ограничения, уведомления.
     */
    fun obstanovka(ctx: Context): String = runCatching {
        val parts = mutableListOf("android ${Build.VERSION.SDK_INT}")
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        parts += "важность ${info.importance}"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        parts += "экономия батареи ${if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "снята" else "действует"}"
        parts += "экран ${if (pm.isInteractive) "включён" else "выключен"}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            parts += "фон ${if (am.isBackgroundRestricted) "ограничен" else "не ограничен"}"
        }
        parts += "уведомления ${if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) "разрешены" else "запрещены"}"
        parts += "VPN ${if (vpnRazreshenie(ctx)) "разрешён" else "без разрешения"}"
        parts.joinToString(", ")
    }.getOrElse { "обстановка не снялась: ${it.message}" }

    private fun pokazat(ctx: Context, povod: Povod) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) {
            KLog.w(TAG, "уведомления запрещены — сообщить человеку нечем")
            return
        }
        val visit = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .activeNotifications.any { it.id == NOTIFICATION_ID }
        }.getOrDefault(false)
        val p = prefs(ctx)
        val seychas = System.currentTimeMillis()
        if (!pokazyvat(seychas, p.getLong(KEY_POKAZ, 0L), visit)) {
            KLog.i(TAG, "уведомление уже ${if (visit) "висит" else "показывали недавно"} — не повторяю")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Служба не включилась", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_VKLYUCHIT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or ServiceNotification.flags)
        val (zagolovok, tekst) = tekst(povod)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kelevra)
            .setContentTitle(zagolovok)
            .setContentText(tekst)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tekst))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
            .onSuccess {
                p.edit().putLong(KEY_POKAZ, seychas).apply()
                KLog.i(TAG, "показано уведомление «$zagolovok»")
            }
            .onFailure { KLog.e(TAG, "уведомление не показалось: ${it.javaClass.name}: ${it.message}", it) }
    }

    /** Служба поднялась — уведомление «не включилась» больше не правда. */
    fun ubratUvedomlenie(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        prefs(ctx).edit().remove(KEY_POKAZ).apply()
    }
}
