package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.HTTPClient
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Свой кэш наборов правил.
 *
 * Ядро умеет тянуть наборы само, но делает это в единственный момент, когда сети может
 * не быть вовсе — на старте, — и мимо туннеля. Забираем это себе: качаем ПОСЛЕ того, как
 * связь поднялась, и по тому пути, который только что честно померили живым. Старт ядра
 * от доступности нашего домена больше не зависит ([RuleSetLocalPatch]).
 *
 * Файлы лежат в личной папке приложения (`filesDir/rule-sets`) и переживают перезапуск:
 * в этом весь смысл — второй запуск в урезанной сети должен идти уже с правилами.
 */
object RuleSetCache {
    private const val TAG = "RuleSetCache"

    /** Раз в сутки: наборы меняются редко, а трафик в такой сети дорог. */
    private const val FRESH_FOR = 24L * 60 * 60 * 1000

    /**
     * Короче этого интервал из профиля значит «набор живой»: свои правила (`svoi-*`) сервер
     * отдаёт с `update_interval: 2m`, и правка в панели должна доезжать до телефона за минуты
     * (решение 25.10.2026 «одна истина»). Большие чужие наборы идут с `1h` — для них остаётся
     * суточная свежесть, как раньше: трафик в урезанной сети дорог.
     */
    internal const val SHORT_LIMIT = 60L * 60 * 1000

    /** Чаще минуты живой набор не качаем, что бы ни прислал сервер. */
    internal const val SHORT_FLOOR = 60L * 1000

    /**
     * Между заходами. Ядро пересобирается на каждое включение комнаты, и без этого
     * порога докачка стартовала бы по нескольку раз в минуту.
     */
    private const val RETRY_AFTER = 60L * 1000

    /** Заголовок скачанного набора sing-box: первые три байта файла. */
    private val MAGIC = byteArrayOf(0x53, 0x52, 0x53) // "SRS"

    /** Качать напрямую, без промежуточного socks: годится только там, где сеть не режут. */
    const val DIRECT = 0

    /**
     * Что сейчас с правилами. Читают шторка и главный экран: человек должен видеть
     * «правила ещё не загружены», а не молча жить с бедными маршрутами.
     *
     * @param total сколько наборов просит конфиг, `0` — конфиг ещё не читали.
     * @param ready сколько из них ушло в ядро из кэша.
     */
    data class State(val total: Int = 0, val ready: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var lastAttempt = 0L

    /** Каким путём качали в прошлый раз: по новому пути пробуем сразу, не выжидая порог. */
    @Volatile
    private var lastPort = 0

    private val dir: File
        get() = File(Application.application.filesDir, "rule-sets").also { it.mkdirs() }

    /**
     * Тег набора → файл в кэше. Только то, что реально лежит на диске, непусто и
     * начинается как набор.
     *
     * Одной длины мало: 13.10.2026 один из телефонов жёстко перезагрузился, пока набор
     * докачивался, и файл остался непустым, но испорченным. Ядро падало на нём при каждом
     * старте, а докачка его не трогала — по времени он считался свежим ещё сутки.
     * Непохожий на набор файл выкидываем: тогда набор уходит в ядро удалённым, как
     * прислал сервер, а докачка заберёт его заново.
     */
    fun cached(): Map<String, File> = runCatching {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && it.name.endsWith(SUFFIX) }
            .filter { file ->
                looksLikeAnyRuleSet(file).also { ok ->
                    if (!ok) {
                        val size = file.length()
                        file.delete()
                        KLog.w(TAG, "набор «${file.name.removeSuffix(SUFFIX)}» в кэше испорчен ($size б) — выкинул")
                    }
                }
            }
            .associateBy { it.name.removeSuffix(SUFFIX) }
    }.getOrElse {
        KLog.w(TAG, "кэш наборов не прочитался: ${it.message}")
        emptyMap()
    }

    /** Ядро называет набор, на котором споткнулось, только номером в конфиге. */
    private val BROKEN_SET = Regex("""parse rule-set\[(\d+)]""")

