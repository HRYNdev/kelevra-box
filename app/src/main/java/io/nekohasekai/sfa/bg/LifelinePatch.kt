package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject

/**
 * Страховочные маршруты: то, что обязано работать даже когда правила не скачались.
 *
 * Зачем. Все наборы правил в конфиге — удалённые, они лежат на нашем домене. Под белым
 * списком оператора этот домен недоступен: он сам ни в один набор не входит, значит идёт
 * `final: direct`, то есть в стену. Получается кольцо — чтобы скачать правила, нужны
 * правила. Пока оно не разорвано, под ограничением у человека работает ровно то, что
 * случайно осталось в кэше с прошлого раза.
 *
 * Проверено боем на живом телефоне, реальный белый список оператора: комната поднята
 * и живая, браузер ходит, а `https://subkv.chickenkiller.com/rules/...` отдаёт `HTTP 000`
 * за 10.7 с, и в журнале комнаты этого соединения нет вовсе. Телеграм там же: набор
 * `telegram` не скачался, трафик ушёл `direct` и получил мгновенный отказ от оператора.
 *
 * Что вшиваем:
 *  1. Наш домен — иначе сеть не может починить сама себя: ни правил, ни конфига, ни
 *     новой версии клиента под ограничением не достать.
 *  2. Подсети Телеграма — он ходит по своим адресам без имён, поэтому доменные наборы
 *     ему не помогают, а без него мессенджер молчит, что человек и замечает первым.
 *     Адреса взяты официальные и меняются раз в годы; кэш и обычные наборы, когда
 *     доедут, всё равно перекроют их сверху.
 *
 * Правило ставится ПЕРЕД `final` и ведёт в тот же выход, куда конфиг уже гонит остальное
 * (селектор), поэтому оно работает и через комнату, и через основной канал, и ничего не
 * меняет там, где правила скачались нормально.
 */
object LifelinePatch {
    private const val TAG = "BoxService"

    /** Домены своей инфраструктуры: раздача правил, конфигов и обновлений. */
    private val OWN_DOMAINS = listOf("chickenkiller.com")

    /**
     * Официальные подсети Telegram (`t.me/ip_ranges`), IPv4.
     */
    private val TELEGRAM_CIDRS = listOf(
        "91.108.4.0/22", "91.108.8.0/21", "91.108.12.0/22", "91.108.16.0/21",
        "91.108.20.0/22", "91.108.56.0/22", "95.161.64.0/20", "149.154.160.0/20",
        "185.76.151.0/24",
    )

    /*
     * IPv6 Телеграма страховка НЕ трогает. Раньше здесь стоял `reject` на его v6-сети
     * («выход у нас только IPv4»). С 27.10 у основного выхода IPv6 есть, а правило
     * дописывалось в конец и не срабатывало ни разу: v6 Телеграма раньше забирает набор
     * `telegram` (в туннель). Сработай оно, когда набор не скачался, — резало бы IPv6
     * Телеграма и там, где он работает. Выход без IPv6 лечится на сервере, а в комнате
     * v6 отклоняет `OlcRtcConfigPatch.rejectIpv6ToRoom`.
     */

    data class Result(val content: String, val note: String, val patched: Boolean)

