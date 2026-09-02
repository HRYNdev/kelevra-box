package io.nekohasekai.sfa.compose.screen.home

import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.compose.theme.plural
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Сводка о подписке для главного экрана. */
data class SubscriptionInfo(
    val name: String,
    val active: Boolean,
    val expires: String?,
    val limitBytes: Long,
    val usedBytes: Long,
    /** имя канала -> транспорт, которым он ходит */
    val transports: Map<String, String> = emptyMap(),
    /** приложения, которые сеть по умолчанию пускает мимо туннеля */
    val bypassPackages: List<String> = emptyList(),
    /** параметры комнаты olcRTC, если сеть их раздаёт; токен внутри — только владельцу кода */
    val olcrtc: JSONObject? = null,
    /** ответ на жалобу, отправленную с этого устройства; null — отвечать нечего */
    val reply: ComplaintReply? = null,
    /**
     * Чьё это устройство и как оно называется — сервер узнаёт по X-Device-Id.
     *
     * Показываются они только внутри вкладки подписки, на главный экран не выносятся:
     * «имя допустим и какие то подробнсоти ток при заходе во вкладку» (хозяин, 31.08.2026).
     * Обоих полей может не быть вовсе — старый сервер их не присылает; тогда строк на
     * экране просто нет, пустые там хуже отсутствия.
     */
    val personName: String? = null,
    val deviceName: String? = null,
    /**
     * Все устройства этой подписки, своё первым — так их сортирует сервер.
     *
     * Поля может не быть вовсе: старый сервер списка не присылает, и это законно.
     * Тогда список пуст и на экране всё как было — строка «Устройство» про своё же
     * устройство. Пришёл список — эта строка прячется: она про то же самое, только
     * вслепую и в одном экземпляре.
     */
    val devices: List<SubscriptionDevice> = emptyList(),
) {
    /** Одна строка под именем: срок и трафик, если они вообще заданы. */
    val note: String
        get() {
            val parts = mutableListOf<String>()
            expires?.let { parts += "до ${humanDate(it)}" }
            if (limitBytes > 0) {
                parts += "${gigabytes(usedBytes)} из ${gigabytes(limitBytes)} ГБ"
            } else {
                parts += "без ограничений"
            }
            return parts.joinToString(" · ")
        }
}

/**
 * Ответ на жалобу.
 *
 * Жалоба уходила в пустоту: человек писал, и всё. Ответ едет обратно тем же путём,
 * которым и так приходит сводка о подписке, и привязан к устройству, а не к коду —
 * пожаловаться можно и до подключения.
 */
data class ComplaintReply(
    /** номер жалобы: по нему помним, что этот ответ уже читали */
    val id: Int,
    val text: String,
    /** первые строки самой жалобы — чтобы человек вспомнил, о чём речь */
    val about: String,
)

private fun gigabytes(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1024.0 / 1024.0 / 1024.0)

private val MONTHS = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

/**
 * Момент словами: «сегодня в 23:30», «вчера в 23:31», «12 августа в 23:30».
 * Нужен там, где важно не «как давно», а «когда именно» — последняя отправка логов.
 */
fun humanWhen(millis: Long): String {
    if (millis <= 0L) return "ещё не было"
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val time = SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(millis))
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val dayDiff = if (sameYear) {
        now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    } else {
        Int.MAX_VALUE
    }
    return when (dayDiff) {
        0 -> "сегодня в $time"
        1 -> "вчера в $time"
        else -> {
            val day = then.get(java.util.Calendar.DAY_OF_MONTH)
            val month = MONTHS.getOrElse(then.get(java.util.Calendar.MONTH)) { "" }
            "$day $month в $time"
        }
    }
}

/** «2026-09-12» -> «12 сентября»: дату человек читает словами, не цифрами. */
private fun humanDate(iso: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso) ?: return iso
    val cal = java.util.Calendar.getInstance().apply { time = date }
    "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(java.util.Calendar.MONTH)]}"
}.getOrDefault(iso)

/**
 * Тянет сводку с того же сервера, что отдаёт конфиг: /k/<код>/info.
 *
 * Код нигде отдельно не хранится — он уже зашит в адрес подписки выбранного
 * профиля, оттуда его и берём.
 */
/**
 * Базовые исключения из сети приезжают с сервера и добавляются к тому, что человек
 * выбрал сам. Раньше эти два списка жили порознь: на сервере одно, в телефоне другое.
 */
