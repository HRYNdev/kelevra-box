package io.nekohasekai.sfa.compose.screen.home

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
        val remote = profile.typed.remoteURL.trimEnd('/')
        if (!remote.contains("/k/")) return@runCatching null

        val conn = URL("$remote/info").openConnection() as HttpURLConnection
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
