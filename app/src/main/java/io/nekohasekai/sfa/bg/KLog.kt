package io.nekohasekai.sfa.bg

import android.os.Process
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись журнала, которая доходит до файла и там, где прошивка режет logcat.
 *
 * Повод. Свой журнал ([AppLog]) собирается из logcat процесса. 20.10.2026 в журнале
 * телефона Honor были только строки уровня E: прошивка выбрасывает из logcat всё, что
 * ниже ошибки, и решения автомата, старты сервиса, пробы до файла не доезжали вовсе.
 *
 * Как устроено. Каждый вызов пишет в [android.util.Log] как раньше, а строку тех
 * уровней, которые logcat до нас не доносит, кладёт в файл журнала ещё и напрямую —
 * тем же форматом `threadtime`, так что разбор на сервере разницы не видит. Какие уровни
 * режутся, узнаёт самопроверка [AppLog]: на старте записи она пишет по маркеру каждого
 * уровня и смотрит, какие вернулись из logcat. Пока самопроверка не кончилась, строки
 * ждут в памяти ([OZHIDANIE_MAX]) и потом либо дописываются (уровень режется), либо
 * выбрасываются (logcat их и так донёс) — живой logcat поэтому дублей не даёт.
 */
object KLog {
    /** Сколько строк держим до решения самопроверки. Старше — вытесняются. */
    private const val OZHIDANIE_MAX = 2000

    /** Уровни, которые проверяет самопроверка. V идёт вслед за D: отдельно её не мерим. */
    internal val PROVERYAEMYE = listOf('D', 'I', 'W', 'E')

    private val lock = Any()

    /** Уровни, которые logcat режет; null — самопроверка ещё не решила. */
    @Volatile
    private var rezhet: Set<Char>? = null

    private val ozhidanie = ArrayDeque<Pair<Char, String>>()

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Для паспорта устройства: «ok» — logcat доносит всё, «rezhet_DIW» — какие уровни
     * пишем сами, «?» — самопроверка ещё не кончилась или не запускалась.
     */
    fun priznak(): String = opisanie(rezhet)

    internal fun opisanie(cut: Set<Char>?): String = when {
        cut == null -> "?"
        cut.isEmpty() -> "ok"
        else -> "rezhet_" + cut.filter { it in PROVERYAEMYE }.sorted().joinToString("")
    }

    fun v(tag: String, msg: String, tr: Throwable? = null): Int =
        (if (tr == null) Log.v(tag, msg) else Log.v(tag, msg, tr)).also { pryamo('V', tag, msg, tr) }

    fun d(tag: String, msg: String, tr: Throwable? = null): Int =
        (if (tr == null) Log.d(tag, msg) else Log.d(tag, msg, tr)).also { pryamo('D', tag, msg, tr) }

    fun i(tag: String, msg: String, tr: Throwable? = null): Int =
        (if (tr == null) Log.i(tag, msg) else Log.i(tag, msg, tr)).also { pryamo('I', tag, msg, tr) }

    fun w(tag: String, msg: String, tr: Throwable? = null): Int =
        (if (tr == null) Log.w(tag, msg) else Log.w(tag, msg, tr)).also { pryamo('W', tag, msg, tr) }

    fun e(tag: String, msg: String, tr: Throwable? = null): Int =
        (if (tr == null) Log.e(tag, msg) else Log.e(tag, msg, tr)).also { pryamo('E', tag, msg, tr) }

    private fun pryamo(level: Char, tag: String, msg: String, tr: Throwable?) {
        val cut = rezhet
        // Logcat доносит этот уровень — второй раз писать нечего.
        if (cut != null && level !in cut) return
        val text = runCatching {
            stroka(synchronized(stampFormat) { stampFormat.format(Date()) }, Process.myPid(), Process.myTid(), level, tag, msg, tr)
        }.getOrNull() ?: return
        if (cut == null) {
            synchronized(lock) {
                // Решение могло прийти, пока собирали строку.
                val now = rezhet
                if (now == null) {
                    if (ozhidanie.size >= OZHIDANIE_MAX) ozhidanie.removeFirst()
                    ozhidanie.addLast(level to text)
                    return
                }
                if (level !in now) return
            }
        }
        AppLog.pryamo(text)
    }

    /**
     * Строка в формате logcat `threadtime`: `MM-DD HH:MM:SS.mmm  PID  TID L TAG: сообщение`.
     *
     * Многострочное сообщение logcat режет на строки с тем же началом — так же и здесь,
     * иначе продолжение без метки разбор приклеил бы к чужой записи. Из исключения берём
     * одну строку (класс и текст): стек в журнале и так отсекается ([AppLog.shumnaya]).
     */
    internal fun stroka(stamp: String, pid: Int, tid: Int, level: Char, tag: String, msg: String, tr: Throwable?): String {
        val prefix = "$stamp ${pid.toString().padStart(5)} ${tid.toString().padStart(5)} $level $tag: "
        val lines = msg.split('\n').toMutableList()
        if (tr != null) lines += tr.toString()
        return lines.joinToString("") { prefix + it.trimEnd('\r') + "\n" }
    }

    /**
     * Самопроверка решила: [cut] — уровни, которые не вернулись из logcat. Ждавшие строки
     * этих уровней уходят в файл, остальные выбрасываются.
     */
    internal fun reshit(cut: Set<Char>) {
        val flush = synchronized(lock) {
            rezhet = cut
            val keep = ozhidanie.filter { it.first in cut }.map { it.second }
            ozhidanie.clear()
            keep
        }
        flush.forEach { AppLog.pryamo(it) }
    }

    /**
     * Маркер уровня вернулся из logcat уже после решения (медленный телефон, logcat
     * поднимался долго) — значит уровень не режется, писать его самим больше не надо.
     * @return true, если признак изменился.
     */
    internal fun vernulsya(level: Char): Boolean = synchronized(lock) {
        val cut = rezhet ?: return false
        if (level !in cut) return false
        val now = cut - level - (if (level == 'D') setOf('V') else emptySet())
        rezhet = now
        true
    }

    /** Что режется по итогам самопроверки: всё проверенное, чего не видели, и V вслед за D. */
    internal fun rezhetsyaPo(uvideli: Set<Char>): Set<Char> {
        val cut = PROVERYAEMYE.filter { it !in uvideli }.toMutableSet()
        if ('D' in cut) cut += 'V'
        return cut
    }
}
