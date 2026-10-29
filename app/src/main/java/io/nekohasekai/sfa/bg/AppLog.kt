package io.nekohasekai.sfa.bg

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Kelevra
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Своя запись журнала: приложение пишет лог само, себе, без единого разрешения.
 *
 * Зачем понадобилось. Логи телефона до этого писал внешний adb-шелл в
 * /sdcard/Download/kelevra-logs — файлы принадлежали шеллу, и на Android 11+
 * приложению для их чтения нужен «Доступ ко всем файлам», который выдаётся руками в
 * системных настройках. С одним телефоном разработчика это работало, с семьёй — нет:
 * обычные люди в системные настройки за таким разрешением не пойдут,
 * а логи нужны со ВСЕХ устройств. Поэтому запись переехала внутрь приложения.
 *
 * Куда пишем. `getExternalFilesDir(null)/logs` — собственный каталог приложения на
 * внешнем хранилище. Разрешений не требует вообще (ни на одной версии Android), при
 * удалении приложения уносится вместе с ним, и при этом виден с компьютера обычным
 * файловым менеджером по пути `/sdcard/Android/data/<пакет>/files/logs` — файл можно
 * достать с телефона без root. Если внешнего хранилища нет вовсе, откатываемся на
 * `filesDir`: там тоже всё работает, только достать сложнее.
 *
 * Что пишем. Строки [KLog] — напрямую, мимо logcat (см. там: прошивки Huawei и Honor
 * режут logcat приложения вплоть до нуля). Плюс то, что logcat приносит из процесса
 * помимо KLog: чужие библиотеки, падения AndroidRuntime, WebView — там, где logcat жив.
 * Начиная с Android 4.1 приложение читает из logcat
 * ТОЛЬКО записи собственного процесса и никакого разрешения для этого не нужно —
 * поэтому [Runtime.exec] с `logcat` отдаёт ровно то, что раньше собирал adb-шелл в
 * automode.log и events.log: `AutoMode`, `BoxService`, `NetworkMode`, `HonestProbe`,
 * `NetDns`, `SpeedProbe`, `OlcRtc*`, `KelevraLogUpload` и остальные наши теги — все они
 * пишутся обычным [android.util.Log] и попадают в буфер процесса. На API 24+ дополнительно
 * сужаем выборку по своему pid (`--pid`) — страховка на случай прошивок, где приложению
 * видно чужое.
 *
 * Сколько занимаем. Потолок жёсткий: [MAX_FILES] × [MAX_FILE_BYTES] = 14 МБ — столько же,
 * сколько занимала внешняя запись (automode.log 3×2 МБ плюс events.log 4×2 МБ). Дальше
 * старое вытесняется новым, файлы бесконечно не растут.
 *
 * Запись через adb на телефоне разработчика при этом никак не трогается: это отдельный
 * ручной канал, он продолжает жить своей жизнью в Download и приезжает вторым источником
 * (см. [LogUploadWork]).
 */
object AppLog {
    private const val TAG = "KelevraAppLog"

    /** Имя головного файла. Нарочно не пересекается с automode.log/events.log из adb. */
    const val BASE_NAME = "kelevra-app.log"

    /** Каталог внутри своей папки — чтобы не мешаться с рабочими файлами ядра. */
    private const val DIR_NAME = "logs"

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** Головной плюс шесть хвостов: 7 × 2 МБ = 14 МБ. */
    private const val MAX_FILES = 7

    /**
     * Потолок паузы перезапуска. Повод правки 28.10.2026: на Huawei чтение logcat
     * обрывалось каждые ~44 с (1147 раз за 8 дней), и фиксированная 30-секундная пауза
     * между КАЖДЫМ обрывом держала журнал слепым полминуты при каждом из них — за
     * сутки это сотни слепых промежутков. Первый обрыв поднимаем почти сразу
     * ([MIN_RESTART_PAUSE_MILLIS]), а если обрывы идут подряд быстро один за другим —
     * пауза растёт вдвое при каждом следующем, пока не достигнет этого потолка.
     */
    private const val RESTART_PAUSE_MILLIS = 30_000L

