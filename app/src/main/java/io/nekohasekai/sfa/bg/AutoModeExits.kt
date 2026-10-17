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
        /**
         * Пути, которые имеет смысл уметь мерить по отдельности. Список короткий и
         * закрытый: мерим только то, что есть кому запомнить, — почему именно так,
         * см. в [parse0].
         */
        val measurable: List<String> = emptyList(),
        /**
         * Локальные входы, закреплённые за конкретными выходами
         * ([io.nekohasekai.sfa.bg.ProbeInboundPatch]): тег выхода → куда стучаться пробе.
         * Пусто — конфиг без правки, и тогда меряется только «текущий выбор», а не путь.
         */
        val probeEntries: Map<String, Endpoint> = emptyMap(),
        /**
         * Очередь узлов (решение 17.10.2026): группы-узлы в селекторе по порядку, первый —
         * основной, дальше запасные. Порядок задаёт сервер (мозг по группе человека). У
         * профиля с одним узлом здесь один элемент — [main], поведение прежнее.
         */
        val nodes: List<String> = emptyList(),
        /** Адреса каждого узла очереди. */
        val nodeEndpoints: Map<String, List<Endpoint>> = emptyMap(),
        /** Общий вход (смотрит туда же, куда селектор) — на случай, когда у узла нет своего. */
        val sharedProxy: Endpoint? = null,
    ) {
        /** Есть ли вообще между чем переключать. */
        val canSwitch: Boolean get() = chooser != null && room != null && main != null

        /**
         * Меряется ли основной путь сам по себе. false — [localProxy] (если он вообще есть)
         * ведёт туда, куда смотрит селектор, и вердикт про основной канал по нему честен
         * только пока селектор стоит на основном.
         */
        val mainPinned: Boolean get() = main != null && probeEntries[main] != null

        /** Вход, закреплённый за путём [tag]. `null` — путь мерить нечем. */
        fun entryFor(tag: String?): Endpoint? = tag?.let { probeEntries[it] }

        /**
         * Та же раскладка, но основным считается узел [tag] из очереди: его адреса, его
         * закреплённый вход. Весь автомат дальше работает с «текущим основным», не зная,
         * какой он по счёту. Узла нет в очереди — раскладка без изменений.
         */
        fun forNode(tag: String): Layout {
            if (tag !in nodes || tag == main) return this
            return copy(
                main = tag,
                mainEndpoints = nodeEndpoints[tag].orEmpty(),
                localProxy = probeEntries[tag] ?: sharedProxy,
            )
        }

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

        // Основной — первый выход селектора, который не комната и не другой socks на петле
        // (выход WB Chat: его выбирает человек, автомату он не основной канал).
        val main = chooser?.let { tag ->
            members(byTag.getValue(tag)).firstOrNull { it != room && !loopbackSocks(byTag[it]) }
        }

        val endpoints = endpointsOf(main, byTag, mutableSetOf())
            .ifEmpty { allEndpoints(byTag) }

        // Очередь узлов: основной и за ним остальные группы-узлы селектора (urltest — так сервер
        // собирает узел из его способов подключения), в порядке, который задал сервер.
        val nodes = chooser?.let { tag ->
            (listOfNotNull(main) + members(byTag.getValue(tag)).filter {
                it != room && !loopbackSocks(byTag[it]) && byTag[it]?.optString("type") == "urltest"
            }).distinct()
        } ?: listOfNotNull(main)
        val nodeEndpoints = nodes.associateWith { endpointsOf(it, byTag, mutableSetOf()) }

        // Мерить по отдельности имеет смысл ровно те пути, которые есть кому запомнить,
        // а помнит их [io.nekohasekai.sfa.bg.path.PathRegistry], и путей у него три.
        // Комната сюда не попадает — у неё свой закреплённый SOCKS в ядре olcRTC,
        // закреплять её ещё раз незачем. Дом меряется вообще без посредника: дома
        // туннеля нет, и вопрос «уходит ли трафик» задаётся прямо по физической сети
        // ([io.nekohasekai.sfa.bg.path.HonestProbe.measureDirect]). Остаётся основной канал.
        //
        // Прямой выход конфига (`type: direct`) тут был и оказался лишним. Замер до него
        // доходил, стоил времени и запроса наружу, а положить его было некуда: реестр
        // такого пути не знает, и [io.nekohasekai.sfa.bg.BoxService] честно писал
        // «замер потерян» (на стенде, дважды за прогон). Заводить под него путь
        // тоже неправильно: выбрать прямой выход нельзя — его нет в селекторе, — а живой
        // «direct» при мёртвом канале означал бы для реестра «связь есть», то есть ровно
        // ту ложь, ради которой реестр и заводили. Хуже того, докачка наборов правил
        // берёт первый живой вход ([BoxService.refreshRuleSets]) и ушла бы напрямую —
        // туда, где в урезанной сети наш домен и срезан.
        // Узлов в очереди может быть несколько: закреплённый вход нужен каждому, иначе запасной
        // не проверить, не переставив выбор (и не порвав живые соединения).
        val measurable = nodes.filter { it != room }

        val entries = probeEntries(root)

        return Layout(
            chooser = chooser,
            room = room,
            main = main,
            mainEndpoints = endpoints,
            // Закреплённый за основным путём вход честнее общего: он меряет ИМЕННО этот
            // путь и не зависит от того, куда сейчас смотрит селектор. Его нет — остаётся
            // общий вход, как было раньше.
            localProxy = main?.let { entries[it] } ?: localProxy(root, socksPort),
            measurable = measurable,
            probeEntries = entries,
            nodes = nodes,
            nodeEndpoints = nodeEndpoints,
            sharedProxy = localProxy(root, socksPort),
        )
    }

    /**
     * Какие локальные входы к каким выходам привязаны правилами маршрута.
     *
     * Читается из самого конфига, а не из памяти о том, что мы туда клали: конфиг —
     * единственное, что реально исполняет ядро, и если правка не легла, здесь будет пусто.
     * Ровно это и нужно: пусто → мерить нечем → путь не «живой», а «не проверен».
     */
    fun probeEntries(content: String): Map<String, Endpoint> =
        runCatching { probeEntries(JSONObject(content)) }.getOrElse { emptyMap() }

    private fun probeEntries(root: JSONObject): Map<String, Endpoint> {
        val rules = root.optJSONObject("route")?.optJSONArray("rules") ?: return emptyMap()
        val inbounds = root.optJSONArray("inbounds") ?: return emptyMap()

        val listenByTag = HashMap<String, Endpoint>()
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") !in PROXY_INBOUNDS) continue
            if ((inbound.optJSONArray("users")?.length() ?: 0) > 0) continue
            val tag = inbound.optString("tag").takeIf { it.isNotBlank() } ?: continue
            val listen = inbound.optString("listen").takeIf { it.isNotBlank() } ?: continue
            if (listen !in LOOPBACK) continue
            val port = inbound.optInt("listen_port").takeIf { it in 1..65535 } ?: continue
            listenByTag[tag] = Endpoint(listen, port)
        }

        val entries = LinkedHashMap<String, Endpoint>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val exit = rule.optString("outbound").takeIf { it.isNotBlank() } ?: continue
            val inbound = rule.optJSONArray("inbound") ?: continue
            // Привязка честна, только если правило про ОДИН вход и больше ни про что:
            // лишнее условие означает, что через этот вход в выход попадёт не всё.
            if (inbound.length() != 1) continue
            if (rule.keys().asSequence().any { it != "inbound" && it != "outbound" && it != "action" }) continue
            if (rule.has("action") && rule.optString("action") != "route") continue
            val entry = listenByTag[inbound.optString(0)] ?: continue
            if (!entries.containsKey(exit)) entries[exit] = entry
        }
        return entries
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

    private fun loopbackSocks(outbound: JSONObject?): Boolean =
        outbound != null && outbound.optString("type") == "socks" && outbound.optString("server") in LOOPBACK

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
