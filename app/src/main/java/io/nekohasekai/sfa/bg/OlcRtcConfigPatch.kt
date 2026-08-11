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

    /** Стек туннеля без своей таблицы трансляции портов — см. [tunnelStack]. */
    private const val STACK = "gvisor"

    /** Сколько ждём первые байты на распознавании протокола — см. [sniffTimeout]. */
    private const val SNIFF_TIMEOUT = "300ms"

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

    /**
     * Пока идём через комнату — весь путь только по IPv4.
     *
     * SOCKS-сервер комнаты понимает ровно два вида адреса: IPv4 и доменное имя
     * (olcrtc, `internal/client/client.go`, `readSocks5Addr`) — IPv6 он отвергает.
     * Отсюда две правки:
     *
     *  1. Подменные адреса раздаём только IPv4. Приложение, которое ходит по адресам
     *     без имён, иначе получает IPv6 и молча умирает.
     *  2. У туннеля снимаем адрес IPv6. Замер сокетов телеграма 11.08.2026: он держал
     *     соединение к своему дата-центру по `2001:67c:4e8::…` на порт 5222 МИМО
     *     туннеля — пока у туннеля есть адрес IPv6, система считает, что IPv6 живёт
     *     сам по себе. Без него приложение идёт по IPv4, который комната умеет.
     *
     * Распознавание протокола (`sniff`) здесь НЕ трогается, и это важно. 11.08.2026 я
     * снял его целиком, решив, что ядро ждёт первых байт, — и получил обратное:
     * из туннеля домен взять стало неоткуда, правило с доменными наборами не
     * досчитывалось никогда, и соединения зависали навсегда, не дойдя до комнаты
     * (263 тысячи за день, из них 70 тысяч — лавина повторов телеграма). Трафик через
     * локальный прокси, где домен известен без распознавания, при этом проходил.
     */
    fun onlyIpv4(content: String): Result = runCatching { patchIpv4(content) }.getOrElse {
        Result(content, "правка про IPv4 не легла (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    /**
     * Сетевой стек туннеля — без своей трансляции портов.
     *
     * Стек `mixed` ведёт TCP через системную часть, а та держит свою таблицу трансляции
     * портов. Телеграм открывает соединения пачками — таблица кончается, и дальше ядро
     * не создаёт НИ ОДНОГО нового соединения: в журнале у них есть строка «нашёл
     * приложение» и больше ничего, ни маршрута, ни ошибки, ни таймаута. Приложение
     * при этом висит на «Соединение…» и долбит новыми попытками, отчего таблица не
     * освобождается никогда.
     *
     * Замер на телефоне 11.08.2026: `ipv4: tcp: NAT port space exhausted` — 220 раз
     * за минуту, 240 МБ журнала, ноль соединений телеграма до выхода. Стена одинаковая
     * и через комнату, и через основной канал, и от распознавания протокола не зависит.
     *
     * Стек `gvisor` своей трансляции не ведёт — он терминирует соединение сам, поэтому
     * кончаться там нечему.
     *
     * Правка живёт и в шаблоне на сервере, но профиль у людей закэширован, а обновляется
     * он не сразу. Клиент чинит это у себя, чтобы не ждать.
     */
    /**
     * Ограничение ожидания на распознавании протокола.
     *
     * Распознаватель ждёт первые байты соединения, чтобы вытащить имя. Приложения,
     * которые молчат до ответа сервера, ждут вместе с ним — и если предела нет, ждут
     * бесконечно. Телеграм ведёт себя ровно так: 11.08.2026 он висел на «Соединение…»
     * и с комнатой, и через основной канал, а дома с погашенным туннелем работал.
     * Отсюда же вчерашнее наблюдение, ради которого я снял распознавание целиком:
     * без него телеграм оживал, но ломались доменные правила — имя брать стало неоткуда.
     *
     * Верно не «снять», а «не ждать вечно»: за [SNIFF_TIMEOUT] TLS и HTTP успевают
     * представиться всегда, а молчащее соединение уходит дальше по адресу.
     */
    fun sniffTimeout(content: String): Result = runCatching {
        val root = JSONObject(content)
        val rules = root.optJSONObject("route")?.optJSONArray("rules")
            ?: return@runCatching Result(content, "в конфиге нет правил маршрутизации", false)
        var changed = 0
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("action") != "sniff") continue
            if (rule.has("timeout")) continue
            rule.put("timeout", SNIFF_TIMEOUT)
            changed++
        }
        if (changed == 0) {
            Result(content, "распознавание протокола и так с пределом ожидания", false)
        } else {
            Result(root.toString(), "распознавание протокола ждёт не дольше $SNIFF_TIMEOUT (правил: $changed)", true)
        }
    }.getOrElse {
        Result(content, "предел ожидания распознавания не встал (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    fun tunnelStack(content: String): Result = runCatching {
        val root = JSONObject(content)
        val inbounds = root.optJSONArray("inbounds") ?: return@runCatching Result(content, "в конфиге нет входов", false)
        var changed = 0
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            if (inbound.optString("stack") == STACK) continue
            inbound.put("stack", STACK)
            changed++
        }
        if (changed == 0) {
            Result(content, "стек туннеля и так «$STACK»", false)
        } else {
            Result(root.toString(), "стек туннеля переведён на «$STACK» (входов: $changed) — своей трансляции портов нет", true)
        }
    }.getOrElse {
        Result(content, "стек туннеля поправить не вышло (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    private fun patchIpv4(content: String): Result {
        val root = JSONObject(content)

        var ranges = 0
        val servers = root.optJSONObject("dns")?.optJSONArray("servers")
        for (i in 0 until (servers?.length() ?: 0)) {
            val server = servers?.optJSONObject(i) ?: continue
            if (server.optString("type") != "fakeip") continue
            if (server.has("inet6_range")) {
                server.remove("inet6_range")
                ranges++
            }
        }

        var addresses = 0
        val inbounds = root.optJSONArray("inbounds")
        for (i in 0 until (inbounds?.length() ?: 0)) {
            val inbound = inbounds?.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            val list = inbound.optJSONArray("address") ?: continue
            val kept = JSONArray()
            for (a in 0 until list.length()) {
                val value = list.optString(a)
                if (value.contains(':')) addresses++ else kept.put(value)
            }
            inbound.put("address", kept)
        }

        if (ranges == 0 && addresses == 0) {
            return Result(content, "комната: IPv6 в конфиге и так нет", false)
        }
        return Result(
            root.toString(),
            "комната: идём только по IPv4 (подменных диапазонов снято $ranges, адресов туннеля $addresses)",
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