    /** С этой паузы начинается перезапуск после первого в серии обрыва. */
    private const val MIN_RESTART_PAUSE_MILLIS = 2_000L

    /**
     * Если чтение прожило дольше этого — оно было здоровым, и серию подряд идущих
     * быстрых обрывов считаем закончившейся: следующий обрыв получит паузу заново
     * с [MIN_RESTART_PAUSE_MILLIS], а не унаследует разогнанную.
     */
    private const val SBROS_PODRYAD_MILLIS = 60_000L

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Самопроверка logcat: сколько ждём, пока маркеры вернутся. Живой logcat отдаёт их
     * за доли секунды; медленный телефон на старте — за несколько.
     */
    private const val SAMOPROVERKA_ZHDAT_MS = 20_000L

    /**
     * Как часто самопроверка повторяется сама. Ещё она повторяется после каждого
     * перезапуска чтения logcat: на Huawei logcat деградировал постепенно, и признак,
     * снятый один раз на старте, в паспорте врал бы.
     */
    private const val SAMOPROVERKA_KAZHDYE_MS = 60 * 60_000L

    /** По этим словам маркер самопроверки узнаётся в потоке logcat. */
    private const val MARKER = "самопроверка logcat"

    /**
     * Тег маркеров. Пишутся они только в logcat (не через [KLog]: им надо именно
     * вернуться из logcat), а в файл не кладутся — итог самопроверки пишется заметкой.
     */
    private const val TAG_MARKER = "KelevraLogcatCheck"

    /** Уровни, которые проверяет самопроверка. */
    internal val PROVERYAEMYE = listOf('D', 'I', 'W', 'E')

    /** Сколько строк [KLog] держим, пока файл журнала ещё не открыт. Старше — вытесняются. */
    private const val DO_STARTA_MAX = 2000

    @Volatile
    private var rotator: LogRotator? = null

    /**
     * Строки [KLog], пришедшие до открытия файла: самый старт процесса или телефон ещё не
     * разблокирован (хранилище заперто). Дописываются, как только файл откроется.
     */
    private val doStarta = ArrayDeque<String>()
    private var doStartaVytesneno = 0

    /** Метка текущего круга самопроверки; null — ещё не запускалась. */
    @Volatile
    private var markerId: String? = null

    /** Какие уровни маркеров текущего круга уже вернулись из logcat. */
    private val uvideli = java.util.Collections.synchronizedSet(HashSet<Char>())

    /** Круг самопроверки закончил ждать: опоздавший маркер теперь лишь поправляет признак. */
    @Volatile
    private var krugResheno = false

    /** Итог последней самопроверки для паспорта: «?» — ещё не было ни одной. */
    @Volatile
    private var logcatPriznak = "?"

    /** Толчок повторить самопроверку раньше срока (чтение logcat поднято заново). */
    private val pereproverit = LinkedBlockingQueue<Unit>(1)

    @Volatile
    private var proverkaPotok: Thread? = null

    /**
     * Признак для паспорта устройства: «ok» — logcat доносит все уровни, «rezhet_DIW» —
     * какие уровни не доходят, «net» — не доходит ничего, «?» — проверки ещё не было.
     * На запись журнала не влияет: [KLog] пишет в файл сам всегда.
     */
    fun priznakLogcat(): String = logcatPriznak

    internal fun opisanie(uvideliUrovni: Set<Char>): String {
        val cut = PROVERYAEMYE.filter { it !in uvideliUrovni }
        return when {
            cut.isEmpty() -> "ok"
            cut.size == PROVERYAEMYE.size -> "net"
            else -> "rezhet_" + cut.sorted().joinToString("")
        }
    }

    /**
     * Пояс телефона одной строкой: «Europe/Moscow, UTC+03:00».
     *
     * Метка времени в строках журнала — местная и без пояса (формат logcat `threadtime`,
     * его разбирает сервер, и менять его нельзя). 20.10.2026 у телефонов на московском
     * времени разбор сдвигал все записи на два часа: сервер считал их екатеринбургскими.
     * Поэтому пояс пишется шапкой в начало каждого файла журнала и в паспорт устройства.
     */
    fun poyas(now: Long = System.currentTimeMillis()): String {
        val tz = TimeZone.getDefault()
        return "${tz.id}, UTC${smeshchenie(tz.getOffset(now))}"
    }

