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
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Своя запись журнала: приложение пишет лог само, себе, без единого разрешения.
 *
 * Зачем понадобилось. Логи телефона до этого писал внешний adb-шелл в
 * /sdcard/Download/kelevra-logs — файлы принадлежали шеллу, и на Android 11+
 * приложению для их чтения нужен «Доступ ко всем файлам», который выдаётся руками в
 * системных настройках. С одним телефоном разработчика это работало, с семьёй — нет:
 * ни домашние, ни Влад, ни домашние, ни домашние в системные настройки за таким разрешением не
 * пойдут, а логи нужны со ВСЕХ устройств. Поэтому запись переехала внутрь приложения.
 *
 * Куда пишем. `getExternalFilesDir(null)/logs` — собственный каталог приложения на
 * внешнем хранилище. Разрешений не требует вообще (ни на одной версии Android), при
 * удалении приложения уносится вместе с ним, и при этом виден с компьютера обычным
 * файловым менеджером по пути `/sdcard/Android/data/<пакет>/files/logs` — файл можно
 * достать с телефона без root. Если внешнего хранилища нет вовсе, откатываемся на
 * `filesDir`: там тоже всё работает, только достать сложнее.
 *
 * Что пишем. Свои же записи logcat. Начиная с Android 4.1 приложение читает из logcat
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

    /** Сорвавшийся logcat поднимаем заново, но не чаще раза в полминуты. */
    private const val RESTART_PAUSE_MILLIS = 30_000L

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var rotator: LogRotator? = null

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
            val target = LogRotator(folder, BASE_NAME, MAX_FILE_BYTES, MAX_FILES)
            rotator = target
            target
        }.onFailure { Log.w(TAG, "не удалось открыть свой журнал, попробую позже", it) }.getOrNull() ?: return

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
            "=== запись журнала начата, $устройство, pid ${Process.myPid()}, " +
                "телефон включён ${SystemClock.elapsedRealtime() / 60_000} мин назад ===",
        )
        pump = Thread({ pumpLoop(started) }, "kelevra-app-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
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
        val target = rotator ?: return
        runCatching { target.append("${stampFormat.format(Date())} $line\n") }
    }

    /**
     * Качает logcat в файл, пока процесс жив. Если поток оборвался (система уронила
     * logcat, буфер пересоздали) — ждём и поднимаем заново: молчащий журнал хуже,
     * чем журнал с дыркой.
     */
    private fun pumpLoop(target: LogRotator) {
        while (true) {
            val failure = runCatching { drain(target) }.exceptionOrNull()
            note("=== чтение logcat прервалось${failure?.let { ": ${it.javaClass.simpleName}" }.orEmpty()}, поднимаю заново ===")
            runCatching { Thread.sleep(RESTART_PAUSE_MILLIS) }.onFailure { return }
        }
    }

    private fun drain(target: LogRotator) {
        val command = mutableListOf("logcat", "-v", "threadtime")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            command += "--pid"
            command += Process.myPid().toString()
        }
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    target.append(line + "\n")
                }
            }
        } finally {
            runCatching { process.destroy() }
        }
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
) {
    private var out: OutputStream? = null
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
        stream.write(bytes)
        stream.flush()
        headBytes += bytes.size
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