fun applyBypassPackages(packages: List<String>) {
    if (packages.isEmpty()) return
    val current = Settings.perAppProxyList
    val merged = current + packages.filter { it !in current }
    if (merged.size != current.size) {
        Settings.perAppProxyList = merged.toSet()
        Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
    }
}

suspend fun loadSubscription(): SubscriptionInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val id = Settings.selectedProfile
        if (id == -1L) return@runCatching null
        val profile = ProfileManager.get(id) ?: return@runCatching null
        if (profile.typed.type != TypedProfile.Type.Remote) return@runCatching null
        // сводка живёт только у своего сборщика, поэтому адрес профиля приводим к нему:
        // старые профили ведут на панель, и по ним раньше не читалось ничего
        val subCode = Kelevra.subscriptionCode(profile.typed.remoteURL) ?: return@runCatching null
        val remote = Kelevra.configUrl(subCode)

        // Своё устройство называем: по нему сервер найдёт ответ на нашу же жалобу.
        val conn = URL("$remote/info?device=${Kelevra.deviceId}").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "kelevra")
        // Те же заголовки, что и у запроса конфига: сервер по ним заводит
        // устройство и возвращает в этой же сводке person.name и device.name.
        Kelevra.deviceHeaders().forEach { (name, value) -> conn.setRequestProperty(name, value) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) return@runCatching null

        val json = JSONObject(text)
        SubscriptionInfo(
            name = json.optString("name"),
            active = json.optBoolean("active"),
            expires = json.optString("expires").takeIf { it.isNotBlank() && it != "null" },
            limitBytes = json.optLong("limit_bytes"),
            usedBytes = json.optLong("used_bytes"),
            bypassPackages = buildList {
                val arr = json.optJSONArray("bypass_packages") ?: return@buildList
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            },
            // Имена приходят от сервера, который узнал устройство по заголовкам.
            // Старый сервер этих блоков не присылает вовсе — тогда остаётся null.
            personName = json.optJSONObject("person")?.optString("name")
                ?.takeIf { it.isNotBlank() && it != "null" },
            deviceName = json.optJSONObject("device")?.optString("name")
                ?.takeIf { it.isNotBlank() && it != "null" },
            devices = parseDevices(json),
            // блока нет = сеть комнату не раздаёт; выключенную комнату сервер тоже не присылает
            olcrtc = json.optJSONObject("olcrtc"),
            reply = json.optJSONObject("reply")?.let { answer ->
                val body = answer.optString("text")
                if (body.isBlank()) null else ComplaintReply(
                    id = answer.optInt("id"),
                    text = body,
                    about = answer.optString("about"),
                )
            },
            transports = buildMap {
                val list = json.optJSONArray("channels") ?: return@buildMap
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val transport = item.optString("transport")
                    if (name.isNotBlank() && transport.isNotBlank()) put(name, transport)
                }
            },
        )
    }.getOrNull()
}


// ---------------------------------------------------------------------------
// Устройства подписки
//
// Один ключ доступа — несколько устройств: телефон, ноутбук, системный блок.
// Сервер знает их все и отдаёт списком всем клиентам; телефон этот список до сих
// пор молча выбрасывал, хотя на десктопе он уже показан. Всё, что ниже, — чистые
// функции: разобрать и назвать словами. Ради этого они и вынесены из экрана —
// иначе проверить их без Android нечем.
// ---------------------------------------------------------------------------

/** Одно устройство подписки: как его показать и когда его видели. */
data class SubscriptionDevice(
    /** это самое устройство, на котором открыт экран */
    val self: Boolean,
    val name: String,
    /** phone / laptop / desktop — по нему выбирается значок */
    val kind: String,
    val platform: String?,
    val appVersion: String?,
    /** 0 = дату не прислали или не разобрали; тогда строки про неё просто нет */
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    /** 0 = расход по устройству не считался; в списке это прочерк, а не «0 Б» */
    val trafficBytes: Long,
)

/** Свежее этого — «в сети». Четверть часа: сервер метит устройство не на каждом пакете. */
const val DEVICE_ONLINE_WINDOW_MS = 15 * 60 * 1000L

/**
 * Разбор поля `devices`. Поля может не быть — вернётся пустой список, и это не ошибка.
 *
 * Безымянное устройство пропускаем: пустая строка на экране хуже отсутствия строки.
 * Битая дата не роняет весь список — у такого устройства просто не будет времени.
 */