    /** Смещение пояса в виде «+03:00» / «-04:30». */
    internal fun smeshchenie(offsetMillis: Int): String {
        val sign = if (offsetMillis < 0) "-" else "+"
        val minutes = Math.abs(offsetMillis) / 60_000
        return sign + "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)
    }

    /** Шапка каждого нового файла журнала: по ней разбор узнаёт пояс меток. */
    internal fun shapkaFayla(): String =
        "${synchronized(stampFormat) { stampFormat.format(Date()) }} === пояс ${poyas()} ===\n"

    @Volatile
    private var pump: Thread? = null

    /** Куда пишем. Отдельным методом, потому что то же место читает отправка логов. */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /**
     * Поднимает запись. Зовётся из [io.nekohasekai.sfa.Application] на старте процесса,
     * а также при запуске сервиса; повторный вызов при живой записи ничего не делает.
     *
     * Повторный вызов нужен не для красоты. Процесс может подняться ДО разблокировки
     * телефона (плитка быстрых настроек у нас directBootAware, а приложение — нет): в
     * этот момент шифрованное хранилище недоступно, каталог журнала не открывается, и
     * при «поднимаем один раз» журнал оставался мёртвым до конца жизни процесса — то
     * есть ровно после перезагрузки телефона, когда он и нужен. Теперь неудача не
     * запоминается: следующий вызов (старт сервиса, старт экрана) откроет журнал снова.
     */
    @Synchronized
    fun start(context: Context) {
        if (pump?.isAlive == true) return
        val started = runCatching {
            // Каталог создаём прямо здесь и проверяем результат: до разблокировки
            // телефона хранилище заперто, и молча получить журнал «в никуда» нельзя.
            val folder = dir(context)
            if (!folder.isDirectory && !folder.mkdirs()) error("каталог журнала не создан: $folder")
            LogRotator(folder, BASE_NAME, MAX_FILE_BYTES, MAX_FILES, header = ::shapkaFayla)
        }.onFailure { KLog.w(TAG, "не удалось открыть свой журнал, попробую позже", it) }.getOrNull() ?: return

        // Первая строка журнала называет устройство: модель, система с версией, версия
        // приложения ([Kelevra.deviceSummary]). До этого в андроидном журнале не было ни
        // того, ни другого, ни третьего — присланный на сервер файл опознавался только
        // по реестру, а сам по себе не говорил ничего. У десктопа такая шапка есть
        // изначально, и разбор там начинается с неё.
        //
        // Шапку добываем осторожно: журнал поднимается на самом старте процесса, и
        // остаться без записи из-за строки о железе было бы обменом наоборот.
        val устройство = runCatching { Kelevra.deviceSummary() }.getOrDefault("устройство не опознано")
        // Сколько телефон на ногах: по этой цифре перезагрузка телефона отличается от
        // перезапуска одного лишь приложения, а без такой отметки два разных случая
        // выглядят в журнале одинаково.
        note(
            started,
            "=== запись журнала начата, $устройство, pid ${Process.myPid()}, " +
                "телефон включён ${SystemClock.elapsedRealtime() / 60_000} мин назад, " +
                "пояс ${runCatching { poyas() }.getOrDefault("?")} ===",
        )
        // Шапка сессии первой, за ней — строки KLog, накопленные до открытия файла.
        podklyuchit(started)
        runCatching { Kelevra.pasportStroka() }.getOrNull()?.let { note("=== паспорт: $it ===") }
        pump = Thread({ pumpLoop(started) }, "kelevra-app-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        if (proverkaPotok == null) {
            proverkaPotok = Thread({ samoproverkaLoop() }, "kelevra-logcat-check").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Подключить файл к прямой записи и дописать в него накопленное до открытия. */
    internal fun podklyuchit(target: LogRotator) {
        val (lines, vytesneno) = synchronized(doStarta) {
            // Порядок под замком: строка, пришедшая в [pryamo] после снятия копии, уже
            // увидит файл и ляжет следом, а не застрянет в очереди.
            rotator = target
            val all = doStarta.toList()
            doStarta.clear()
            val lost = doStartaVytesneno
            doStartaVytesneno = 0
            all to lost
        }
        if (vytesneno > 0) note("=== до открытия журнала вытеснено строк: $vytesneno ===")
        if (lines.isNotEmpty()) note("=== строки, записанные до открытия журнала: ${lines.size} ===")
        lines.forEach { runCatching { target.append(it) } }
    }

    /** Для тестов: отключить файл, забыть накопленное. */
    internal fun otklyuchit() {
        synchronized(doStarta) {
            rotator = null
            doStarta.clear()
            doStartaVytesneno = 0
        }
    }

    /**
     * Доносит ли logcat наши строки — по уровням. Только диагностика для паспорта: на
     * запись журнала итог не влияет ([KLog] пишет в файл сам всегда).
     *
     * Повод: 20.10.2026 прошивка Honor выбрасывала из logcat всё ниже E, а Huawei не
     * доносил ничего. Пишем по маркеру каждого уровня и ждём, какие вернутся через
     * [drain]. Круг повторяется раз в [SAMOPROVERKA_KAZHDYE_MS] и после каждого
     * перезапуска чтения; заметка в файл — только когда итог поменялся.
     */
    private fun samoproverkaLoop() {
        var prezhniy: String? = null
        while (true) {
            val itog = runCatching { krugSamoproverki() }.getOrElse { "?" }
            if (itog != prezhniy) {
                note("=== самопроверка logcat: ${opisanieDlyaFayla(itog)} (журнал пишется напрямую, от logcat не зависит) ===")
                prezhniy = itog
            }
            val ok = runCatching { pereproverit.poll(SAMOPROVERKA_KAZHDYE_MS, TimeUnit.MILLISECONDS) }
            if (ok.isFailure) return
        }
    }

    private fun krugSamoproverki(): String {
        val id = java.lang.Long.toHexString(System.nanoTime())
        synchronized(uvideli) {
            krugResheno = false
            uvideli.clear()
            markerId = id
        }
        Log.d(TAG_MARKER, "$MARKER $id D")
        Log.i(TAG_MARKER, "$MARKER $id I")
        Log.w(TAG_MARKER, "$MARKER $id W")
        Log.e(TAG_MARKER, "$MARKER $id E")
        val until = SystemClock.elapsedRealtime() + SAMOPROVERKA_ZHDAT_MS
        while (SystemClock.elapsedRealtime() < until && uvideli.size < PROVERYAEMYE.size) {
            Thread.sleep(250)
        }
        val itog = synchronized(uvideli) {
            krugResheno = true
            opisanie(HashSet(uvideli))
        }
        logcatPriznak = itog
        return itog
    }

    private fun opisanieDlyaFayla(itog: String): String = when {
        itog == "ok" -> "все уровни доходят"
        itog == "net" -> "не доходит ничего"
        itog.startsWith("rezhet_") -> "режутся ${itog.removePrefix("rezhet_")}"
        else -> "сорвалась"
    }

    /** Строка маркера самопроверки из logcat: запомнить уровень. */
    private fun markerVidit(line: String) {
        val id = markerId ?: return
        val at = line.indexOf("$MARKER $id ")
        if (at < 0) return
        val level = line.getOrNull(at + MARKER.length + id.length + 2) ?: return
        // Опоздавший маркер (круг уже решён) лишь поправляет признак паспорта.
        synchronized(uvideli) {
            if (uvideli.add(level) && krugResheno) logcatPriznak = opisanie(HashSet(uvideli))
        }
    }

    /**
     * Строка, готовая в формате `threadtime`, — прямо в файл, мимо logcat. Зовёт [KLog]
     * на каждую свою строку. Файл ещё не открыт — строка ждёт в [doStarta].
     */
    fun pryamo(text: String) {
        val target = rotator ?: synchronized(doStarta) {
            // Файл могли подключить, пока шли сюда: тогда пишем в него, иначе ждём.
            rotator ?: run {
                if (doStarta.size >= DO_STARTA_MAX) {
                    doStarta.removeFirst()
                    doStartaVytesneno++
                }
                doStarta.addLast(text)
                null
            }
        } ?: return
        runCatching { target.append(text) }
    }

    /** Дожать файл до носителя (падение процесса: дальше — только смерть). */
    fun sbrosit() {
        runCatching { rotator?.sync() }
    }

    /**
     * Записать строку в свой файл напрямую, минуя logcat.
     *
     * Нужно для того, чего в logcat заведомо не будет: отметок о самой записи. Если
     * logcat почему-то не читается (экзотическая прошивка, вырезанный бинарь), в файле
     * останется хотя бы честная запись об этом, а не пустота, которую не отличить от
     * «ничего не происходило».
     */
    fun note(line: String) {
        note(rotator ?: return, line)
    }

    private fun note(target: LogRotator, line: String) {
        runCatching { target.append("${synchronized(stampFormat) { stampFormat.format(Date()) }} $line\n") }
    }

    /**
     * Качает logcat в файл, пока процесс жив. Если поток оборвался (система уронила
     * logcat, буфер пересоздали) — ждём и поднимаем заново: молчащий журнал хуже,
     * чем журнал с дыркой.
     */
    private fun pumpLoop(target: LogRotator) {
        // Одна отметка на всю жизнь записи: перезапуск logcat продолжает с неё, а не с
        // начала буфера.
        val metka = LogcatMetka()
        // Сколько подряд быстрых (короче SBROS_PODRYAD_MILLIS) обрывов уже было —
        // пауза растёт с каждым, пока серию не прерывает здоровое долгое чтение.
        var podryad = 0
        while (true) {
            val itog = drain(target, metka)
            if (sbrositPodryad(itog.zhilMs)) podryad = 0
            val pauza = pauzaPosle(podryad)
            note(
                "=== чтение logcat прервалось" +
                    (itog.oshibka?.let { ": ${it.javaClass.simpleName}" } ?: "") +
                    ", код ${itog.kodVyhoda ?: "?"}, прожил ${itog.zhilMs / 1000} с, следующий через ${pauza / 1000} с ===",
            )
            runCatching { Thread.sleep(pauza) }.onFailure { return }
            podryad++
            // Logcat сорвался — проверить заново, что он доносит: паспорт не должен врать.
            // Маркеры, записанные чуть раньше запуска чтения, оно подберёт по отметке -T.
            pereproverit.offer(Unit)
        }
    }

    /** Итог одного прохода [drain]: чем кончился процесс logcat и сколько прожил. */
    internal data class DrainItog(val oshibka: Throwable?, val kodVyhoda: Int?, val zhilMs: Long)

    /**
     * Чтение прожило дольше [SBROS_PODRYAD_MILLIS] — значит было здоровым, и серию
     * подряд идущих БЫСТРЫХ обрывов пора считать закончившейся.
     */
    internal fun sbrositPodryad(zhilMs: Long): Boolean = zhilMs >= SBROS_PODRYAD_MILLIS

    /**
     * Пауза перед следующим перезапуском после [podryad] уже случившихся подряд
     * быстрых обрывов: [MIN_RESTART_PAUSE_MILLIS], дальше рост вдвое на каждый
     * следующий, не выше [RESTART_PAUSE_MILLIS].
     */
    internal fun pauzaPosle(podryad: Int): Long {
        var pauza = MIN_RESTART_PAUSE_MILLIS
        repeat(podryad.coerceAtLeast(0)) {
            if (pauza >= RESTART_PAUSE_MILLIS) return RESTART_PAUSE_MILLIS
            pauza *= 2
        }
        return pauza.coerceAtMost(RESTART_PAUSE_MILLIS)
    }

    /**
     * Чужие теги, которые система пишет внутри нашего процесса и которые в разборе
     * бесполезны ни разу.
     *
     * Замер 10.09.2026 по суточному журналу телефона: из 19 520 строк 11 853 — это
     * `NotificationManager` про перерисовку нашего же значка в шторке, ещё 3 219 —
     * `ViewRootImpl` про включение и выключение экрана. Полезного нашего — около
     * десятой части. Поэтому журнал приложения занимал все свои 14 МБ и вытеснял
     * историю, а искать в нём приходилось глазами.
     *
     * Список чёрный, а не белый: своих тегов много, они появляются с каждой новой
     * частью клиента, и забытый белый список тихо съел бы именно то, ради чего журнал
     * и возят. `WM-WorkerWrapper` намеренно НЕ здесь: по нему видно, чем кончилась
     * фоновая задача (SUCCESS / RETRY), и именно так 10.09 выяснилось, что профиль в
     * итоге всё-таки забирается повторами.
     */
    private val ШУМНЫЕ_ТЕГИ = setOf(
        "NotificationManager", "ViewRootImpl", "InsetsSource", "HWUI", "OpenGLRenderer",
        "VRI", "GraphicsEnvironment", "HandWritingStubImpl", "MiuiPreloadClassImpl",
        "FinalizerDaemon", "WindowOnBackDispatcher", "JobService", "JobInfo",
        "ActivityThread", "PrivilegeSettingsClient", "ConnectivityBinderUtils",
        "WM-GreedyScheduler", "WM-SystemJobScheduler", "WM-WorkConstraintsTrack",
        "WM-Processor", "System.err", "OpenGLRenderer", "libEGL", "AdrenoGLES",
        "Choreographer", "SurfaceView", "BLASTBufferQueue", "Parcel",
    )

    /**
     * Шумная ли это строка. Формат logcat `threadtime`:
     * `MM-DD HH:MM:SS.mmm  PID  TID L TAG: сообщение`.
     *
     * Строку, которую не разобрать (нет двоеточия, свой формат, продолжение стека),
     * оставляем: потерять непонятное хуже, чем возить лишнее.
     */
    /** Тег из шумного списка: [KLog] такие строки в файл напрямую тоже не пишет. */
    internal fun shumnyTeg(tag: String): Boolean = tag in ШУМНЫЕ_ТЕГИ

    /** Начало строки `threadtime`: pid и тег (logcat добивает тег пробелами до 8 знаков). */
    private val RX_GOLOVA = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFA]\s+(.*?)\s*:(?:\s|$)""")

    /**
     * Брать ли строку logcat в файл. Нет — если это шум ([shumnaya]), маркер самопроверки
     * или строка своего процесса ([myPid]) с тегом [KLog] ([tegiKLog]): такую KLog уже
     * записал напрямую, из logcat она была бы дублем. Строку, которую не разобрать,
     * оставляем — потерять непонятное хуже, чем возить лишнее.
     */
    internal fun vFayl(line: String, myPid: Int, tegiKLog: Set<String>): Boolean {
        if (line.contains(MARKER)) return false
        if (shumnaya(line)) return false
        val m = RX_GOLOVA.find(line) ?: return true
        val pid = m.groupValues[1].toIntOrNull() ?: return true
        if (pid != myPid) return true
        return m.groupValues[2].trim() !in tegiKLog
    }

    internal fun shumnaya(line: String): Boolean {
        val dvoetochie = line.indexOf(':', startIndex = 20)
        if (dvoetochie <= 0) return false
        val do_dvoetochiya = line.substring(0, dvoetochie)
        val probel = do_dvoetochiya.lastIndexOf(' ')
        if (probel <= 0) return false
        val teg = do_dvoetochiya.substring(probel + 1).trim()
        if (teg.isEmpty()) return false
        // Строки продолжения стектрейса («\tat io.nekohasekai…») тегом не помечены,
        // но и смысла не несут: своё исключение уже названо строкой выше.
        if (line.contains("\tat ")) return true
        return teg in ШУМНЫЕ_ТЕГИ
    }

    /**
     * Качает одну жизнь процесса logcat в файл. Возвращает итог ([DrainItog]) вместо
     * того, чтобы бросать исключение наружу молча: [pumpLoop] по нему решает, сколько
     * ждать до перезапуска, и пишет код выхода процесса в строку обрыва.
     *
     * Код выхода снимается ПОСЛЕ конца чтения, но ДО [Process.destroy] ниже — иначе
     * вместо настоящей причины обрыва увидели бы код нашего же принудительного убийства
     * (SIGKILL, 137). Если за короткий срок после конца потока процесс сам не
     * завершился (т.е. оборвался именно поток, а не процесс), код — null.
     */
    private fun drain(target: LogRotator, metka: LogcatMetka): DrainItog {
        val myPid = Process.myPid()
        val pid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) myPid else null
        val nachalos = SystemClock.elapsedRealtime()
        val process = Runtime.getRuntime().exec(metka.komanda(pid).toTypedArray())
        val oshibka = runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (metka.uzhePisali(line)) continue
                    if (line.contains(MARKER)) markerVidit(line)
                    if (!vFayl(line, myPid, KLog.tegi)) continue
                    target.append(line + "\n")
                }
            }
        }.exceptionOrNull()
        val kodVyhoda = runCatching {
            if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue() else null
        }.getOrNull()
        runCatching { process.destroy() }
        return DrainItog(oshibka, kodVyhoda, SystemClock.elapsedRealtime() - nachalos)
    }
}

/**
 * Докуда logcat уже прочитан — чтобы перезапуск чтения не переписывал буфер заново.
 *
 * Без отметки каждый обрыв logcat (система уронила процесс, буфер пересоздали) давал
 * новый запуск без `-T`, и он отдавал весь накопленный буфер процесса с начала: те же
 * строки второй раз ложились в журнал и уезжали в архив дублями.
 *
 * Как устроено. Запоминается время последней прочитанной строки (формат `threadtime`,
 * `MM-DD HH:MM:SS.mmm`) и сколько строк с этим временем уже отдано (позиция, а не
 * содержимое: две ОДИНАКОВЫЕ строки в одну миллисекунду без перезапуска — законное
 * совпадение, а не повтор буфера, и терять вторую нельзя). Перезапуск идёт с
 * `-T <время>`: logcat отдаёт строки начиная с этой отметки ВКЛЮЧИТЕЛЬНО в ТОМ ЖЕ
 * порядке, поэтому первые [porogNaVremeni] строк этой миллисекунды — те же самые,
 * что уже записаны, и отсекаются по счётчику ([poziciyaVremeni]); всё, что идёт следом
 * (новые строки той же миллисекунды, добавленные логом ПОСЛЕ обрыва), считается заново.
 * Строки без времени (служебные «beginning of main») не двигают отметку и не отсекаются.
 *
 * Первый запуск идёт без `-T` намеренно: буфер процесса до старта записи — это начало
 * его же жизни, и оно нужно журналу.
 */
internal class LogcatMetka {
    private var vremya: String? = null

    /** Сколько строк текущей метки времени уже отдано в этом проходе чтения. */
    private var poziciyaVremeni = 0

    /** Сколько строк этой метки были отданы ДО перезапуска — порог отсечки повтора. */
    private var porogNaVremeni = 0

    /** Команда очередного запуска logcat. [pid] — сужение по своему процессу (API 24+). */
    fun komanda(pid: Int?): List<String> {
        val command = mutableListOf("logcat", "-v", "threadtime")
        vremya?.let {
            command += "-T"
            command += it
            // Это перезапуск: следующий проход отдаст миллисекунду [vremya] заново с
            // начала. Запоминаем, сколько строк на ней уже отдано, — ровно столько же
            // надо будет отсечь снова, остальное (пришедшее уже ПОСЛЕ обрыва) — новое.
            porogNaVremeni = poziciyaVremeni
            poziciyaVremeni = 0
        }
        if (pid != null) {
            command += "--pid"
            command += pid.toString()
        }
        return command
    }

    /**
     * Прочитанная строка. true — эта позиция внутри своей миллисекунды уже была
     * записана до перезапуска, писать не надо. Считаем ПОЗИЦИЮ строки внутри метки
     * времени, а не её содержимое: две ОДИНАКОВЫЕ строки, законно пришедшие дважды в
     * одну и ту же миллисекунду БЕЗ перезапуска, — это два разных события, и вторую
     * терять нельзя.
     */
    fun uzhePisali(line: String): Boolean {
        val stamp = vremyaStroki(line) ?: return false
        if (stamp != vremya) {
            vremya = stamp
            poziciyaVremeni = 0
            porogNaVremeni = 0
        }
        poziciyaVremeni += 1
        return poziciyaVremeni <= porogNaVremeni
    }

    private fun vremyaStroki(line: String): String? {
        if (line.length < STAMP_LENGTH) return null
        val stamp = line.substring(0, STAMP_LENGTH)
        return stamp.takeIf { RX_STAMP.matches(it) }
    }

    private companion object {
        const val STAMP_LENGTH = 18
        val RX_STAMP = Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d""")
    }
}

