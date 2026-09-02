package io.nekohasekai.sfa.bg

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Суточная отправка логов телефона разработчику.
 *
 * Зачем. Разбирать жалобы вроде «сам включается» можно было только с телефоном в руках
 * и кабелем — три реальных эпизода 28.08.2026 разобрать было уже нечем. Теперь раз в
 * сутки, в конце дня, всё новое уезжает на свой же сервер.
 *
 * Откуда берутся файлы. Основной источник — **свой журнал приложения** ([AppLog]): его
 * пишет само приложение в собственный каталог, разрешений это не требует, и поэтому
 * работает на всех телефонах семьи одинаково. Раньше основным (и единственным)
 * источником была папка /sdcard/Download/kelevra-logs, куда пишет внешний adb-шелл;
 * её файлы принадлежат шеллу, и на Android 11+ приложение читает их только с «Доступом
 * ко всем файлам», который выдаётся руками в системных настройках. Обычный человек
 * туда не пойдёт — а логи нужны со всех устройств. Теперь эта папка
 * осталась **дополнительным** источником: она приезжает, только если её реально видно
 * (телефон разработчика, где разрешение выдано), а если не видно — молча пропускается,
 * без единого требования к человеку. Сама запись через adb при этом не трогается вовсе.
 *
 * Что важно в устройстве:
 *  - Ходит ровно в конце дня (около 23:30 по местному), а не «через 24 часа после
 *    установки»: сутки должны быть уже прожиты, иначе вечерние эпизоды уедут только
 *    завтра. Начальная задержка считается до ближайшего 23:30.
 *  - Один и тот же кусок не уходит дважды. По каждому файлу помним размер, отметку
 *    времени и сколько байт уже отправлено; в следующий раз берём только хвост.
 *    Ротация переносит содержимое в файл с другим именем — такой файл узнаётся по
 *    паре (размер, время) и пропускается целиком.
 *  - Локально ничего не удаляется: ротация и своя, и внешняя уже работают сами.
 *  - Пустой архив не отправляется вовсе.
 *  - Больше 20 МБ не отправляем: части складываются от самых свежих к старым и
 *    обрезаются по достижении потолка.
 */
object LogUploadWork {
    private const val TAG = "KelevraLogUpload"
    private const val WORK_NAME = "KelevraLogUpload"

    /** Конец дня по местному времени: сутки прожиты, человек ещё не спит. */
    private const val SEND_HOUR = 23
    private const val SEND_MINUTE = 30

    /** Потолок одной отправки. Больше — режем по самым свежим логам. */
    private const val MAX_ARCHIVE_BYTES = 20 * 1024 * 1024

    /** Повтор не чаще раза в час и не дольше суток. */
    private val RETRY_MIN_MILLIS = TimeUnit.HOURS.toMillis(1)
    private val RETRY_GIVE_UP_MILLIS = TimeUnit.DAYS.toMillis(1)

    /** Свой журнал: приложение пишет его само, разрешений не требует. Основной источник. */
    private val ownLogsDir: File
        get() = AppLog.dir(Application.application)

    /**
     * Отчёты о падениях: каждое падение — своя папка с `go.log`, `jvm.log` и метаданными.
     *
     * Их пишет [CrashReportManager] (и [OOMReportManager] рядом), и до сих пор они
     * оставались лежать на телефоне: посылку собирали только из плоских каталогов, а
     * отчёты лежат на уровень глубже. Ровно поэтому «ядро упало» разбиралось только с
     * телефоном в руках — при том что отчёт уже был написан.
     */
    private val reportRoots: List<File>
        get() {
            val working = Application.application.getExternalFilesDir(null) ?: return emptyList()
            return listOf(File(working, "crash_reports"), File(working, "oom_reports"))
        }

