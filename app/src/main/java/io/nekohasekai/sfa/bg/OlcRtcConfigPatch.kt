package io.nekohasekai.sfa.bg

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Правка конфига sing-box под выход через комнату.
 *
 * Через комнату ходит только TCP: ядро olcrtc отдаёт SOCKS5, а SOCKS5 UDP-ASSOCIATE
 * там не реализован. Поэтому QUIC (UDP/443) в комнату уходит и умирает —
 * в логе `outbound packet connection` → `connection reset by peer`, а браузер
 * висит белым экраном, потому что честного отказа он не получает.
 *
 * Лечится правилом `{"action":"reject","network":"udp","port":443}`: браузер видит
 * отказ сразу и падает на TCP. Держать это правило в шаблоне на сервере нельзя —
 * оно порежет QUIC и на обычном канале, где UDP работает нормально. Значит правку
 * делает клиент и только когда комната включена; выключена — конфиг не трогается.
 *
 * Куда вставляем: перед первым правилом, которое уводит трафик в комнату.
 * Тогда всё, что маршрутизируется мимо комнаты (частные адреса, прямые правила
 * выше по списку), сохраняет QUIC как раньше. Если в комнату уходит `final`,
 * правило добавляется в конец — до `final` доходит только то, что не поймали правила.
 */
object OlcRtcConfigPatch {
    private const val TAG = "OlcRtcCore"

    /** Ядро слушает SOCKS только на петле, по этому адресу и опознаём выход комнаты. */
    private val LOOPBACK = setOf("127.0.0.1", "::1", "localhost")

    private const val QUIC_PORT = 443

    /** Что получилось: сам конфиг и человекочитаемое объяснение для лога. */
    data class Result(val content: String, val note: String, val patched: Boolean)

    fun addQuicReject(content: String, socksPort: Int): Result {
        return runCatching { patch(content, socksPort) }.getOrElse {
            // Конфиг чужой и меняется на сервере: сломать старт из-за неудачной правки хуже,
            // чем оставить QUIC как есть.
            Result(content, "правка маршрутов не удалась (${it.javaClass.simpleName}), конфиг оставлен как есть", false)
        }
    }

    private fun patch(content: String, socksPort: Int): Result {
        val root = JSONObject(content)
        val roomTags = roomTags(root, socksPort)
        if (roomTags.isEmpty()) {
            return Result(content, "выхода комнаты в конфиге нет, маршруты не трогаем", false)
        }

        val route = root.optJSONObject("route") ?: return Result(content, "в конфиге нет route", false)
        val rules = route.optJSONArray("rules") ?: JSONArray()

        val firstRoomRule = (0 until rules.length()).firstOrNull { i ->
            rules.optJSONObject(i)?.optString("outbound") in roomTags
        }
        val finalToRoom = route.optString("final") in roomTags
        if (firstRoomRule == null && !finalToRoom) {
            return Result(content, "в комнату ничего не маршрутизируется, маршруты не трогаем", false)
        }

        val insertAt = firstRoomRule ?: rules.length()
        if (hasQuicReject(rules, insertAt)) {
            return Result(content, "reject udp/443 уже есть в конфиге", false)
        }

        val patched = JSONArray()
        for (i in 0 until rules.length()) {
            if (i == insertAt) patched.put(quicRejectRule())
            patched.put(rules.opt(i))
        }
        if (insertAt >= rules.length()) patched.put(quicRejectRule())

        route.put("rules", patched)
        return Result(
            root.toString(),
            "в маршруты добавлен reject udp/443 на позицию $insertAt " +
                "(комната: ${roomTags.joinToString()}, правил стало ${patched.length()})",
            true,
        )
    }

    private fun quicRejectRule(): JSONObject = JSONObject()
        .put("action", "reject")
        .put("network", "udp")
        .put("port", QUIC_PORT)

    /** Правило уже есть, если оно стоит не позже нашей позиции — иначе оно бесполезно. */
    private fun hasQuicReject(rules: JSONArray, insertAt: Int): Boolean =
        (0 until minOf(insertAt + 1, rules.length())).any { i ->
            val rule = rules.optJSONObject(i) ?: return@any false
            rule.optString("action") == "reject" &&
                rule.optString("network") == "udp" &&
                rule.optInt("port") == QUIC_PORT
        }

    /**
     * Теги, которые ведут в комнату: сам socks-выход на петле плюс все группы
     * (selector/urltest), которые его содержат — прямо или через другую группу.
     */
    private fun roomTags(root: JSONObject, socksPort: Int): Set<String> {
        val outbounds = root.optJSONArray("outbounds") ?: return emptySet()
        val tags = mutableSetOf<String>()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("type") != "socks") continue
            if (outbound.optString("server") !in LOOPBACK) continue
            if (outbound.optInt("server_port") != socksPort) continue
            outbound.optString("tag").takeIf { it.isNotBlank() }?.let { tags += it }
        }
        if (tags.isEmpty()) return emptySet()

        // Группы разворачиваем до неподвижной точки: группа может ссылаться на группу.
        var grew = true
        while (grew) {
            grew = false
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val tag = outbound.optString("tag").takeIf { it.isNotBlank() } ?: continue
                if (tag in tags) continue
                val members = outbound.optJSONArray("outbounds") ?: continue
                val hit = (0 until members.length()).any { members.optString(it) in tags }
                if (hit) {
                    tags += tag
                    grew = true
                }
            }
        }
        return tags
    }

    fun log(result: Result) {
        Log.i(TAG, "olcRTC: ${result.note}")
    }
}