/**
 * Журнал с ротацией по размеру: `имя`, `имя.1` … `имя.N`.
 *
 * Head держим открытым и сбрасываем на диск после каждой записи: журнал нужен ровно
 * тогда, когда приложение упало, — недописанный буфер в этот момент бесполезен.
 * Потолок жёсткий: длиннее [maxFileBytes] не бывает даже одна строка, она обрезается.
 */
internal class LogRotator(
    private val dir: File,
    private val baseName: String,
    private val maxFileBytes: Long,
    private val maxFiles: Int,
    /** Шапка в начало каждого нового файла (пояс меток времени); null — без шапки. */
    private val header: (() -> String)? = null,
) {
    private var out: FileOutputStream? = null
    private var headBytes = 0L

    val head: File get() = File(dir, baseName)

    /** Все файлы журнала, свежий первым. */
    fun files(): List<File> = files(dir, baseName)

    /** Сколько журнал занимает на диске сейчас. Никогда не больше maxFiles × maxFileBytes. */
    fun totalBytes(): Long = files().sumOf { it.length() }

    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        var bytes = text.toByteArray()
        // Строка длиннее целого файла сделала бы потолок необязательным — режем.
        if (bytes.size > maxFileBytes) bytes = bytes.copyOf(maxFileBytes.toInt())
        // Открываемся ДО проверки потолка: после перезапуска приложения head уже может
        // быть почти полным, и без этого первая же строка новой сессии его переполняла.
        if (out == null) open()
        if (headBytes > 0 && headBytes + bytes.size > maxFileBytes) {
            rotate()
            open()
        }
        val stream = out ?: return
        val makeHeader = header
        if (headBytes == 0L && makeHeader != null) {
            val head = runCatching { makeHeader().toByteArray() }.getOrNull()
            // Шапка не должна пробивать потолок файла: не влезает вместе со строкой — без неё.
            if (head != null && head.isNotEmpty() && head.size + bytes.size <= maxFileBytes) {
                stream.write(head)
                headBytes += head.size
            }
        }
        stream.write(bytes)
        stream.flush()
        headBytes += bytes.size
    }

    /** Дожать записанное до носителя: append уже отдал всё ядру, это — против выключения. */
    @Synchronized
    fun sync() {
        runCatching { out?.fd?.sync() }
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private fun open() {
        dir.mkdirs()
        headBytes = head.length()
        out = FileOutputStream(head, true)
    }

    /**
     * Сдвигает хвосты и освобождает head. Самый старый файл уходит совсем — в этом и
     * состоит потолок: место занимают только последние maxFiles кусков.
     */
    private fun rotate() {
        close()
        File(dir, "$baseName.${maxFiles - 1}").delete()
        for (index in maxFiles - 2 downTo 1) {
            val from = File(dir, "$baseName.$index")
            if (from.exists()) from.renameTo(File(dir, "$baseName.${index + 1}"))
        }
        head.renameTo(File(dir, "$baseName.1"))
        headBytes = 0
    }

    companion object {
        /** Файлы журнала в каталоге, свежий первым. Читается и снаружи — отправкой логов. */
        fun files(dir: File, baseName: String): List<File> {
            if (!dir.isDirectory) return emptyList()
            val head = File(dir, baseName)
            val tails = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("$baseName.") }
                ?.sortedBy { it.name }
                .orEmpty()
            return (listOf(head) + tails).filter { it.isFile && it.length() > 0 }
        }
    }
}
