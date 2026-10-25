package io.nekohasekai.sfa.bg

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Правка конфига под наборы правил из своего кэша.
 *
 * Зачем. Конфиг приходит с сервера с 22 наборами вида
 * `{"type":"remote","url":"https://<наш домен>/rules/ads.srs","http_client":{"detour":"direct"}}`.
 * Ядро тянет их САМО и делает это на старте, до того как встанет хоть какая-то связь,
 * да ещё и мимо туннеля (`detour: direct`). В сети с белым списком наш домен срезан —
 * скачивание не проходит, старт ядра падает целиком. Дальше замок: комнату не поднять
 * без туннеля, туннель не поднять без наборов, наборы не скачать без комнаты.
 *
 * Что делаем. Перед стартом подменяем удалённые наборы на локальные файлы из кэша
 * ([RuleSetCache]). Скачиванием занимаемся сами и уже ПОСЛЕ того, как связь появилась,
 * поэтому старт ядра о доступности домена больше не спрашивает вообще.
 *
 * Чего в кэше нет — то остаётся ровно таким, каким его прислал сервер. Это проверено
 * на стенде 08.08.2026 и важнее, чем кажется: ядро при недоступности набора НЕ падает
 * и старт не срывает — оно поднимается, а набор тянет фоном, каждый со своим таймаутом
 * (в логе `router: fetch rule-set X: ... i/o timeout`), и держит своё собственное
 * хранилище (`experimental.cache_file`). Значит выбрасывать удалённый набор нельзя:
 * мы бы отняли правила, которые ядро способно взять из своего хранилища, и сделали
 * маршруты хуже, чем без всякой правки. Правка обязана только добавлять.
 *
 * Итог: что лежит у нас — уходит локальными файлами и берётся мгновенно, без сети;
 * чего нет — работает как работало. Человеку про неполные правила говорится вслух
 * ([io.nekohasekai.sfa.bg.path.PathWords.rulesNote]), молчать об этом нельзя.
 *
 * Правка живёт только в памяти: файл профиля приходит с сервера и не наш (тем же приёмом
 * работают [OlcRtcConfigPatch] и [ProbeInboundPatch]).
 *
 * Железное правило то же, что у соседей: не наложилось — конфиг возвращается нетронутым.
 * Хуже от этого не станет, потому что нетронутый конфиг — это ровно сегодняшнее поведение.
 */
object RuleSetLocalPatch {
    private const val TAG = "RuleSetPatch"

    /** Потолок повторов старта на битых наборах: больше наборов, чем есть, не выкинуть. */
    const val MAX_SETS = 64

    /**
     * Удалённый набор, каким его прислал сервер: по этому списку работает докачка.
     *
     * @param updateMillis `update_interval` набора из профиля в миллисекундах, `0` — не задан
     *   или не разобрался. Наборы своих правил (`svoi-*`, решение 25.10.2026 «одна истина»)
     *   приходят с интервалом 2 минуты: правка в панели должна доходить за минуты, а не за
     *   сутки, как у больших чужих наборов ([RuleSetCache.freshFor]).
     */
    data class Remote(val tag: String, val url: String, val format: String, val updateMillis: Long = 0L)

    data class Result(
        val content: String,
        /** Все удалённые наборы из конфига — что вообще надо иметь в кэше. */
        val remotes: List<Remote>,
        /** Теги, которые ушли в ядро локальными файлами. */
        val ready: List<String>,
        /** Теги, которых в кэше не было: оставлены удалёнными, как прислал сервер. */
        val missing: List<String>,
        val note: String,
        val patched: Boolean,
        /**
         * Теги, которым кэша не было и которые ушли в ядро локальной заглушкой
         * ([RuleSetCache.zaglushka]): докачка заменит файл, ядро перечитает его само.
         */
        val placeholders: List<String> = emptyList(),
    )

    /**
     * @param cached тег набора → файл в кэше. Пустая карта — законный случай: первый
     *   запуск, кэша ещё нет.
     * @return конфиг, которому для старта не нужна сеть. Любая неожиданность — конфиг
     *   возвращается нетронутым: сломать старт правкой, которая старт и чинит, нельзя.
     */
    fun useCached(
        content: String,
        cached: Map<String, File>,
        zaglushka: ((Remote) -> File?)? = null,
    ): Result =
        runCatching { patch(content, cached, zaglushka) }.getOrElse {
            Result(
                content,
                emptyList(),
                emptyList(),
                emptyList(),
                "наборы правил оставлены как есть (${it.javaClass.simpleName}: ${it.message})",
                false,
            )
        }

