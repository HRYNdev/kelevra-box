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
     * Приводит к рабочей ссылке на конфиг:
     *  - короткий код              -> https://<host>/<код>/singbox
     *  - ссылка панели без суффикса -> дописываем /singbox
     *  - всё остальное              -> оставляем как есть (чужие подписки не трогаем)
     */
    fun normalizeSubscription(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return raw
        if (!raw.contains("://")) {
            return "https://$SUBSCRIPTION_HOST/${raw.trim('/')}$CONFIG_SUFFIX"
        }
        val cleaned = raw.trimEnd('/')
        return if (cleaned.contains(SUBSCRIPTION_HOST) && !cleaned.endsWith(CONFIG_SUFFIX)) {
            cleaned + CONFIG_SUFFIX
        } else {
            cleaned
        }
    }
}
