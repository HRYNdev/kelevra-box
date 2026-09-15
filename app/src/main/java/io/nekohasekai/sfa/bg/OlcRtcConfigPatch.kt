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

    /**
     * С какой версии апстрим СНОСИТ ключ `tun.stack` — источник графика,
     * `experimental/deprecated/constants.go` (sing-box): `OptionTunStack{DeprecatedVersion:
     * "1.15.0", ScheduledVersion: "1.17.0"}`. Ключ deprecated с 1.15.0 (появился свой
     * стек TCP/IP, release notes 1.15.0: "Remove the `stack` option to use it"), но
     * СНОСЯТ его по расписанию только в 1.17.0 — до тех пор он ещё декодируется.
     * Замер нарядом 0915-171106 на реальном v1.15.0-alpha.4 это подтвердил (DECODE OK
     * с ключом в конфиге). Раньше здесь стояло 15 — снимало рабочий ключ на два минора
     * раньше срока.
     */
    private const val STACK_KEY_REMOVED_MAJOR = 1
    private const val STACK_KEY_REMOVED_MINOR = 17

    private val VERSION_RE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** major.minor из строки ядра вида "1.15.0-alpha.3"; null — распознать не вышло. */
    private fun parseMajorMinor(version: String): Pair<Int, Int>? {
        val m = VERSION_RE.find(version) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        return major to minor
    }

    private fun stackKeyRemoved(major: Int, minor: Int): Boolean =
        major > STACK_KEY_REMOVED_MAJOR || (major == STACK_KEY_REMOVED_MAJOR && minor >= STACK_KEY_REMOVED_MINOR)

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
     *
     * С 1.15.0 у sing-box появился свой стек TCP/IP, который эту же беду (своя таблица
     * трансляции портов) не наследует, а ключ `tun.stack` для него — deprecated, но
     * ключ ещё живой и декодируется вплоть до 1.17.0 — апстрим сносит его именно там
     * (`experimental/deprecated/constants.go`, `OptionTunStack.ScheduledVersion`).
     * Поэтому ключ снимаем только с 1.17.0, а на 1.15.0–1.16.x ставим `gvisor`, как и
     * раньше. `coreVersion` — версия ядра из [io.nekohasekai.libbox.Libbox.version];
     * не распознать не смогли — ведём себя как на старом ядре, то есть ставим `gvisor`.
     */
    fun tunnelStack(content: String, coreVersion: String = ""): Result = runCatching {
        val root = JSONObject(content)
        val inbounds = root.optJSONArray("inbounds") ?: return@runCatching Result(content, "в конфиге нет входов", false)
        val mm = parseMajorMinor(coreVersion)
        val stackKeyGone = mm != null && stackKeyRemoved(mm.first, mm.second)
        val versionUnknown = if (mm == null) "версия ядра «$coreVersion» не распознана — " else ""

        var changed = 0
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            if (stackKeyGone) {
                if (!inbound.has("stack")) continue
                inbound.remove("stack")
            } else {
                if (inbound.optString("stack") == STACK) continue
                inbound.put("stack", STACK)
            }
            changed++
        }

        when {
            changed == 0 && stackKeyGone -> Result(content, "ключ «stack» и так снят (ядро $coreVersion — апстрим его уже не читает)", false)
            changed == 0 -> Result(content, "${versionUnknown}стек туннеля и так «$STACK»", false)
            stackKeyGone -> Result(
                root.toString(),
                "ключ «stack» убран из входов (ядро $coreVersion ≥ ${STACK_KEY_REMOVED_MAJOR}.${STACK_KEY_REMOVED_MINOR} — апстрим сносит " +
                    "ключ по расписанию с этой версии, deprecated он с 1.15.0, входов: $changed)",
                true,
            )
            else -> Result(
                root.toString(),
                "${versionUnknown}стек туннеля переведён на «$STACK» (входов: $changed) — своей трансляции портов нет",
                true,
            )
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

    /**
     * Домены разрешённых сервисов, которых нет в снимке белого списка, но без которых
     * сервис из списка не работает: картинки и статика лежат на своих доменах CDN.
     *
     * Лишнее имя здесь дорого: всё под ним пойдёт напрямую, и если оператор его не
     * пропускает, оно умрёт там, где через комнату жило бы. Поэтому у каждого имени есть
     * источник, один из двух:
     *  - разбор ограничения: картинок WB (`wbbasket.ru`, `wbcontent.net`) и Ozon
     *    (`ozonusercontent.com`, `ozone.ru`) в снимке нет, а сами сервисы в нём есть;
     *  - сам снимок: в нём лежат поддомены этого имени (`sun1-13.userapi.com`,
     *    `st.okcdn.ru`, `api.vk.ru`, `login.vk.com`, `gu-st.ru`, `id.sber.ru`,
     *    `cdn.tbank.ru`, `imgproxy.cdn-tinkoff.ru`, `s.vtb.ru`, `ws-api.oneme.ru`),
     *    то есть сервис разрешён, и мы расширяем его до всего домена.
     */
    val DOBAVKI_DOMENOV = listOf(
        "wbbasket.ru", "wbcontent.net", "wb.ru", "wildberries.ru",
        "ozon.ru", "ozone.ru", "ozonusercontent.com",
        "userapi.com", "okcdn.ru", "vk.com", "vk.ru",
        "yastatic.net",
        "gu-st.ru",
        "sber.ru", "tbank.ru", "cdn-tinkoff.ru", "vtb.ru",
        "oneme.ru",
    )

    /**
     * Приложения разрешённых сервисов — напрямую, что бы они ни открывали.
     *
     * Нужны там, где домена нет: приложение ходит по адресу без имени или на домен CDN,
     * которого нет ни в снимке, ни в добавках. Имена пакетов сверены по карточкам RuStore.
     *
     * Браузеров здесь нет сознательно, в том числе «Яндекс Старта»: у браузера любой сайт —
     * «его трафик», и всё вне списка ушло бы напрямую, в стену, вместо комнаты.
     * Встроенные браузеры VK и MAX — та же цена в малом: ссылка наружу из них откроется
     * напрямую. Заблокированное всё равно ловят наборы выше.
     *
     * Пакет на Android ядро узнаёт всегда: при платформенном интерфейсе sing-box ищет
     * владельца соединения безусловно (`route/router.go`, `C.IsAndroid && platformInterface`),
     * а приложение отвечает через `getConnectionOwnerUid` ([PlatformInterfaceWrapper]).
     */
    val PAKETY_RAZRESHYONNYE = listOf(
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.rostel",
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.yandex.taxi",
        "ru.yandex.yandexmaps",
        "com.vkontakte.android",
        "ru.oneme.app",
    )

    /**
     * Включена ли комната по белому списку — то есть нужна ли правка [finalViaRoom].
     *
     * Два признака:
     *  - комнату выбрал человек руками при выключенном автомате — он видит, что без неё
     *    не ходит;
     *  - определитель режима сети недавно сказал «белый список». Вердикт перемеряется раз
     *    в минуту-полторы и забывается при смене сети, так что срок [ttlMillis] его зря
     *    не держит.
     * Комната, поднятая автоматом по другой причине (заблокирован только узел основного
     * канала), конфиг не трогает: там `final = direct` верен, напрямую живо всё.
     *
     * Флажок ручной комнаты при включённом автомате — остаток прошлого выбора, не довод.
     *
     * @param whitelistAgeMillis сколько лет вердикту «белый список»; `null` — вердикта нет.
     */
    fun wantsFinalViaRoom(
        autoMode: Boolean,
        manualRoom: Boolean,
        whitelistAgeMillis: Long?,
        ttlMillis: Long,
    ): Boolean = when {
        !autoMode && manualRoom -> true
        whitelistAgeMillis == null -> false
        else -> whitelistAgeMillis in 0 until ttlMillis
    }

    /**
     * Под белым списком: разрешённое оператором — напрямую, остальное — через комнату.
     *
     * Сервер отдаёт `route.final = direct`. Под белым списком это значит, что всё вне
     * наборов умирает напрямую. Но и «всё вне наборов — в комнату» неверно: Озон, WB, банки
     * оператор пропускает сам, и гнать их в видеозвонок на пару мегабит незачем.
     *
     * Белый список режет в два слоя: по адресу (пакеты к адресу вне списка пропадают) и у
     * части операторов по имени в TLS (чужое имя на разрешённом адресе виснет после первых
     * килобайт). Поэтому «разрешённое» здесь — прежде всего имя, а не адрес.
     *
     * Правила дописываются В КОНЕЦ, после всего, что в конфиге уже есть: перехвата DNS,
     * частных адресов, рекламы, наборов с заблокированным (они и так ведут в селектор с
     * комнатой), страховки ([LifelinePatch]). Порядок:
     *  1. Отказ по UDP/443, если его нет выше, — до разрешённого: UDP под белым списком
     *     режется почти весь, и QUIC к разрешённому сайту висел бы вместо перехода на TCP.
     *  2. Домены белого списка и [DOBAVKI_DOMENOV] — напрямую, `domain_suffix`.
     *  3. Приложения [PAKETY_RAZRESHYONNYE] — напрямую, отдельным правилом: поля внутри
     *     одного правила sing-box складывает через «И», и вместе с доменами правило ловило
     *     бы только их пересечение.
     *  4. Прочий UDP — отказ. Комната возит только TCP, оператор этот UDP режет, а
     *     разрешённое по имени и приложению уже ушло правилами выше. Отказ даёт
     *     приложению сразу перейти на TCP вместо молчаливого таймаута.
     *  5. `final` — в селектор, где лежит комната. Именно в селектор: когда комната умирает,
     *     [AutoMode.roomLost] переключает селектор на основной канал без пересборки ядра, и
     *     остаток уходит вместе с ним. Разрешённое от селектора не зависит вовсе —
     *     правила 2 и 3 ведут в прямой выход, так что за границу оно не уйдёт.
     *
     *  3а. Живые подсети [podseti] — напрямую, но только для TCP-соединений БЕЗ имени
     *     (приложение ходит по адресу): логическое `and` из `ip_cidr` и
     *     `domain_regex: [".*"]` с `invert` — элемент домена на пустом имени отвечает «нет».
     *     Соединение с чужим именем на разрешённом адресе сюда не попадает: у оператора
     *     с фильтром по имени оно всё равно умрёт, и ему место в комнате. Набор — не
     *     раздутый снимок (~30 тыс. записей на десятки млн адресов), а /24 из замеров
     *     живых адресов через симку (`assets/belyj-spisok/podseti.txt`, ~600 записей).
     *     Пустой набор — правила нет.
     *
     * Только в памяти и только пока комната стоит. Повторная правка ничего не меняет.
     */
    fun finalViaRoom(
        content: String,
        socksPort: Int,
        domeny: Collection<String>,
        podseti: Collection<String> = emptyList(),
    ): Result =
        runCatching { patchFinal(content, socksPort, domeny, podseti) }.getOrElse {
            Result(content, "белый список: правка маршрутов не легла (${it.javaClass.simpleName}), конфиг как есть", false)
        }

    /**
     * Снимок плюс добавки, без повторов и без имён, которые уже накрыты родителем:
     * `domain_suffix: ozon.ru` ловит и `adv.ozon.ru`.
     */
    internal fun razreshyonnyeDomeny(izSpiska: Collection<String>): List<String> {
        val all = (izSpiska.asSequence() + DOBAVKI_DOMENOV.asSequence())
            .map { it.trim().trim('.').lowercase() }
            .filter { it.contains('.') }
            .toCollection(LinkedHashSet())
        return all.filter { host ->
            val labels = host.split('.')
            (1 until labels.size - 1).none { labels.subList(it, labels.size).joinToString(".") in all }
        }
    }

    private fun patchFinal(content: String, socksPort: Int, domeny: Collection<String>, podseti: Collection<String>): Result {
        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds") ?: return Result(content, "в конфиге нет выходов", false)
        val socks = socksTags(outbounds, socksPort)
        if (socks.isEmpty()) return Result(content, "выхода комнаты в конфиге нет, маршруты не трогаем", false)
        val route = root.optJSONObject("route") ?: return Result(content, "в конфиге нет route", false)
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }

        val byTag = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .associateBy { it.optString("tag") }
        val target = finalTarget(rules, byTag, socks)
        if (route.optString("final") == target && hasPackageRule(rules)) {
            return Result(content, "белый список: правка уже стоит, final ведёт в «$target»", false)
        }
        val direct = byTag.values.firstOrNull { it.optString("type") == "direct" }?.optString("tag")
            ?.takeIf { it.isNotBlank() }
            ?: return Result(content, "прямого выхода в конфиге нет — разрешённое вести некуда, маршруты не трогаем", false)

        val added = mutableListOf<String>()
        if (!hasQuicReject(rules, rules.length())) {
            rules.put(quicRejectRule())
            added += "отказ udp/443"
        }
        val suffixes = razreshyonnyeDomeny(domeny)
        if (suffixes.isNotEmpty()) {
            rules.put(JSONObject().put("outbound", direct).put("domain_suffix", JSONArray(suffixes)))
            added += "домены напрямую (${suffixes.size})"
        }
        rules.put(JSONObject().put("outbound", direct).put("package_name", JSONArray(PAKETY_RAZRESHYONNYE)))
        added += "приложения напрямую (${PAKETY_RAZRESHYONNYE.size})"
        val cidr = podseti.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cidr.isNotEmpty()) {
            rules.put(podsetiBezImeniRule(direct, cidr))
            added += "живые подсети без имени напрямую (${cidr.size})"
        }
        rules.put(JSONObject().put("action", "reject").put("network", "udp"))
        added += "прочий UDP — отказ"

        val was = route.optString("final").ifBlank { "не задан" }
        route.put("final", target)
        return Result(
            root.toString(),
            "белый список: ${added.joinToString("; ")}; final: «$was» → «$target»",
            true,
        )
    }

    /** TCP к живой подсети и без имени — напрямую; см. пункт 3а у [finalViaRoom]. */
    internal fun podsetiBezImeniRule(direct: String, cidr: List<String>): JSONObject = JSONObject()
        .put("type", "logical")
        .put("mode", "and")
        .put(
            "rules",
            JSONArray()
                .put(JSONObject().put("network", "tcp").put("ip_cidr", JSONArray(cidr)))
                .put(JSONObject().put("domain_regex", JSONArray(listOf(".*"))).put("invert", true)),
        )
        .put("outbound", direct)

    private fun hasPackageRule(rules: JSONArray): Boolean = (0 until rules.length()).any { i ->
        val list = rules.optJSONObject(i)?.optJSONArray("package_name") ?: return@any false
        (0 until list.length()).map { list.optString(it) } == PAKETY_RAZRESHYONNYE
    }

    /** Селектор, в котором лежит сокс комнаты; нет такого — сам сокс. */
    private fun finalTarget(rules: JSONArray, byTag: Map<String, JSONObject>, socks: Set<String>): String {
        fun holdsRoom(tag: String): Boolean {
            val group = byTag[tag] ?: return false
            if (group.optString("type") != "selector") return false
            val members = group.optJSONArray("outbounds") ?: return false
            return (0 until members.length()).any { members.optString(it) in socks }
        }
        // Первым смотрим туда, куда конфиг уже гонит наборы: это и есть группа, которую
        // переключает автомат.
        (0 until rules.length())
            .mapNotNull { rules.optJSONObject(it)?.optString("outbound")?.takeIf(String::isNotBlank) }
            .firstOrNull(::holdsRoom)
            ?.let { return it }
        byTag.keys.firstOrNull { it.isNotBlank() && holdsRoom(it) }?.let { return it }
        return socks.first()
    }

    private fun socksTags(outbounds: JSONArray, socksPort: Int): Set<String> =
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .filter {
                it.optString("type") == "socks" &&
                    it.optString("server") in LOOPBACK &&
                    it.optInt("server_port") == socksPort
            }
            .mapNotNull { it.optString("tag").takeIf(String::isNotBlank) }
            .toCollection(LinkedHashSet())

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