    fun addLifeline(content: String): Result = runCatching { patch(content) }.getOrElse {
        // Конфиг приходит с сервера и меняется. Уронить старт из-за неудачной правки
        // хуже, чем остаться без страховки.
        Result(content, "страховочные маршруты не легли (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    private fun patch(content: String): Result {
        val root = JSONObject(content)
        val route = root.optJSONObject("route") ?: return Result(content, "в конфиге нет route", false)
        val rules = route.optJSONArray("rules") ?: JSONArray()

        // Куда гнать: берём выход того правила, которое уже уводит наружу наборы.
        // Так страховка идёт туда же, куда и всё остальное, — сегодня в комнату,
        // завтра в основной канал, и знать об этом ей не нужно.
        val target = tunnelTarget(root, rules)
            ?: return Result(content, "не нашли, куда конфиг гонит наборы — страховку не ставим", false)

        // Узнаём своё правило по содержимому, а не по своей пометке: лишнее поле в
        // правиле sing-box не переживает — конфиг не принимается, и ядро молча не встаёт
        // (проверено на живом телефоне: туннель не поднимался вообще).
        val already = (0 until rules.length()).any { i ->
            val cidrs = rules.optJSONObject(i)?.optJSONArray("ip_cidr") ?: return@any false
            (0 until cidrs.length()).any { cidrs.optString(it) == TELEGRAM_CIDRS.first() }
        }
        if (already) return Result(content, "страховочные маршруты уже стоят", false)

        // ДВА правила, а не одно: внутри правила sing-box поля соединяются логическим И.
        // Одно правило с доменом и подсетями требовало бы, чтобы имя оканчивалось на наш
        // домен И адрес лежал в сетях Телеграма — такого не бывает, правило было мёртвым
        // (моя ошибка, поймана на живом телефоне: страховка «стояла», но не работала).
        rules.put(
            JSONObject().apply {
                put("outbound", target)
                put("domain_suffix", JSONArray(OWN_DOMAINS))
            },
        )
        rules.put(
            JSONObject().apply {
                put("outbound", target)
                put("ip_cidr", JSONArray(TELEGRAM_CIDRS))
            },
        )
        route.put("rules", rules)

        return Result(
            root.toString(),
            "страховочные маршруты → «$target»: свой домен (${OWN_DOMAINS.size}) " +
                "и подсети Телеграма (${TELEGRAM_CIDRS.size})",
            true,
        )
    }

    /**
     * Наборы, по которым правило туннеля узнаётся наверняка: свои правила «в туннель»
     * (`svoi-tunnel`, `svoi-tunnel-2` — по двери на набор) и прежний общий список доменов.
     */
    private val TUNNEL_SETS = listOf("svoi-tunnel", "main-domains")

    /**
     * Выход правила туннеля.
     *
     * Раньше это было «первое правило с `rule_set` без `action`». С одной истиной правил
     * (решение 25.10.2026) выше туннеля встаёт правило «напрямую» (`svoi-mimo` →
     * `direct`), и если сервер запишет его без `action`, страховка — наш домен и подсети
     * Телеграма — ушла бы напрямую, а под белым списком это смерть: ни правил, ни профиля,
     * ни обновлений. Поэтому по порядку:
     *  1. правило, в наборах которого есть [TUNNEL_SETS] (по имени набора, а не по месту);
     *  2. иначе первое правило с наборами, которое ведёт не в прямой выход и не в отказ;
     *  3. иначе — не ставим страховку вовсе: вести её напрямую хуже, чем не ставить.
     *
     * `action: route` — то же, что правило без `action`: так sing-box понимает оба вида.
     */
    internal fun tunnelTarget(root: JSONObject, rules: JSONArray): String? {
        val routing = (0 until rules.length())
            .mapNotNull { rules.optJSONObject(it) }
            .filter { it.has("rule_set") && it.optString("action").let { a -> a.isEmpty() || a == "route" } }
            .filter { it.optString("outbound").isNotEmpty() }
        routing.firstOrNull { rule ->
            ruleSets(rule).any { set -> TUNNEL_SETS.any { set == it || set.startsWith("$it-") } }
        }?.let { return it.optString("outbound") }
        val notOutside = notOutsideTags(root)
        return routing.firstOrNull { it.optString("outbound") !in notOutside }?.optString("outbound")
    }

    private fun ruleSets(rule: JSONObject): List<String> = when (val v = rule.opt("rule_set")) {
        is String -> listOf(v)
        is JSONArray -> (0 until v.length()).map { v.optString(it) }
        else -> emptyList()
    }

    /** Выходы, которые не туннель: прямой и отказ (по типу), плюс их привычные имена. */
    private fun notOutsideTags(root: JSONObject): Set<String> {
        val tags = mutableSetOf("direct", "block")
        val outbounds = root.optJSONArray("outbounds") ?: return tags
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("type") in setOf("direct", "block", "dns")) {
                ob.optString("tag").takeIf { it.isNotEmpty() }?.let { tags += it }
            }
        }
        return tags
    }

    fun log(result: Result) {
        if (result.patched) KLog.i(TAG, result.note) else KLog.w(TAG, result.note)
    }
}
