package io.nekohasekai.sfa

/**
 * Настройки своей сети.
 *
 * Подписка раздаётся панелью по адресу вида https://<host>/<код>, а конфиг sing-box
 * лежит по тому же адресу с суффиксом /singbox. Пользователю достаточно кода:
 * остальное достраиваем сами.
 */
object Kelevra {
    const val SUBSCRIPTION_HOST = "subkv.chickenkiller.com"

    private const val CONFIG_SUFFIX = "/singbox"

    /**
     * Постоянный идентификатор устройства для учёта в панели.
     * Генерируется один раз и переживает перезапуски: иначе каждое обновление подписки
     * выглядело бы как новое устройство.
     */
    val deviceId: String by lazy {
        val prefs = Application.application.getSharedPreferences("kelevra", android.content.Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    /**
     * Код подписки из любого нашего адреса: и нового (/k/<код>), и старого, который
     * отдавала панель (/<код>/singbox). Для чужих ссылок — null.
     *
     * Нужен, потому что профили, заведённые до своего сборщика, ведут на панель:
     * такой конфиг приезжает без комнаты, а сводка /info по нему не читается вовсе.
     */
    fun subscriptionCode(url: String): String? {
        val cleaned = url.trim().trimEnd('/')
        if (!cleaned.contains(SUBSCRIPTION_HOST)) return null
        val parts = cleaned.substringAfter(SUBSCRIPTION_HOST).split('/').filter { it.isNotBlank() }
        // /k/<код> и /s/<код> — свои формы, у остальных код идёт первым сегментом
        val code = if (parts.firstOrNull() in setOf("k", "s")) parts.getOrNull(1) else parts.firstOrNull()
        return code?.takeIf { it.isNotBlank() && it != CONFIG_SUFFIX.trim('/') }
    }

    /** Адрес конфига у своего сборщика. */
    fun configUrl(code: String): String = "https://$SUBSCRIPTION_HOST/k/$code"

    /**
     * Маскирует код доступа в произвольном тексте: адрес вида
     * `https://<host>/k/<код>` несёт код целиком, и текст ошибки от HTTP-клиента
     * или ядра на Go часто повторяет запрошенный URL внутри себя. В журнал и в
     * исключения код должен попадать не целиком — домен и длина остаются,
     * чтобы диагностика не потерялась.
     */
    fun maskCode(text: String): String =
        Regex("(?i)${Regex.escape(SUBSCRIPTION_HOST)}/(?:k/|s/)?([A-Za-z0-9_-]+)").replace(text) { m ->
            val code = m.groupValues[1]
            val tail = if (code.length > 2) code.takeLast(2) else code
            "$SUBSCRIPTION_HOST/***$tail (${code.length} симв.)"
        }

    /**
     * Копия исключения с замаскированным сообщением, но с тем же местом падения:
     * стектрейс диагностировать помогает, а код доступа в нём не нужен.
     */
    fun maskThrowable(e: Throwable): Throwable {
        val masked = RuntimeException(maskCode(e.toString()))
        masked.stackTrace = e.stackTrace
        return masked
    }

    /**
     * Приводит к рабочей ссылке на конфиг:
     *  - короткий код               -> https://<host>/k/<код>
     *  - любая наша ссылка          -> тоже /k/<код>, в какой бы форме её ни дали
     *  - всё остальное              -> оставляем как есть (чужие подписки не трогаем)
     *
     * Старую форму (`/<код>/singbox`, конфиг от панели) не оставляем даже когда её
     * вводят руками: панель отдаёт формат старого ядра, и sing-box 1.14 его не принимает
     * вовсе. Проверено в эмуляторе 07.08.2026: подключение по такой ссылке падало с
     * «legacy DNS fakeip options are deprecated ... removed in sing-box 1.14.0»,
     * профиль не создавался.
     */
    fun normalizeSubscription(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return raw
        // короткий код -> свой сборщик конфигов
        if (!raw.contains("://")) {
            return configUrl(raw.trim('/'))
        }
        val cleaned = raw.trimEnd('/')
        return subscriptionCode(cleaned)?.let(::configUrl) ?: cleaned
    }
}
