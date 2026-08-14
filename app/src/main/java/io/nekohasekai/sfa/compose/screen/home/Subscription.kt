package io.nekohasekai.sfa.compose.screen.home

import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
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
