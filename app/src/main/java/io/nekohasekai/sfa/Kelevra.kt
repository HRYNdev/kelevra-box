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
     * Как устройство называет себя серверу.
     *
     * Ровно то же, что уходит с жалобой ([io.nekohasekai.sfa.compose.screen.home.ComplaintScreen]):
     * модель железа и версия приложения. Раньше это собиралось только там, теперь
     * одно место на всех — жалобу, запрос конфига, сводку и отправку логов.
     */
    const val PLATFORM = "android"

    /** «Xiaomi 23021RAAEG» — производитель и модель, как их отдаёт само железо. */
    val deviceModel: String
        get() = header("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

    val appVersion: String
        get() = header(BuildConfig.VERSION_NAME)

    /** Версия самой системы — «12». Тем же значением зовётся заголовок `x-ver-os`. */
    val osVersion: String
        get() = header(android.os.Build.VERSION.RELEASE ?: "")

    /**
     * Значение заголовка обязано быть однострочным ASCII: имена моделей у части
     * прошивок содержат кириллицу и переводы строк, а такой заголовок HTTP-клиент
     * либо режет, либо роняет весь запрос.
     */
    private fun header(value: String): String =
        value.map { if (it.code in 32..126) it else '?' }.joinToString("").trim().take(128)

    /**
     * Заголовки, по которым сервер сам заводит устройство и потом отдаёт в сводке
     * человеческие имена (`person.name`, `device.name`).
     *
     * Пятым идёт свой расход трафика ([io.nekohasekai.sfa.bg.DeviceTraffic]) — его
     * может и не быть: пока ничего не насчитали, заголовок не шлём вовсе.
     */
    fun deviceHeaders(): List<Pair<String, String>> = listOfNotNull(
        "X-Device-Id" to deviceId,
        "X-Device-Model" to deviceModel,
        "X-Device-Platform" to PLATFORM,
        "X-App-Version" to appVersion,
        io.nekohasekai.sfa.bg.DeviceTraffic.header(io.nekohasekai.sfa.bg.DeviceTraffic.total()),
    )

    /**
     * Чем устройство подписывается в первой строке своего журнала.
     *
     * Повод. В андроидном журнале не было ни модели, ни версии приложения, ни версии
     * системы: кто прислал файл — узнавалось только из реестра на сервере, а по самому
     * журналу никак. У десктопа такая шапка есть с самого начала («запуск Kelevra 0.6.44
     * (windows/amd64)»), и разбор там начинается с неё.
     *
     * Источник ровно тот же, что у заголовков `X-Device-*` ([deviceHeaders]) — второго
     * не заводим: шапка журнала и то, чем устройство назвалось серверу, обязаны
     * совпадать, иначе при разборе жалобы они начинают спорить друг с другом.
     */
    fun deviceSummary(): String = describeDevice(deviceModel, osVersion, appVersion)

    /**
     * Та же шапка из готовых значений — отдельно, чтобы её можно было проверить без телефона.
     *
     * Пустое поле не выкидываем, а называем словами: «модель неизвестна» в шапке — это
     * факт об устройстве, а молча съеденное поле не отличить от того, что его забыли
     * записать вовсе.
     */
    internal fun describeDevice(model: String, osVersion: String, appVersion: String): String {
        val железо = model.ifBlank { "модель неизвестна" }
        val система = if (osVersion.isBlank()) PLATFORM else "$PLATFORM $osVersion"
        val версия = appVersion.ifBlank { "версия неизвестна" }
        return "Kelevra $версия, $железо, $система"
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
