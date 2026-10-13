package io.nekohasekai.sfa

/**
 * Разбор ссылок подписки. Чистая логика без Android: проверяется юнит-тестами.
 *
 * Сеть многосерверная: тот же протокол (/k/<код>, /k/<код>/info, /logs, /report)
 * раздаёт любой наш хост, поэтому хост берётся из самой ссылки, а не из константы.
 */
object SubscriptionLink {
    const val DEFAULT_HOST = "subkv.chickenkiller.com"

    /** Хост (с портом, если был) и код доступа. */
    /** [kind]: 'k' (общий профиль по коду) или 'u' (профиль устройства, [code] здесь ключ). */
    data class Parsed(val host: String, val code: String, val kind: Char = 'k')

    private const val LEGACY_SUFFIX = "singbox"

    /**
     * Разбирает нашу ссылку: `https://<хост>/k|s|i/<код>[/...]`.
     * Старую форму `/<код>[/singbox]` (код первым сегментом) понимаем только для [DEFAULT_HOST]:
     * на чужом хосте такой путь может быть чьей угодно подпиской. Не наше — null.
     */
    fun parse(input: String): Parsed? {
        val raw = input.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = raw.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
        val host = rest.substringBefore('/').substringAfterLast('@').lowercase()
        if (host.isEmpty()) return null
        val parts = rest.substringAfter('/', "").split('/').filter { it.isNotBlank() }
        val first = parts.firstOrNull() ?: return null
        val code = if (first in setOf("k", "s", "i", "u")) {
            parts.getOrNull(1)
        } else if (host == DEFAULT_HOST) {
            first
        } else {
            null
        }
        if (code.isNullOrBlank() || code == LEGACY_SUFFIX) return null
        return Parsed(host, code, if (first == "u") 'u' else 'k')
    }

    /** Адрес профиля в его собственной форме: `/u/<ключ>` или `/k/<код>`. Не наша ссылка: null. */
    fun baseUrl(url: String): String? = parse(url)?.let { "https://${it.host}/${it.kind}/${it.code}" }

    /** Адрес конфига: `https://<хост>/k/<код>`. */
    fun configUrl(code: String, host: String = DEFAULT_HOST): String = "https://$host/k/$code"

    /**
     * Приводит ввод к рабочей ссылке:
     *  - голый код                               -> https://subkv.../k/<код>
     *  - наша ссылка на любом хосте (/k, /s, /i) -> https://<тот же хост>/k/<код>
     *  - всё остальное                           -> как есть (чужие подписки не трогаем)
     */
    fun normalize(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return raw
        if (!raw.contains("://")) return configUrl(raw.trim('/'))
        val parsed = parse(raw) ?: return raw.trimEnd('/')
        return baseUrl(raw) ?: raw.trimEnd('/')
    }

    /** Хост из ссылки профиля; не наша ссылка — [DEFAULT_HOST]. */
    fun hostOf(url: String): String = parse(url)?.host ?: DEFAULT_HOST

    /** Наш ли это адрес (для заголовков устройства): наша форма на любом хосте либо что угодно на [DEFAULT_HOST]. */
    fun isOwn(url: String): Boolean = parse(url) != null || url.contains(DEFAULT_HOST)

    /** Заменяет схему и хост в адресе (для debug-подмены сервера). */
    fun withBase(url: String, base: String): String =
        Regex("^https?://[^/]+").replace(url, java.util.regex.Matcher.quoteReplacement(base.trimEnd('/')))

    private val OWN_PATH = Regex("(?i)([a-z0-9.-]+(?::\\d+)?)/(?:k|s|i|u)/([A-Za-z0-9_-]+)")
    private val LEGACY_PATH = Regex("(?i)(" + Regex.escape(DEFAULT_HOST) + ")/(?!\\*\\*\\*)([A-Za-z0-9_-]+)")

    private fun mask(m: MatchResult): String {
        val code = m.groupValues[2]
        val tail = if (code.length > 2) code.takeLast(2) else code
        return "${m.groupValues[1]}/***$tail (${code.length} симв.)"
    }

    /**
     * Маскирует код в тексте для любого хоста: `<хост>/k/<код>` превращается в `<хост>` + звёздочки + хвост кода и длину.
     * Для [DEFAULT_HOST] маскируется и старая форма без /k/.
     */
    fun maskCode(text: String): String = LEGACY_PATH.replace(OWN_PATH.replace(text, ::mask), ::mask)

    /**
     * Глубокая ссылка `kelevra://import?url=<urlencoded>` -> адрес подписки (нормализованный)
     * или null, если это другая ссылка. Допускается и `kelevra://import?code=<код>`.
     */
    fun fromDeepLink(link: String): String? {
        val raw = link.trim()
        if (!raw.lowercase().startsWith("kelevra://import")) return null
        val query = raw.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            k to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        val value = params["url"] ?: params["code"] ?: return null
        if (value.isBlank()) return null
        return normalize(value)
    }
}