    /**
     * Внешняя папка, куда пишет adb-шелл на телефоне разработчика. Дополнительный
     * источник: заводится не приложением, читается только если система это позволила.
     */
    private val downloadLogsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "kelevra-logs",
        )

    /**
     * Откуда собираем посылку. Свой каталог — всегда. Внешний — только если он реально
     * читается: без «Доступа ко всем файлам» на Android 11+ `listFiles` вернёт null, и
     * папка просто не попадёт в список. Ни ошибки, ни требования к человеку из этого не
     * следует — своего журнала для разбора достаточно.
     */
    internal fun sourceDirs(own: File, download: File): List<File> {
        val dirs = mutableListOf(own)
        val downloadReadable = runCatching {
            download.isDirectory && download.listFiles() != null
        }.getOrDefault(false)
        if (downloadReadable) dirs += download
        return dirs
    }

    /**
     * Что взять из отчётов о падениях.
     *
     * Берём только текст разбора и метаданные: `go.log`, `jvm.log`, `metadata.json`,
     * `configuration.json`. Дампы памяти (профили из oom_reports) не берём — они
     * мегабайтные, читаются только специальным инструментом и утопили бы посылку.
     */
    private val REPORT_FILES = setOf("go.log", "jvm.log", "metadata.json", "configuration.json")

    /** Отчёт не может быть больше этого: защита от неожиданно распухшего файла. */
    private const val MAX_REPORT_BYTES = 2L * 1024 * 1024

    /**
     * Куски из отчётов о падениях. Имя куска всегда несёт папку отчёта («20260828-231045
     * /go.log»): один и тот же `go.log` бывает у каждого падения, и без папки отметки
     * одного затирали бы другой.
     */
    internal fun collectReports(roots: List<File>, marks: Map<String, Mark>): List<Part> {
        val parts = mutableListOf<Part>()
        for (root in roots) {
            val reports = runCatching { root.listFiles() }.getOrNull()?.filter { it.isDirectory } ?: continue
            for (report in reports) {
                val files = runCatching { report.listFiles() }.getOrNull()
                    ?.filter { it.isFile && it.name in REPORT_FILES && it.length() in 1..MAX_REPORT_BYTES }
                    ?: continue
                for (file in files) {
                    val label = "${report.name}/${file.name}"
                    val size = file.length()
                    val sent = marks[label]?.sent ?: 0L
                    // Отчёт пишется один раз и больше не меняется: отправили — забыли.
                    if (sent >= size) continue
                    parts += Part(file, label, sent, size - sent, file.lastModified())
                }
            }
        }
        return parts
    }

    /** Пишется ли свой журнал. Показывается человеку в настройках. */
    fun logsAvailable(): Boolean = runCatching {
        val dir = ownLogsDir
        dir.isDirectory && (dir.listFiles()?.isNotEmpty() ?: false)
    }.getOrDefault(false)

    /**
     * Ставит суточную задачу. Зовётся при каждом запуске приложения: WorkManager сам
     * сведёт повторные постановки в одну.
     *
     * Выключателя у отправки нет по решению от 31.08.2026: пункт отключения в настройках
     * только сбивал бы с толку. Журнал уходит всегда: сеть своя, семейная, и без него
     * жалобы вроде «само включается» не разбираются вовсе.
     */
    fun schedule() {
        runCatching { schedule0() }.onFailure { Log.w(TAG, "не удалось поставить отправку логов", it) }
    }

    private fun schedule0() {
        val manager = WorkManager.getInstance(Application.application)
        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(UploadTask::class.java, 1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInitialDelay(millisUntilSendTime(), TimeUnit.MILLISECONDS)
                // Первая повторная попытка через час; дальше WorkManager сам разводит
                // их шире. Сутки ограничиваем отдельно, в самой задаче.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MIN_MILLIS, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** Сколько ждать до ближайшего 23:30 по местному времени. */
    internal fun millisUntilSendTime(now: Long = System.currentTimeMillis()): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, SEND_HOUR)
            set(Calendar.MINUTE, SEND_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }

    /**
     * Что уже отправлено, по каждому файлу: сколько байт ушло и с какими размером и
     * отметкой времени файл был на тот момент.
     */
    internal data class Mark(val size: Long, val modified: Long, val sent: Long)

    private fun loadMarks(): MutableMap<String, Mark> {
        val raw = Settings.logUploadMarks
        if (raw.isBlank()) return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val marks = mutableMapOf<String, Mark>()
            json.keys().forEach { name ->
                val item = json.optJSONObject(name) ?: return@forEach
                marks[name] = Mark(
                    size = item.optLong("size"),
                    modified = item.optLong("modified"),
                    sent = item.optLong("sent"),
                )
            }
            marks
        }.getOrDefault(mutableMapOf())
    }

    private fun saveMarks(marks: Map<String, Mark>) {
        val json = JSONObject()
        marks.forEach { (name, mark) ->
            json.put(
                name,
                JSONObject().apply {
                    put("size", mark.size)
                    put("modified", mark.modified)
                    put("sent", mark.sent)
                },
            )
        }
        Settings.logUploadMarks = json.toString()
    }

    /**
     * Кусок одного файла, который надо отправить.
     *
     * [label] — имя куска в посылке и ключ, по которому помнится отправленное. Обычно
     * это просто имя файла; своё (kelevra-app.log) и внешнее (automode.log, events.log)
     * не пересекаются по построению, но если два источника всё же принесут одинаковое
     * имя — второму добавится имя его каталога, иначе отметки одного затирали бы другой.
     */
    internal data class Part(
        val file: File,
        val label: String,
        val offset: Long,
        val length: Long,
        val modified: Long,
    )

    /**
     * Что нового появилось с прошлой удачной отправки, по всем источникам сразу.
     *
     * Файл пропускается целиком, если пара (размер, отметка времени) уже встречалась
     * под любым именем: ровно так выглядит ротация, когда содержимое automode.log
     * переезжает в automode.log.1 — переименование отметку времени не меняет.
     */
    internal fun collectParts(dirs: List<File>, marks: Map<String, Mark>): List<Part> {
        val alreadySent = marks.values.map { it.size to it.modified }.toSet()
        val parts = mutableListOf<Part>()
        val usedLabels = mutableSetOf<String>()
        for (dir in dirs) {
            val files = runCatching { dir.listFiles() }.getOrNull()
                ?.filter { it.isFile && it.length() > 0 }
                ?: continue
            for (file in files) {
                val label = if (usedLabels.add(file.name)) {
                    file.name
                } else {
                    "${dir.name}/${file.name}".also { usedLabels += it }
                }
                val size = file.length()
                val modified = file.lastModified()
                if ((size to modified) in alreadySent) continue
                val mark = marks[label]
                // Файл усох или сменился целиком — прошлое смещение к нему уже не относится.
                val offset = if (mark != null && size >= mark.sent && modified >= mark.modified) mark.sent else 0L
                if (size - offset <= 0) continue
                parts += Part(file, label, offset, size - offset, modified)
            }
        }
        // Самые свежие первыми: если упрёмся в потолок, обрежется старое, а не новое.
        return parts.sortedByDescending { it.modified }
    }

    /**
     * Складывает части в один gzip-поток. Внутри — обычный текст с разделителями,
     * чтобы на сервере хватило `gzip -d`: тар писать нечем, а zip это уже не gzip.
     */
    internal fun buildArchive(parts: List<Part>): Pair<ByteArray, List<Part>> {
        val raw = ByteArrayOutputStream()
        val taken = mutableListOf<Part>()
        // syncFlush=true: без него flush() не выталкивает сжатые байты, и потолок
        // считался бы по недосчитанному размеру.
        GZIPOutputStream(raw, true).use { gzip ->
            for (part in parts) {
                val head = "===== ${part.label} offset=${part.offset} bytes=${part.length} =====\n"
                gzip.write(head.toByteArray())
                RandomAccessFile(part.file, "r").use { source ->
                    source.seek(part.offset)
                    val buffer = ByteArray(64 * 1024)
                    var left = part.length
                    while (left > 0) {
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (read <= 0) break
                        gzip.write(buffer, 0, read)
                        left -= read
                    }
                }
                gzip.write("\n".toByteArray())
                gzip.flush()
                taken += part
                // Потолок считаем по уже сжатому: ровно столько уйдёт в сеть.
                if (raw.size() >= MAX_ARCHIVE_BYTES) {
                    Log.i(TAG, "архив упёрся в потолок 20 МБ, старые логи в эту отправку не попали")
                    break
                }
            }
        }
        return raw.toByteArray() to taken
    }

    /** Отправка. Успех — только когда сервер ответил {"ok":true}. */
    private fun upload(archive: ByteArray): Boolean {
        val conn = URL("https://${Kelevra.SUBSCRIPTION_HOST}/logs").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(archive.size)
            conn.setRequestProperty("Content-Type", "application/gzip")
            conn.setRequestProperty("User-Agent", "kelevra")
            Kelevra.deviceHeaders().forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.outputStream.use { it.write(archive) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "сервер отказал: http $code")
                return false
            }
            runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Одна попытка целиком. Возвращает:
     *  - true  — отправлять было нечего или отправилось;
     *  - false — не получилось, имеет смысл повторить.
     */
    internal suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) {
        val marks = loadMarks()
        // Отчёты о падениях идут первыми: если посылка упрётся в потолок, обрежется
        // обычный журнал, а не разбор падения.
        val parts = collectReports(reportRoots, marks) +
            collectParts(sourceDirs(ownLogsDir, downloadLogsDir), marks)
        if (parts.isEmpty()) {
            Log.i(TAG, "отправлять нечего: новых логов нет")
            return@withContext true
        }

        val (archive, taken) = buildArchive(parts)
        if (archive.isEmpty() || taken.isEmpty()) {
            Log.i(TAG, "архив пустой, отправка отменена")
            return@withContext true
        }

        Log.i(TAG, "отправляю логи: ${taken.size} шт., ${archive.size / 1024} КБ сжатыми")
        val ok = runCatching { upload(archive) }
            .onFailure { Log.w(TAG, "отправка логов сорвалась", Kelevra.maskThrowable(it)) }
            .getOrDefault(false)
        if (!ok) return@withContext false

        // Отметки двигаем только после успеха: сорванная отправка не должна съесть кусок.
        taken.forEach { part ->
            marks[part.label] = Mark(
                size = part.offset + part.length,
                modified = part.modified,
                sent = part.offset + part.length,
            )
        }
        saveMarks(marks)
        Settings.logUploadLastOk = System.currentTimeMillis()
        Log.i(TAG, "логи отправлены")
        true
    }

    class UploadTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val ok = runCatching { runOnce() }
                .onFailure { Log.w(TAG, "отправка логов упала", it) }
                .getOrDefault(false)
            if (ok) {
                Settings.logUploadRetrySince = 0L
                return Result.success()
            }
            // Повторяем, но не дольше суток: дальше ждём следующего вечера.
            val since = Settings.logUploadRetrySince.takeIf { it > 0 } ?: System.currentTimeMillis()
            Settings.logUploadRetrySince = since
            if (System.currentTimeMillis() - since >= RETRY_GIVE_UP_MILLIS) {
                Log.w(TAG, "сутки повторов не дали результата — ждём следующего вечера")
                Settings.logUploadRetrySince = 0L
                return Result.success()
            }
            return Result.retry()
        }
    }
}
