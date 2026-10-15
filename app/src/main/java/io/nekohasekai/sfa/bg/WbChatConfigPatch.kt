package io.nekohasekai.sfa.bg

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Правка профиля sing-box под постоянно работающее ядро WB Chat: его собственный трафик
 * не должен уходить в собственный же socks-выход.
 *
 * Ядро — дочерний процесс под нашим uid. protect(fd) у чужого процесса не позвать, а
 * вывода приложения из tun ([TunnelFacts.selfOutsideTun]) вне комнаты нет — и ядро WB Chat,
 * в отличие от Телемоста, живёт и вне комнаты. Значит его сокеты (UDP на TURN WB, HTTPS/WSS
 * на chat.wb.ru) идут в наш tun, и дальше их ведут правила маршрута. Пока всё, что не
 * поймали правила, уходит в `final: direct`, петли нет; но стоит wb.ru попасть в набор,
 * который ведёт в селектор, или `final` уйти в селектор, а селектор стоять на выходе
 * WB Chat — ядро понесёт собственный звонок внутрь себя.
 *
 * Поэтому одно правило первым среди маршрутных: НАШ пакет к доменам WB и подсети TURN —
 * в `direct`. Условие по пакету держит правку узкой: чужие приложения к wb.ru ходят как
 * раньше. В режиме комнаты приложение и так выведено из tun, и правило просто не срабатывает,
 * поэтому поведение Телемоста и olcRTC оно не меняет.
 */
object WbChatConfigPatch {
    private const val TAG = "WbChatCore"

    /** Сигналинг и API звонков: chat.wb.ru (и всё на wb.ru — TURN бывает именем). */
    val DOMAIN_SUFFIXES = listOf("wb.ru")

    /** TURN WB Chat (176.101.92.167:3478) и его соседи. */
    val SUBNETS = listOf("176.101.92.0/23")

    /**
     * Мимо tun целиком (route_exclude_address, Android 13+): TURN и сигналинг WB (chat.wb.ru —
     * 185.62.202.0/24 и 2a03:720::/32, AS57073). Замер 15.10 на телефоне Вовы: при выборе выхода
     * ядро sing-box пересобирается, рвёт всё, что шло через tun, и звонок WB Chat падает
     * (realtime reset через 10 с после выбора). Вне tun пересборка его не касается.
     */
    val TUN_EXCLUDE = listOf("176.101.92.0/23", "185.62.202.0/24", "2a03:720::/32")

    /** Правила-действия, которые обязаны идти раньше маршрутных: распознавание и перехват DNS. */
    private val LEADING_ACTIONS = setOf("sniff", "hijack-dns", "resolve")

    data class Result(val content: String, val note: String, val patched: Boolean)

    fun directForSelf(content: String, packageName: String): Result =
        runCatching { patch(content, packageName) }.getOrElse {
            Result(content, "правило direct для ядра WB Chat не легло (${it.javaClass.simpleName}), конфиг как есть", false)
        }

    private fun patch(content: String, packageName: String): Result {
        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds") ?: return Result(content, "в конфиге нет outbounds", false)
        val direct = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "direct" }
            ?.optString("tag")?.takeIf { it.isNotBlank() }
            ?: return Result(content, "в конфиге нет выхода direct — правило для ядра WB Chat не ставлю", false)
        val route = root.optJSONObject("route") ?: return Result(content, "в конфиге нет route", false)
        val rules = route.optJSONArray("rules") ?: JSONArray()

        val rule = rule(packageName, direct)
        if ((0 until rules.length()).any { rules.optJSONObject(it)?.toString() == rule.toString() }) {
            return Result(content, "правило direct для ядра WB Chat уже есть", false)
        }

        var insertAt = 0
        while (insertAt < rules.length()) {
            val r = rules.optJSONObject(insertAt) ?: break
            if (r.optString("action") !in LEADING_ACTIONS) break
            insertAt++
        }

        val patched = JSONArray()
        for (i in 0 until rules.length()) {
            if (i == insertAt) patched.put(rule)
            patched.put(rules.opt(i))
        }
        if (insertAt >= rules.length()) patched.put(rule)
        route.put("rules", patched)
        val excluded = excludeFromTun(root)
        return Result(root.toString(), "свой трафик ядра WB Chat (wb.ru, TURN) — в «$direct», правило на позиции $insertAt; мимо tun: $excluded", true)
    }

    /** Добавляет [TUN_EXCLUDE] в route_exclude_address каждого tun-входа; возвращает, сколько tun правлено. */
    private fun excludeFromTun(root: JSONObject): Int {
        val inbounds = root.optJSONArray("inbounds") ?: return 0
        var n = 0
        for (i in 0 until inbounds.length()) {
            val tun = inbounds.optJSONObject(i)?.takeIf { it.optString("type") == "tun" } ?: continue
            val list = tun.optJSONArray("route_exclude_address") ?: JSONArray()
            val have = (0 until list.length()).map { list.optString(it) }.toSet()
            TUN_EXCLUDE.filter { it !in have }.forEach { list.put(it) }
            tun.put("route_exclude_address", list)
            n++
        }
        return n
    }

    /**
     * В одном правиле домены и подсети складываются через «ИЛИ», а пакет — через «И»
     * (так sing-box сочетает поля правила), то есть: наш пакет И (wb.ru ИЛИ подсеть TURN).
     */
    private fun rule(packageName: String, direct: String): JSONObject = JSONObject()
        .put("package_name", JSONArray().put(packageName))
        .put("domain_suffix", JSONArray().apply { DOMAIN_SUFFIXES.forEach { put(it) } })
        .put("ip_cidr", JSONArray().apply { SUBNETS.forEach { put(it) } })
        .put("action", "route")
        .put("outbound", direct)

    fun log(result: Result) {
        Log.i(TAG, "WB Chat: ${result.note}")
    }
}
