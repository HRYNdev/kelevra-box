package io.nekohasekai.sfa.bg

import android.util.Log
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

    /** Удалённый набор, каким его прислал сервер: по этому списку работает докачка. */
    data class Remote(val tag: String, val url: String, val format: String)

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
    )

    /**
     * @param cached тег набора → файл в кэше. Пустая карта — законный случай: первый
     *   запуск, кэша ещё нет.
     * @return конфиг, которому для старта не нужна сеть. Любая неожиданность — конфиг
     *   возвращается нетронутым: сломать старт правкой, которая старт и чинит, нельзя.
     */
    fun useCached(content: String, cached: Map<String, File>): Result =
        runCatching { patch(content, cached) }.getOrElse {
            Result(
                content,
                emptyList(),
                emptyList(),
                emptyList(),
                "наборы правил оставлены как есть (${it.javaClass.simpleName}: ${it.message})",
                false,
            )
        }

    private fun patch(content: String, cached: Map<String, File>): Result {
        val root = JSONObject(content)
        val route = root.optJSONObject("route")
            ?: return untouched(content, "в конфиге нет route")
        val sets = route.optJSONArray("rule_set")
            ?: return untouched(content, "в конфиге нет наборов правил")

        val remotes = mutableListOf<Remote>()
        val ready = mutableListOf<String>()
        val missing = mutableListOf<String>()
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
            remotes += Remote(tag, set.optString("url"), format)

            val file = cached[tag]
            if (file == null) {
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
        if (ready.isEmpty()) {
            return Result(content, remotes, ready, missing, "наборы правил: кэша нет, конфиг не трогаем", false)
        }

        route.put("rule_set", kept)

        val note = buildString {
            append("наборы правил: из кэша ${ready.size} из ${remotes.size}")
            if (missing.isNotEmpty()) append("; остались удалёнными: ").append(missing.joinToString(", "))
        }
        return Result(root.toString(), remotes, ready, missing, note, true)
    }

    private fun untouched(content: String, why: String) =
        Result(content, emptyList(), emptyList(), emptyList(), why, false)

    fun log(result: Result) {
        if (result.missing.isEmpty()) Log.i(TAG, result.note) else Log.w(TAG, result.note)
    }
}