    /**
     * Тег набора, на котором ядро упало при старте, — если это наш файл из кэша.
     *
     * Заголовок проверяет не всё: файл может начинаться верно и быть обрезан дальше.
     * Тогда судит ядро — по номеру из его ошибки находим набор в том самом конфиге,
     * который ему отдали. Чужие наборы (удалённые, не из кэша) не наши: `null`.
     */
    fun brokenCachedTag(config: String, error: String?): String? {
        val index = error?.let { BROKEN_SET.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return runCatching {
            val set = JSONObject(config).optJSONObject("route")?.optJSONArray("rule_set")?.optJSONObject(index)
                ?: return null
            if (set.optString("type") != "local") return null
            if (File(set.optString("path")).parentFile?.absolutePath != dir.absolutePath) return null
            set.optString("tag").ifBlank { null }
        }.getOrNull()
    }

    /** Выкинуть набор из кэша. `true` — файла больше нет. */
    fun drop(tag: String): Boolean {
        val file = File(dir, tag + SUFFIX)
        return !file.exists() || file.delete()
    }

    /** Итог наложенной правки — отсюда берётся строка для человека. */
    fun report(result: RuleSetLocalPatch.Result) {
        if (!result.patched && result.remotes.isEmpty()) return
        _state.value = State(total = result.remotes.size, ready = result.ready.size)
    }

    /**
     * Докачать то, чего не хватает или что протухло.
     *
     * Зовётся ТОЛЬКО когда путь уже померен живым, и идёт через закреплённый за этим
     * путём локальный вход ([ProbeInboundPatch]). Через общий вход было бы нельзя:
     * маршруты ведёт как раз то, чего у нас ещё нет, и наш домен ушёл бы «напрямую» —
     * туда, где его и срезали.
     *
     * Блокирующий: зовут из фонового потока проверки путей.
     */
    @Synchronized
    fun refresh(remotes: List<RuleSetLocalPatch.Remote>, socksPort: Int, reason: String, tikho: Boolean = false) {
        if (remotes.isEmpty()) return
        val now = System.currentTimeMillis()
        // Порог держит только повтор по тому же пути. Появилась НОВАЯ дорога (поднялась
        // комната сразу после неудачи по мёртвому каналу) — пробуем немедленно: ждать
        // минуту в сети, где эта дорога единственная, значит терять её же.
        if (now - lastAttempt < RETRY_AFTER && socksPort == lastPort) return
        lastAttempt = now
        lastPort = socksPort

        val due = remotes.filter { stale(File(dir, it.tag + SUFFIX), now, freshFor(it)) }
        if (due.isEmpty()) {
            if (!tikho) KLog.i(TAG, "наборы свежие, докачивать нечего ($reason)")
            return
        }
        val way = if (socksPort == DIRECT) "напрямую" else "через 127.0.0.1:$socksPort"
        if (!tikho) KLog.i(TAG, "докачиваю наборы: ${due.size} из ${remotes.size} ($reason), $way")

        var ok = 0
        val novye = mutableListOf<String>()
        for (remote in due) {
            when (download(remote, socksPort)) {
                Zagruzka.NOVYY -> { ok++; novye += remote.tag }
                Zagruzka.TOT_ZHE -> ok++
                Zagruzka.NET -> Unit
            }
        }
        val have = cached().filterValues { !isZaglushka(it) }.keys
        _state.value = State(total = remotes.size, ready = remotes.count { it.tag in have })
        // Таймер живых наборов ходит каждые пару минут: в журнал — только когда набор
        // сменился или не скачался, а не каждый тик.
        if (!tikho || novye.isNotEmpty() || ok < due.size) {
            val chto = if (novye.isEmpty()) "" else "; обновлены: ${novye.joinToString(", ")}"
            KLog.i(TAG, "докачано $ok из ${due.size} ($reason, $way)$chto; в кэше ${_state.value.ready} из ${remotes.size}")
        }
    }

    private enum class Zagruzka { NET, TOT_ZHE, NOVYY }

    private const val SUFFIX = ".srs"

    /**
     * Сколько файл набора считается свежим. Живой набор (интервал из профиля короче
     * [SHORT_LIMIT]) — чуть меньше своего интервала: таймер приходит ровно через интервал,
     * и без запаса файл, докачанный секундой позже прошлого тика, пропускал бы следующий тик
     * (вместо 2 минут — 4). Остальные — сутки.
     */
    internal fun freshFor(remote: RuleSetLocalPatch.Remote): Long {
        val every = remote.updateMillis
        if (every <= 0 || every >= SHORT_LIMIT) return FRESH_FOR
        val interval = maxOf(every, SHORT_FLOOR)
        return interval - minOf(30_000L, interval / 4)
    }

    /** Живые наборы: их докачивает свой таймер службы, а не только проверка путей. */
    fun shortLived(remotes: List<RuleSetLocalPatch.Remote>): List<RuleSetLocalPatch.Remote> =
        remotes.filter { it.updateMillis in 1 until SHORT_LIMIT }

    /** Ритм таймера живых наборов: самый короткий интервал, но не чаще [SHORT_FLOOR]. `0` — живых нет. */
    fun shortPeriod(remotes: List<RuleSetLocalPatch.Remote>): Long =
        shortLived(remotes).minOfOrNull { maxOf(it.updateMillis, SHORT_FLOOR) } ?: 0L

    internal fun stale(file: File, now: Long, freshFor: Long = FRESH_FOR): Boolean =
        !file.isFile || file.length() == 0L || now - file.lastModified() > freshFor

    /**
     * Скачивает набор во временный файл и подменяет им старый только целиком.
     *
     * Проверка заголовка тут не придирка, а защита от самого неприятного исхода: в такой
     * сети вместо файла легко приезжает страница-заглушка, и записать её под именем набора
     * означало бы сломать старт ядра НАВСЕГДА — ровно то, от чего уходим. Не похоже на
     * набор — считаем, что не скачали.
     */
    private fun download(remote: RuleSetLocalPatch.Remote, socksPort: Int): Zagruzka {
        val target = File(dir, remote.tag + SUFFIX)
        val tmp = File(dir, remote.tag + SUFFIX + ".tmp")
        return runCatching {
            val client = Libbox.newHTTPClient()
            try {
                client.modernTLS()
                if (socksPort > 0) client.trySocks5(socksPort)
                val request = client.newRequest()
                request.setUserAgent(HTTPClient.userAgent)
                request.setURL(remote.url)
                request.execute().writeTo(tmp.absolutePath)
            } finally {
                runCatching { client.close() }
            }
            if (!looksLikeRuleSet(tmp, remote.format)) {
                val size = tmp.length()
                tmp.delete()
                KLog.w(TAG, "набор «${remote.tag}» скачался непохожим на набор ($size б) — не берём")
                return Zagruzka.NET
            }
            // Живой набор качается каждые пару минут и почти всегда приходит тем же. Тот же
            // файл не подменяем, а только отмечаем свежим: подмена будит ядро (оно следит за
            // папкой и перечитывает набор), а перечитывать нечего.
            if (target.isFile && sameBytes(tmp, target)) {
                tmp.delete()
                target.setLastModified(System.currentTimeMillis())
                return Zagruzka.TOT_ZHE
            }
            // Сначала содержимое на диск, потом имя. Без этого переименование могло дойти
            // до флеша раньше данных, и жёсткая перезагрузка оставляла под именем набора
            // мусор (13.10.2026).
            FileOutputStream(tmp, true).use { it.fd.sync() }
            // БЕЗ target.delete() перед переименованием: rename на одной файловой
            // системе атомарен и старый файл заменяет сам, а delete открывал окно, в
            // котором набора нет вовсе — под работающим ядром, которое следит за папкой.
            if (!tmp.renameTo(target)) {
                tmp.delete()
                KLog.w(TAG, "набор «${remote.tag}» не переименовался в кэше")
                return Zagruzka.NET
            }
            Zagruzka.NOVYY
        }.getOrElse {
            tmp.delete()
            KLog.w(TAG, "набор «${remote.tag}» не скачался: ${it.message}")
            Zagruzka.NET
        }
    }

    /** Домен-метка заглушки: ничему не равен (зона `.invalid`), по нему заглушку и узнаём. */
    internal const val ZAGLUSHKA_DOMEN = "kelevra-zaglushka.invalid"

    private val ZAGLUSHKA_TEKST = """{"version":2,"rules":[{"domain_suffix":["$ZAGLUSHKA_DOMEN"]}]}"""

    /**
     * Локальная заглушка текстового набора, которого ещё нет в кэше ([RuleSetLocalPatch]).
     * Лежит под именем самого набора и помечена протухшей (время файла — 0), поэтому первая же
     * докачка кладёт настоящий набор на её место, а ядро перечитывает файл без перезапуска.
     * Файл уже есть (настоящий или заглушка) — его и отдаём. Не вышло записать — `null`:
     * набор останется удалённым, как прислал сервер.
     */
    fun zaglushka(remote: RuleSetLocalPatch.Remote): File? = runCatching {
        val target = File(dir, remote.tag + SUFFIX)
        if (target.isFile && target.length() > 0) return target
        val tmp = File(dir, remote.tag + SUFFIX + ".tmp")
        tmp.writeText(ZAGLUSHKA_TEKST)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        target.setLastModified(0L)
        target
    }.getOrNull()

    /** Это заглушка, а не скачанный набор. */
    fun isZaglushka(file: File): Boolean = runCatching {
        file.length() < 256 && file.readText(Charsets.UTF_8).contains(ZAGLUSHKA_DOMEN)
    }.getOrDefault(false)

    private fun sameBytes(a: File, b: File): Boolean = runCatching {
        a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())
    }.getOrDefault(false)

