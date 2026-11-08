package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Узлы своей сети — без DNS: резолвер выхода узла — hosts-сервер профиля.
 *
 * Зачем. Сервер кладёт в профиль hosts-сервер `svoi-imena` с адресами своих имён (узлы,
 * сервер подписки) и правило `dns.rules` «эти имена → svoi-imena». Но само ядро при наборе
 * узла этим правилом не пользуется: у выходов узлов своего `domain_resolver` нет, и набор
 * берёт `route.default_domain_resolver` с явным транспортом — мимо `dns.rules` (sing-box
 * 1.14.1: `common/dialer/dialer.go:110-115`, `dns/router.go:1291-1296`). На телефоне это
 * `local` → [LocalResolver] → резолвер оператора на сети по умолчанию. То есть имя узла
 * спрашивалось у сети каждый раз, когда ядро открывало к нему соединение: после смены сети
 * (пока сеть по умолчанию не встала, `missing default interface`), под DPI на DNS, при
 * подмене ответов. На Windows тот же путь давал полторы минуты «lookup …: deadline exceeded»
 * после смены Wi-Fi; там это лечится тем же приёмом.
 *
 * Что делаем. Каждому выходу, у которого `server` — имя, а для этого имени в hosts-сервере
 * профиля есть годный адрес, ставим `"domain_resolver": "<тег hosts>"`. Сервер hosts
 * отвечает из памяти (`dns/transport/hosts/hosts.go`), без сети. Адреса — «одна истина»
 * сервера и приезжают с профилем.
 *
 * Не трогаем: выход со своим `domain_resolver` (решение сервера), выход с `detour` (имя
 * тогда разрешает посредник), группы и direct/block/dns, `endpoints` («Дом»). Имени нет в
 * hosts или hosts-сервера нет вовсе — выход как был: профили без `svoi-imena` не ломаются.
 * `route.default_domain_resolver` (для direct) не меняем.
 *
 * Только в памяти, как остальные правки ([BoxService.effectiveConfig]).
 */
object UzlyBezDnsPatch {
    private const val TAG = "BoxService"

    /** Тег hosts-сервера своих имён в профиле. */
    const val TEG_SVOIH_IMEN = "svoi-imena"

    /** Выходы без своего набора до сервера. */
    private val NE_UZEL = setOf("direct", "block", "dns", "selector", "urltest")

    data class Result(val content: String, val note: String, val patched: Boolean, val count: Int = 0)

    fun apply(content: String): Result = runCatching { patch(content) }.getOrElse {
        // Конфиг приходит с сервера и меняется. Уронить старт из-за неудачной правки хуже,
        // чем оставить узлы на резолвере по умолчанию, как было.
        Result(content, "узлы без DNS: правка не легла (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    private fun patch(content: String): Result {
        val root = JSONObject(content)
        val imena = imenaHosts(root)
        if (imena.isEmpty()) return Result(content, "узлы без DNS: в профиле нет hosts-сервера с адресами — резолвер узлов прежний", false)
        val outbounds = root.optJSONArray("outbounds")
            ?: return Result(content, "узлы без DNS: выходов нет", false)
        var n = 0
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("type").lowercase(Locale.ROOT) in NE_UZEL) continue
            if (ob.has("domain_resolver")) continue
            if (ob.optString("detour").isNotEmpty()) continue
            val imya = kanon(ob.optString("server"))
            if (imya.isEmpty() || adres(imya)) continue
            val teg = imena[imya] ?: continue
            ob.put("domain_resolver", teg)
            n++
        }
        if (n == 0) return Result(content, "узлы без DNS: ни одного имени узла нет в hosts профиля — резолвер узлов прежний", false)
        return Result(root.toString(), "узлов без DNS $n: имена узлов разрешает hosts профиля, не сеть", true, n)
    }

    /**
     * Имя → тег hosts-сервера, у которого для имени есть хоть один годный адрес. Несколько
     * hosts-серверов знают одно имя — берётся [TEG_SVOIH_IMEN], иначе первый по порядку.
     */
    internal fun imenaHosts(root: JSONObject): Map<String, String> {
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return emptyMap()
        val itog = LinkedHashMap<String, String>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("type") != "hosts") continue
            val teg = s.optString("tag").takeIf { it.isNotEmpty() } ?: continue
            val predefined = s.optJSONObject("predefined") ?: continue
            for (key in predefined.keys()) {
                val k = kanon(key)
                if (k.isEmpty() || godnyeAdresa(predefined.opt(key)).isEmpty()) continue
                val byl = itog[k]
                if (byl == TEG_SVOIH_IMEN) continue
                if (byl != null && teg != TEG_SVOIH_IMEN) continue
                itog[k] = teg
            }
        }
        return itog
    }

    /**
     * Имя → адреса из hosts-серверов профиля (предпочтительно [TEG_SVOIH_IMEN]). Нужно
     * автомату: стучаться в узел по адресу из профиля, а не спрашивать имя у сети.
     */
    fun adresaImen(content: String): Map<String, List<String>> =
        runCatching { adresaImen(JSONObject(content)) }.getOrElse { emptyMap() }

    internal fun adresaImen(root: JSONObject): Map<String, List<String>> = runCatching {
        val tegi = imenaHosts(root)
        if (tegi.isEmpty()) return@runCatching emptyMap()
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return@runCatching emptyMap()
        val poTegu = HashMap<String, JSONObject>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("type") == "hosts") poTegu.putIfAbsent(s.optString("tag"), s)
        }
        val itog = LinkedHashMap<String, List<String>>()
        for ((imya, teg) in tegi) {
            val predefined = poTegu[teg]?.optJSONObject("predefined") ?: continue
            val key = predefined.keys().asSequence().firstOrNull { kanon(it) == imya } ?: continue
            val adresa = godnyeAdresa(predefined.opt(key))
            if (adresa.isNotEmpty()) itog[imya] = adresa
        }
        itog
    }.getOrElse { emptyMap() }

    /** Годные адреса из значения predefined (строка или список): разбираемые и ненулевые. */
    private fun godnyeAdresa(v: Any?): List<String> {
        val spisok = when (v) {
            is String -> listOf(v)
            is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? String }
            else -> emptyList()
        }
        return spisok.map { it.trim() }.filter { a -> adres(a) && !nulevoy(a) }
    }

    /** Литерал IPv4/IPv6 — без обращения к DNS (InetAddress тут нельзя: он резолвит имена). */
    internal fun adres(s: String): Boolean = IPV4.matches(s) && s.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 } ||
        (s.contains(':') && IPV6.matches(s))

    private fun nulevoy(s: String): Boolean = s == "0.0.0.0" || s.replace("0", "").replace(":", "").isEmpty()

    internal fun kanon(s: String): String = s.trim().removeSuffix(".").lowercase(Locale.ROOT)

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:.]+$""")

    fun log(result: Result) {
        KLog.i(TAG, result.note)
    }
}