fun parseDevices(json: JSONObject): List<SubscriptionDevice> = buildList {
    val arr = json.optJSONArray("devices") ?: return@buildList
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val name = item.optString("name").cleanText() ?: continue
        add(
            SubscriptionDevice(
                self = item.optBoolean("self"),
                name = name,
                kind = item.optString("kind").cleanText()?.lowercase(Locale.US).orEmpty(),
                platform = item.optString("platform").cleanText(),
                appVersion = item.optString("app_version").cleanText(),
                firstSeenMillis = parseIsoMillis(item.optString("first_seen")),
                lastSeenMillis = parseIsoMillis(item.optString("last_seen")),
                trafficBytes = item.optLong("traffic_bytes").coerceAtLeast(0L),
            )
        )
    }
}

/** org.json отдаёт отсутствующее поле пустой строкой, а null — строкой «null». */
private fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private val ISO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    // Зону сервер присылает всегда, но дата без зоны — не повод потерять устройство:
    // читаем её как UTC. Ошибиться на часовой пояс лучше, чем не показать ничего.
    "yyyy-MM-dd'T'HH:mm:ss",
)

/** ISO-8601 с зоной -> миллисекунды. Не разобралось — 0, и времени у устройства не будет. */
fun parseIsoMillis(text: String?): Long {
    val raw = text.cleanText() ?: return 0L
    for (pattern in ISO_PATTERNS) {
        val parsed = runCatching {
            val format = SimpleDateFormat(pattern, Locale.US)
            // Без этого SimpleDateFormat молча съедает «2026-13-45» и выдаёт другую дату.
            format.isLenient = false
            if (pattern.endsWith("ss")) format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(raw)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}

/**
 * Когда устройство видели: «в сети» / «был 20 минут назад» / «был 2 часа назад» /
 * «был вчера» / «был 3 сентября». null — времени нет, значит и строки на экране нет.
 */
fun deviceSeenWords(lastSeenMillis: Long, nowMillis: Long): String? {
    if (lastSeenMillis <= 0L) return null
    val diff = nowMillis - lastSeenMillis
    // Часы устройства могут убежать вперёд относительно серверных. «Был через полчаса»
    // читать невозможно, поэтому будущее — это «сейчас».
    if (diff < DEVICE_ONLINE_WINDOW_MS) return "в сети"
    val minutes = diff / 60_000L
    if (minutes < 60L) return "был $minutes ${plural(minutes.toInt(), "минуту", "минуты", "минут")} назад"
    val hours = diff / 3_600_000L
    if (hours < 24L) return "был $hours ${plural(hours.toInt(), "час", "часа", "часов")} назад"
    if (isYesterday(lastSeenMillis, nowMillis)) return "был вчера"
    return "был ${dayMonth(lastSeenMillis)}"
}

/** «с 31 августа» — с какого дня устройство у этой подписки. null, если даты нет. */
fun deviceSinceWords(firstSeenMillis: Long): String? {
    if (firstSeenMillis <= 0L) return null
    return "с ${dayMonth(firstSeenMillis)}"
}

/** Вчера считается по календарю, а не по «минус 24 часа»: человек думает днями. */
private fun isYesterday(millis: Long, nowMillis: Long): Boolean {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val yesterday = java.util.Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    return then.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
        then.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)
}

/** «3 сентября»: день и месяц словами, как все остальные даты на этом экране. */
private fun dayMonth(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(java.util.Calendar.MONTH)]}"
}

private val TRAFFIC_UNITS = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")

/**
 * Расход устройства: «12 ГБ», «3,0 ГБ», «870 МБ».
 *
 * null — расход не считался: на его месте в списке прочерк. «0 Б» тут врёт, потому
 * что ноль в ответе сервера значит «не мерили», а не «не ходил в сеть».
 */
fun deviceTrafficWords(bytes: Long): String? {
    if (bytes <= 0L) return null
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < TRAFFIC_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    // Округление до «1024 МБ» — это «1,0 ГБ», иначе единица измерения выглядит сломанной.
    if (value >= 1023.5 && unit < TRAFFIC_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    // Дробь нужна только мелким числам: «11,5 ГБ» глаз читает дольше, чем «12 ГБ», а
    // полгигабайта на таком объёме ничего не решают. Запятая, а не точка — по-русски.
    val text = if (unit == 0 || value >= 10.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value).replace('.', ',')
    }
    return "$text ${TRAFFIC_UNITS[unit]}"
}