    /** Формат файла в кэше не записан: годится любой из двух видов набора. */
    private fun looksLikeAnyRuleSet(file: File): Boolean = kindOf(file) != null

    private fun looksLikeRuleSet(file: File, format: String): Boolean = kindOf(file) == format

    /**
     * Какой это набор: `binary` (заголовок `SRS`), `source` (JSON с массивом `rules`) или
     * `null` — не набор вовсе.
     *
     * У текстовой формы своего заголовка нет, и одной открывающей скобки мало: в урезанной
     * сети вместо набора приезжает и JSON-ответ с ошибкой (`{"error":…}`), а ядро на таком
     * файле не стартует. Поэтому текстовый набор разбирается целиком — он маленький (свои
     * правила, тысячи строк), а ошибка здесь стоила бы старта ядра.
     */
    internal fun kindOf(file: File): String? {
        if (!file.isFile || file.length() < 4) return null
        val head = ByteArray(3)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrElse { -1 }
        if (read != head.size) return null
        if (head.contentEquals(MAGIC)) return "binary"
        return if (isSourceRuleSet(file)) "source" else null
    }

    private fun isSourceRuleSet(file: File): Boolean = runCatching {
        if (file.length() > SOURCE_MAX_BYTES) return false
        val text = file.readText(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!text.startsWith("{")) return false
        JSONObject(text).optJSONArray("rules") != null
    }.getOrDefault(false)

    /** Текстовый набор больше этого — уже не свои правила; разбирать его целиком не будем. */
    private const val SOURCE_MAX_BYTES = 8L * 1024 * 1024

    /**
     * Домены из текстового набора в кэше (`domain_suffix` и `domain` всех правил) — для
     * разрешённого под белым списком ([BelyeDobavki]). Набора нет, он бинарный или битый —
     * `null`: решать, чем заменить, будет вызывающий.
     */
    fun sourceDomains(file: File): List<String>? = runCatching {
        if (kindOf(file) != "source") return null
        val rules = JSONObject(file.readText(Charsets.UTF_8).trimStart('\uFEFF')).getJSONArray("rules")
        val out = LinkedHashSet<String>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            for (key in listOf("domain_suffix", "domain")) {
                when (val v = rule.opt(key)) {
                    is String -> out += v
                    is org.json.JSONArray -> for (j in 0 until v.length()) v.optString(j).takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }
        out.toList()
    }.getOrNull()
}
