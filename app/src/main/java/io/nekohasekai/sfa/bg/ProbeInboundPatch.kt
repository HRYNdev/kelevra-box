package io.nekohasekai.sfa.bg

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Правка конфига под честную пробу: свой локальный вход на каждый путь.
 *
 * Зачем. Проба «через канал реально ходит трафик» умеет спрашивать только тот выход,
 * в который её пустят маршруты. Общий вход `mixed-in` (тот, что конфиг отдаёт системе
 * через `platform.http_proxy`) ведёт туда, куда смотрит селектор прямо сейчас, поэтому
 * спросить им «а жив ли ДРУГОЙ путь» нельзя: приходилось на время замера переставлять
 * селектор, и все новые соединения на эти секунды уходили в проверяемый (возможно
 * мёртвый) путь. Платил за замер пользователь.
 *
 * Что делаем. На каждый интересующий выход добавляем в конфиг отдельный socks-вход на
 * петле и правило маршрута `{"inbound":[вход], "outbound":[выход]}` первым в списке.
 * Дальше проба стучится в закреплённый вход и гарантированно меряет ровно этот путь,
 * не трогая ничей выбор. Порты берутся свободными в рантайме — фиксировать их в конфиге
 * нельзя, телефон не наш и порт может быть занят кем угодно.
 *
 * Правка живёт только в памяти: файл профиля приходит с сервера и не наш (тем же приёмом
 * работает [OlcRtcConfigPatch]).
 *
 * Железное правило. Не наложилось (порт занят, конфига не понял, выхода нет) — путь
 * остаётся БЕЗ входа. Мерить его нечем, и [HonestProbe] обязан сказать «не проверено»,
 * а не «работает». Молча выдать непроверенный путь за живой — ровно тот баг, ради
 * которого всё это делается.
 */
object ProbeInboundPatch {
    private const val TAG = "ProbeInbound"

    /** Префикс тега входа. Тег виден в логах sing-box, поэтому человекочитаемый. */
    const val TAG_PREFIX = "kelevra-probe-"

    private const val LOOPBACK = "127.0.0.1"

    data class Result(
        val content: String,
        /** Тег выхода → локальный вход, закреплённый именно за ним. */
        val entries: Map<String, AutoModeExits.Endpoint>,
        val note: String,
        val patched: Boolean,
    )

    /**
     * @param exits теги выходов, которые нужно уметь мерить по отдельности.
     * @return конфиг с добавленными входами и правилами. Любая неожиданность — конфиг
     *   возвращается нетронутым с пустым [Result.entries]: сломать старт ради пробы нельзя.
     */
    fun addProbeInbounds(content: String, exits: List<String>): Result =
        runCatching { patch(content, exits) }.getOrElse {
            Result(
                content,
                emptyMap(),
                "входы для пробы не добавлены (${it.javaClass.simpleName}: ${it.message}), " +
                    "пути останутся непроверяемыми",
                false,
            )
        }

    private fun patch(content: String, exits: List<String>): Result {
        if (exits.isEmpty()) return Result(content, emptyMap(), "мерить нечего: путей не передали", false)

        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds")
            ?: return Result(content, emptyMap(), "в конфиге нет outbounds, входы не добавляем", false)
        val knownExits = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotBlank() } }
            .toSet()

        val route = root.optJSONObject("route")
            ?: return Result(content, emptyMap(), "в конфиге нет route, входы не добавляем", false)
        // Правила есть, но это не список — конфиг мы не понимаем. Дописывать в него свои
        // правила наугад нельзя: лучше остаться без пробы, чем сломать чужой маршрут.
        if (route.has("rules") && route.optJSONArray("rules") == null) {
            return Result(content, emptyMap(), "route.rules не список, входы не добавляем", false)
        }

        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        val busyPorts = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it)?.optInt("listen_port")?.takeIf { p -> p in 1..65535 } }
            .toMutableSet()
        val busyTags = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotBlank() } }
            .toMutableSet()

        val entries = LinkedHashMap<String, AutoModeExits.Endpoint>()
        val newRules = JSONArray()
        val skipped = mutableListOf<String>()

        for (exit in exits.distinct()) {
            if (exit.isBlank()) continue
            if (exit !in knownExits) {
                skipped += "«$exit» (такого выхода в конфиге нет)"
                continue
            }
            val port = freePort(busyPorts)
            if (port == null) {
                skipped += "«$exit» (свободного порта не нашлось)"
                continue
            }
            busyPorts += port

            var tag = TAG_PREFIX + slug(exit)
            var n = 2
            while (tag in busyTags) tag = TAG_PREFIX + slug(exit) + "-" + n++
            busyTags += tag

            inbounds.put(
                JSONObject()
                    .put("type", "socks")
                    .put("tag", tag)
                    .put("listen", LOOPBACK)
                    .put("listen_port", port),
            )
            // Форма без "action" намеренно: ровно так правила пишет сам сервер
            // (`{"outbound":"direct","ip_is_private":true}`), значит её понимает то ядро,
            // с которым мы реально живём.
            newRules.put(
                JSONObject()
                    .put("inbound", JSONArray().put(tag))
                    .put("outbound", exit),
            )
            entries[exit] = AutoModeExits.Endpoint(LOOPBACK, port)
        }

        if (entries.isEmpty()) {
            return Result(
                content,
                emptyMap(),
                "входы для пробы не добавлены: " + skipped.joinToString("; ").ifBlank { "нечего добавлять" },
                false,
            )
        }

        // Первыми: наши правила должны выиграть у всего, что уводит трафик по доменам
        // и адресам. Чужой трафик они не задевают — каждое привязано к своему входу.
        val old = route.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until old.length()) newRules.put(old.opt(i))
        route.put("rules", newRules)

        val note = buildString {
            append("входы для пробы: ")
            append(entries.entries.joinToString { (exit, entry) -> "«$exit» → ${entry.host}:${entry.port}" })
            if (skipped.isNotEmpty()) append("; без входа осталось: ").append(skipped.joinToString("; "))
        }
        return Result(root.toString(), entries, note, true)
    }

    /**
     * Свободный порт спрашиваем у системы: занимаем на секунду и сразу отпускаем.
     * Гонка тут есть (кто-то может влезть между освобождением и стартом sing-box), но
     * платой за неё будет честное «вход не поднялся» в логе ядра и «не проверено» наверх,
     * а не враньё про живой канал.
     */
    private fun freePort(busy: Set<Int>): Int? {
        repeat(8) {
            val port = runCatching {
                ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            }.getOrNull() ?: return@repeat
            if (port !in busy) return port
        }
        return null
    }

    /** Тег выхода бывает русским и с пробелами — в тег входа он идёт в приличном виде. */
    private fun slug(exit: String): String {
        val ascii = exit.map { if (it.code in 33..126 && it != '"') it else '-' }.joinToString("")
        val squeezed = ascii.trim('-').replace(Regex("-{2,}"), "-")
        return squeezed.ifBlank { "exit" }.take(24) + "-" + Integer.toHexString(exit.hashCode()).takeLast(4)
    }

    fun log(result: Result) {
        Log.i(TAG, result.note)
    }
}