    private fun patch(content: String, cached: Map<String, File>, zaglushka: ((Remote) -> File?)?): Result {
        val root = JSONObject(content)
        val route = root.optJSONObject("route")
            ?: return untouched(content, "в конфиге нет route")
        val sets = route.optJSONArray("rule_set")
            ?: return untouched(content, "в конфиге нет наборов правил")

        val remotes = mutableListOf<Remote>()
        val ready = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val kept = JSONArray()

        for (i in 0 until sets.length()) {
            val set = sets.optJSONObject(i)
            if (set == null) {
                kept.put(sets.opt(i))
                continue
            }
            val tag = set.optString("tag")
            // Не «remote» — не наша забота: локальные наборы старту и так не мешают.
            if (set.optString("type") != "remote" || tag.isBlank()) {
                kept.put(set)
                continue
            }
            val format = set.optString("format").ifBlank { "binary" }
            val remote = Remote(tag, set.optString("url"), format, durationMillis(set.optString("update_interval")))
            remotes += remote

            var file = cached[tag]
            // Текстового набора в кэше ещё нет (свои правила `svoi-*` впервые пришли в профиле).
            // Удалённым его оставлять нельзя: ядро 1.14 без своего хранилища качает такой набор
            // на старте и при неудаче НЕ СТАРТУЕТ ВОВСЕ («initial rule-set: …», проверено на
            // эмуляторе 25.10.2026), а под белым списком дверь как раз недоступна. Вместо
            // него — локальная заглушка в том же файле кэша: ядро встаёт сразу, докачка кладёт
            // настоящий набор на её место, ядро перечитывает файл само.
            if (file == null && zaglushka != null && format == "source") {
                file = zaglushka(remote)?.also { placeholders += tag }
            }
            // Заглушка с прошлого запуска, которую ещё не сменил настоящий набор.
            if (file != null && tag !in placeholders && RuleSetCache.isZaglushka(file)) placeholders += tag
            // Файл в кэше другого вида, чем набор в профиле (сервер сменил `binary` на `source`
            // под тем же тегом, или наоборот): локальный набор с чужим форматом ядро не
            // разберёт и не стартует. Такой оставляем удалённым — докачка заберёт свежий.
            if (file == null || !formatMatches(file, format)) {
                // Не наш — не трогаем. Ядро возьмёт его из своего хранилища или дотянет
                // фоном; отняв запись, мы бы только лишили его этой возможности.
                missing += tag
                kept.put(set)
                continue
            }
            // Ровно четыре поля. `update_interval` и `http_client` относятся к скачиванию,
            // которого у локального набора нет, и ядро на них ругается.
            kept.put(
                JSONObject()
                    .put("tag", tag)
                    .put("type", "local")
                    .put("format", format)
                    .put("path", file.absolutePath),
            )
            ready += tag
        }

        if (remotes.isEmpty()) return untouched(content, "удалённых наборов в конфиге нет")
        // Кэш пуст — менять нечего, и трогать конфиг незачем: пусть уходит в ядро тем же
        // текстом, каким пришёл с сервера.
        // Заглушка в кэше — тоже файл, но человеку «правила загружены» про неё не говорим.
        ready.removeAll(placeholders.toSet())
        if (ready.isEmpty() && placeholders.isEmpty()) {
            return Result(content, remotes, ready, missing, "наборы правил: кэша нет, конфиг не трогаем", false)
        }

        route.put("rule_set", kept)

        val note = buildString {
            append("наборы правил: из кэша ${ready.size} из ${remotes.size}")
            if (missing.isNotEmpty()) append("; остались удалёнными: ").append(missing.joinToString(", "))
            if (placeholders.isNotEmpty()) append("; заглушкой до докачки: ").append(placeholders.joinToString(", "))
        }
        return Result(root.toString(), remotes, ready, missing, note, true, placeholders)
    }

    /**
     * Совпадает ли содержимое файла с форматом набора в профиле. Прочитать не вышло (файла
     * нет — так бывает только в тестах с выдуманными путями) — не судим: `true`, как раньше.
     */
    internal fun formatMatches(file: File, format: String): Boolean {
        val kind = RuleSetCache.kindOf(file) ?: return true
        return kind == format
    }

    /**
     * Длительность sing-box (`2m`, `1h`, `1h30m`, `90s`, `1d`) в миллисекундах.
     * Пусто или не разобралось — `0`: интервал не задан, докачка живёт по своему суточному.
     */
    internal fun durationMillis(text: String?): Long {
        val s = text?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return 0L
        val part = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h|d)""")
        var total = 0.0
        var pos = 0
        for (m in part.findAll(s)) {
            if (m.range.first != pos) return 0L
            pos = m.range.last + 1
            val n = m.groupValues[1].toDouble()
            total += n * when (m.groupValues[2]) {
                "ms" -> 1.0
                "s" -> 1_000.0
                "m" -> 60_000.0
                "h" -> 3_600_000.0
                else -> 86_400_000.0
            }
        }
        if (pos != s.length) return 0L
        return total.toLong()
    }

    private fun untouched(content: String, why: String) =
        Result(content, emptyList(), emptyList(), emptyList(), why, false)

    fun log(result: Result) {
        if (result.missing.isEmpty()) KLog.i(TAG, result.note) else KLog.w(TAG, result.note)
    }
}
