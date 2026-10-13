package io.nekohasekai.sfa.bg

import android.util.Log
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
                        file.delete()
                        Log.w(TAG, "набор «${file.name.removeSuffix(SUFFIX)}» в кэше испорчен (${file.length()} б) — выкинул")
                    }
                }
            }
            .associateBy { it.name.removeSuffix(SUFFIX) }
    }.getOrElse {
        Log.w(TAG, "кэш наборов не прочитался: ${it.message}")
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
    fun refresh(remotes: List<RuleSetLocalPatch.Remote>, socksPort: Int, reason: String) {
        if (remotes.isEmpty()) return
        val now = System.currentTimeMillis()
        // Порог держит только повтор по тому же пути. Появилась НОВАЯ дорога (поднялась
        // комната сразу после неудачи по мёртвому каналу) — пробуем немедленно: ждать
        // минуту в сети, где эта дорога единственная, значит терять её же.
        if (now - lastAttempt < RETRY_AFTER && socksPort == lastPort) return
        lastAttempt = now
        lastPort = socksPort

        val due = remotes.filter { stale(File(dir, it.tag + SUFFIX), now) }
        if (due.isEmpty()) {
            Log.i(TAG, "наборы свежие, докачивать нечего ($reason)")
            return
        }
        val way = if (socksPort == DIRECT) "напрямую" else "через 127.0.0.1:$socksPort"
        Log.i(TAG, "докачиваю наборы: ${due.size} из ${remotes.size} ($reason), $way")

        var ok = 0
        for (remote in due) {
            if (download(remote, socksPort)) ok++
        }
        val have = cached().keys
        _state.value = State(total = remotes.size, ready = remotes.count { it.tag in have })
        Log.i(TAG, "докачано $ok из ${due.size}; в кэше ${_state.value.ready} из ${remotes.size}")
    }

    private const val SUFFIX = ".srs"

    private fun stale(file: File, now: Long): Boolean =
        !file.isFile || file.length() == 0L || now - file.lastModified() > FRESH_FOR

    /**
     * Скачивает набор во временный файл и подменяет им старый только целиком.
     *
     * Проверка заголовка тут не придирка, а защита от самого неприятного исхода: в такой
     * сети вместо файла легко приезжает страница-заглушка, и записать её под именем набора
     * означало бы сломать старт ядра НАВСЕГДА — ровно то, от чего уходим. Не похоже на
     * набор — считаем, что не скачали.
     */
    private fun download(remote: RuleSetLocalPatch.Remote, socksPort: Int): Boolean {
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
                tmp.delete()
                Log.w(TAG, "набор «${remote.tag}» скачался непохожим на набор (${tmp.length()} б) — не берём")
                return false
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
                Log.w(TAG, "набор «${remote.tag}» не переименовался в кэше")
                return false
            }
            true
        }.getOrElse {
            tmp.delete()
            Log.w(TAG, "набор «${remote.tag}» не скачался: ${it.message}")
            false
        }
    }

    /** Формат файла в кэше не записан: годится любой из двух видов набора. */
    private fun looksLikeAnyRuleSet(file: File): Boolean =
        looksLikeRuleSet(file, "binary") || looksLikeRuleSet(file, "source")

    private fun looksLikeRuleSet(file: File, format: String): Boolean {
        if (!file.isFile || file.length() < 4) return false
        val head = ByteArray(3)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrElse { -1 }
        if (read != head.size) return false
        // Текстовая форма набора — обычный JSON, у неё своего заголовка нет.
        if (format == "source") return head[0] == '{'.code.toByte()
        return head.contentEquals(MAGIC)
    }
}
