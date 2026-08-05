package io.nekohasekai.sfa.bg

import org.json.JSONObject

/**
 * Разбор конфига: какие выходы в нём есть и чем их переключать.
 *
 * Автомату нужны четыре вещи, и все четыре лежат в конфиге, который отдал сервер:
 *  - каким селектором переключается выход (у нас «Соединение»),
 *  - как называется выход комнаты внутри него,
 *  - как называется основной выход и по каким адресам он реально ходит,
 *  - куда постучаться, чтобы проверить весь путь целиком — локальный вход самого
 *    sing-box, тот же, что конфиг подставляет системе через `platform.http_proxy`.
 *
 * Ничего не зашито именами: сервер завтра назовёт выход иначе, и это не должно
 * ломать автомат. Комната опознаётся по конструкции — socks на петле с тем же
 * портом, который поднимает ядро olcRTC; селектор — по тому, что комната лежит
 * внутри него.
 */
object AutoModeExits {
    /** Ядро olcRTC слушает SOCKS только на петле, по этому адресу и опознаём комнату. */
    private val LOOPBACK = setOf("127.0.0.1", "::1", "localhost")

    /** Входы, через которые можно попросить sing-box сходить наружу его же маршрутами. */
    private val PROXY_INBOUNDS = setOf("mixed", "socks")

    /** Куда реально идёт TCP. Нужен, чтобы спросить сеть «а этот адрес вообще достижим». */
    data class Endpoint(val host: String, val port: Int)

    data class Layout(
        /** Селектор, которым переключается выход. */
        val chooser: String?,
        /** Выход комнаты внутри селектора. */
        val room: String?,
        /** Основной выход внутри того же селектора. */
        val main: String?,
        /** Адреса узлов основного выхода. */
        val mainEndpoints: List<Endpoint>,
        /**
         * Локальный вход самого sing-box. Через него проверяется, что канал реально
         * несёт трафик, а не только принимает соединения. `null` — в конфиге такого
         * входа нет, честная проба недоступна.
         */
        val localProxy: Endpoint? = null,
    ) {
        /** Есть ли вообще между чем переключать. */
        val canSwitch: Boolean get() = chooser != null && room != null && main != null

        companion object {
            val EMPTY = Layout(null, null, null, emptyList(), null)
        }
    }

    /**
     * @param socksPort порт, на котором ядро olcRTC поднимает свой SOCKS5.
     * @return разложенные выходы; при любой неожиданности — [Layout.EMPTY],
     *   потому что автомат без раскладки просто не переключает, а не падает.
     */
    fun parse(content: String, socksPort: Int): Layout = runCatching { parse0(content, socksPort) }
        .getOrElse { Layout.EMPTY }

    private fun parse0(content: String, socksPort: Int): Layout {
        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds") ?: return Layout.EMPTY

        val byTag = LinkedHashMap<String, JSONObject>()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val tag = outbound.optString("tag").takeIf { it.isNotBlank() } ?: continue
            byTag[tag] = outbound
        }

        val room = byTag.entries.firstOrNull { (_, outbound) ->
            outbound.optString("type") == "socks" &&
                outbound.optString("server") in LOOPBACK &&
                outbound.optInt("server_port") == socksPort
        }?.key

        // Переключаем тем селектором, внутри которого лежит комната. Комнаты в конфиге
        // нет (сервер её не выдал) — берём первый селектор, чтобы автомат хотя бы
        // возвращал выбор на основной выход после ручного вмешательства.
        val chooser = byTag.entries.firstOrNull { (_, outbound) ->
            outbound.optString("type") == "selector" && room != null && room in members(outbound)
        }?.key ?: byTag.entries.firstOrNull { it.value.optString("type") == "selector" }?.key

        val main = chooser?.let { tag -> members(byTag.getValue(tag)).firstOrNull { it != room } }

        val endpoints = endpointsOf(main, byTag, mutableSetOf())
            .ifEmpty { allEndpoints(byTag) }

        return Layout(
            chooser = chooser,
            room = room,
            main = main,
            mainEndpoints = endpoints,
            localProxy = localProxy(root, socksPort),
        )
    }

    /**
     * Куда стучаться честной пробой.
     *
     * Первым берём то, что конфиг сам объявил системным прокси для tun: этот вход
     * заведомо рабочий, потому что через него платформа гонит трафик всех приложений.
     * Если такого объявления нет, годится любой локальный `mixed`/`socks` вход —
     * кроме входа самой комнаты: он ведёт не наружу, а в другое ядро.
     */
    private fun localProxy(root: JSONObject, socksPort: Int): Endpoint? {
        val inbounds = root.optJSONArray("inbounds") ?: return null

        for (i in 0 until inbounds.length()) {
            val proxy = inbounds.optJSONObject(i)?.optJSONObject("platform")
                ?.optJSONObject("http_proxy") ?: continue
            if (!proxy.optBoolean("enabled")) continue
            val port = proxy.optInt("server_port").takeIf { it in 1..65535 } ?: continue
            return Endpoint(proxy.optString("server").ifBlank { "127.0.0.1" }, port)
        }

        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") !in PROXY_INBOUNDS) continue
            // Вход с паролем пробе не годится: авторизации у неё нет.
            if ((inbound.optJSONArray("users")?.length() ?: 0) > 0) continue
            val listen = inbound.optString("listen").takeIf { it.isNotBlank() } ?: "127.0.0.1"
            if (listen !in LOOPBACK) continue
            val port = inbound.optInt("listen_port").takeIf { it in 1..65535 } ?: continue
            if (port == socksPort) continue
            return Endpoint(listen, port)
        }
        return null
    }

    private fun members(outbound: JSONObject): List<String> {
        val list = outbound.optJSONArray("outbounds") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /**
     * Адреса, до которых ходит выход [tag]. Группы разворачиваются вглубь,
     * [seen] держит защиту от кольца (селектор может ссылаться сам на себя через группу).
     */
    private fun endpointsOf(tag: String?, byTag: Map<String, JSONObject>, seen: MutableSet<String>): List<Endpoint> {
        val name = tag ?: return emptyList()
        if (!seen.add(name)) return emptyList()
        val outbound = byTag[name] ?: return emptyList()
        val nested = members(outbound)
        if (nested.isNotEmpty()) return nested.flatMap { endpointsOf(it, byTag, seen) }
        return listOfNotNull(endpointOf(outbound))
    }

    /** Запасной вариант: раскладку не поняли — стучимся во все чужие адреса из конфига. */
    private fun allEndpoints(byTag: Map<String, JSONObject>): List<Endpoint> =
        byTag.values.mapNotNull { endpointOf(it) }.distinct()

    private fun endpointOf(outbound: JSONObject): Endpoint? {
        val server = outbound.optString("server").takeIf { it.isNotBlank() } ?: return null
        if (server in LOOPBACK) return null
        val port = outbound.optInt("server_port").takeIf { it in 1..65535 } ?: return null
        return Endpoint(server, port)
    }
}
