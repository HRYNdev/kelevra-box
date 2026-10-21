package io.nekohasekai.sfa.bg

import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Запись журнала, которая доходит до файла на любом телефоне, что бы прошивка ни делала
 * с logcat.
 *
 * Повод. Свой журнал ([AppLog]) собирался из logcat процесса. 20.10.2026 у Honor в файле
 * были только строки уровня E (прошивка режет всё ниже ошибки), а у Huawei чтение logcat
 * каждые несколько минут «прерывалось» и не приносило ничего: восемь дней журнала из одних
 * строк «чтение logcat прервалось». В #52 KLog дописывал в файл сам только те уровни,
 * которые самопроверка на старте сочла режущимися, — но logcat, живой на старте, умирал
 * позже, а опоздавший маркер снимал прямую запись обратно, и журнал снова слеп.
 *
 * Как устроено теперь. Каждый вызов пишет строку в файл журнала НАПРЯМУЮ, всегда и на всех
 * уровнях, тем же форматом `threadtime`, — от logcat запись больше не зависит вовсе.
 * В [android.util.Log] строка уходит тоже, как раньше: её видно по adb при отладке.
 * Чтобы живой logcat не давал дублей, KLog запоминает свои теги ([tegi]), и чтение logcat
 * в [AppLog] выбрасывает строки своего процесса с этими тегами: они уже в файле. Всё
 * остальное, что logcat приносит из процесса (чужие библиотеки, падения AndroidRuntime,
 * WebView), по-прежнему пишется из logcat — там, где он жив.
 */
object KLog {
    /** Тег записи о необработанном исключении процесса. */
    const val TEG_PADENIE = "KelevraPadenie"

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val tegiMap = ConcurrentHashMap<String, Boolean>()

    /**
     * Теги, которыми KLog хоть раз писал. Строка с таким тегом из logcat своего процесса —
     * дубль уже записанной напрямую. Тег попадает сюда ДО записи в logcat, поэтому чтение
     * logcat не может увидеть строку раньше, чем узнает её тег.
     */
    val tegi: Set<String> get() = tegiMap.keys

    @JvmStatic
    @JvmOverloads
    fun v(tag: String, msg: String, tr: Throwable? = null): Int = zapisat('V', tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = zapisat('D', tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = zapisat('I', tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = zapisat('W', tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = zapisat('E', tag, msg, tr)

    private fun zapisat(level: Char, tag: String, msg: String, tr: Throwable?): Int {
        tegiMap.putIfAbsent(tag, true)
        // В logcat — для adb. Сбой здесь (юнит-тест без android.jar, экзотика) не должен
        // стоить строки в файле.
        val rc = runCatching {
            if (tr == null) Log.println(prioritet(level), tag, msg) else logcat(level, tag, msg, tr)
        }.getOrDefault(0)
        pryamo(level, tag, msg, tr, polnyStek = false)
        return rc
    }

    private fun logcat(level: Char, tag: String, msg: String, tr: Throwable): Int = when (level) {
        'V' -> Log.v(tag, msg, tr)
        'D' -> Log.d(tag, msg, tr)
        'I' -> Log.i(tag, msg, tr)
        'W' -> Log.w(tag, msg, tr)
        else -> Log.e(tag, msg, tr)
    }

    private fun prioritet(level: Char): Int = when (level) {
        'V' -> Log.VERBOSE
        'D' -> Log.DEBUG
        'I' -> Log.INFO
        'W' -> Log.WARN
        else -> Log.ERROR
    }

    private fun pryamo(level: Char, tag: String, msg: String, tr: Throwable?, polnyStek: Boolean) {
        // Шумные теги из logcat в файл не возили никогда — и напрямую не возим.
        if (AppLog.shumnyTeg(tag)) return
        val text = runCatching {
            val pid = runCatching { Process.myPid() }.getOrDefault(0)
            val tid = runCatching { Process.myTid() }.getOrDefault(0)
            stroka(synchronized(stampFormat) { stampFormat.format(Date()) }, pid, tid, level, tag, msg, tr, polnyStek)
        }.getOrNull() ?: return
        AppLog.pryamo(text)
    }

    /**
     * Строка в формате logcat `threadtime`: `MM-DD HH:MM:SS.mmm  PID  TID L TAG: сообщение`.
     *
     * Многострочное сообщение logcat режет на строки с тем же началом — так же и здесь,
     * иначе продолжение без метки разбор приклеил бы к чужой записи. Из исключения берём
     * класс с текстом и цепочку причин («Caused by: …»), без строк стека — их журнал не
     * возил и из logcat ([AppLog.shumnaya]). Целиком стек пишется только для падения
     * процесса ([polnyStek]).
     */
    internal fun stroka(
        stamp: String,
        pid: Int,
        tid: Int,
        level: Char,
        tag: String,
        msg: String,
        tr: Throwable?,
        polnyStek: Boolean = false,
    ): String {
        val prefix = "$stamp ${pid.toString().padStart(5)} ${tid.toString().padStart(5)} $level $tag: "
        val lines = msg.split('\n').toMutableList()
        if (tr != null) {
            if (polnyStek) {
                lines += stek(tr).trimEnd().split('\n')
            } else {
                lines += tr.toString()
                var cause = tr.cause
                var depth = 0
                while (cause != null && cause !== tr && depth < 8) {
                    lines += "Caused by: $cause"
                    cause = cause.cause
                    depth++
                }
            }
        }
        return lines.joinToString("") { prefix + it.trimEnd('\r') + "\n" }
    }

    private fun stek(tr: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { tr.printStackTrace(it) }
        return writer.toString()
    }

    /**
     * Запись падения: заголовок и стек целиком, сразу на диск. Отдельно от [ustanovitLovushku],
     * чтобы проверялось тестом без подмены обработчика процесса.
     */
    internal fun padenie(thread: Thread, tr: Throwable) {
        val msg = "FATAL EXCEPTION: поток ${thread.name}, процесс завершается"
        tegiMap.putIfAbsent(TEG_PADENIE, true)
        runCatching { Log.e(TEG_PADENIE, msg, tr) }
        pryamo('E', TEG_PADENIE, msg, tr, polnyStek = true)
        AppLog.sbrosit()
    }

    @Volatile
    private var lovushka = false

    /**
     * Ловушка необработанного исключения процесса: стек целиком — в файл журнала напрямую
     * (logcat может быть мёртв, а системная запись AndroidRuntime идёт только туда), и уже
     * потом — прежний обработчик (сборщик крашей, системное «приложение остановлено»).
     * Повторный вызов ничего не делает.
     */
    @Synchronized
    fun ustanovitLovushku() {
        if (lovushka) return
        lovushka = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { padenie(thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Так же поступает системный обработчик: без этого упавший главный поток
                // оставил бы процесс висеть.
                runCatching { Process.killProcess(Process.myPid()) }
                exitProcess(10)
            }
        }
    }
}
