package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject

/**
 * Гасит ЗАЛИПШИЙ выбор автомата, оставляя ручной выбор человека нетронутым.
 *
 * Беда (стенд `zalipanie.sh`, 21.08.2026, воспроизведена живьём): sing-box хранит
 * выбранный outbound селектора в `experimental.cache_file` (BoltDB, бакет `selected`)
 * и накатывает его поверх `default` из свежего конфига при каждом старте. Переключил
 * выход → перезапуск → выход остался прежним, хотя сервер раздал конфиг заново.
 * `selectExit()` в [BoxService] зовёт тот же `selectOutbound`, что и человек с главного
 * экрана ([io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel],
 * [io.nekohasekai.sfa.compose.screen.dashboard.DashboardViewModel]) — ядру автомат и
 * человек неотличимы, и временный выбор автомата залипает навсегда.
 *
 * Просто стирать кэш нельзя: в нём точно так же лежит ручной выбор человека, а он ОБЯЗАН
 * переживать перезапуск — это не баг, а единственная причина, по которой кэш вообще
 * существует. Поэтому вместо стирания кэша помним отдельно, чья была последняя команда:
 * если последним переключал автомат — на следующем старте возвращаем селектор туда, куда
 * его поставил бы свежий конфиг ([restoreTarget]), и дальше автомат решает заново по живой
 * обстановке. Если последним выбирал человек — [forget] снимает пометку, и [release]
 * ничего не трогает.
 */
object AutoModeSticky {
    private const val TAG = "BoxService"

    /** Автомат только что поставил выход — запоминаем пару, чтобы её можно было погасить. */
    fun remember(group: String, tag: String) {
        runCatching {
            Settings.autoModeStickyGroup = group
            Settings.autoModeStickyTag = tag
        }.onFailure { Log.w(TAG, "AutoModeSticky.remember не удалось: ${it.message}") }
    }

    /** Человек выбрал выход руками — его выбор пометке не подлежит, снимаем её. */
    fun forget() {
        runCatching {
            Settings.autoModeStickyGroup = ""
            Settings.autoModeStickyTag = ""
        }.onFailure { Log.w(TAG, "AutoModeSticky.forget не удалось: ${it.message}") }
    }

    /**
     * Куда вернуть селектор [group], если залипший выбор [stickyTag] — это выбор автомата,
     * а не человека.
     *
     * Чистая функция без Android-зависимостей: разбирает [config], ищет outbound с
     * `tag == group` и `type == "selector"`, берёт его поле `default`.
     *
     * @return `default`, если он непустой, входит в список `outbounds` этой группы и не
     *   совпадает с [stickyTag] (иначе возвращать уже нечего — селектор и так там, где надо).
     *   `null` при любой неожиданности конфига — молчаливый отказ, а не сбой.
     */
    fun restoreTarget(config: String, group: String, stickyTag: String): String? = runCatching {
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: return@runCatching null

        var selector: JSONObject? = null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("tag") == group && outbound.optString("type") == "selector") {
                selector = outbound
                break
            }
        }
        val found = selector ?: return@runCatching null

        val default = found.optString("default").takeIf { it.isNotBlank() } ?: return@runCatching null
        if (default == stickyTag) return@runCatching null

        val members = found.optJSONArray("outbounds") ?: return@runCatching null
        val memberTags = (0 until members.length())
            .mapNotNull { members.optString(it).takeIf { s -> s.isNotBlank() } }
        if (default !in memberTags) return@runCatching null

        default
    }.getOrNull()

    /**
     * Снимает залипший выбор автомата ПОСЛЕ того, как ядро уже поднялось с кэшем.
     *
     * Если пометка стоит и [restoreTarget] нашёл, куда вернуть селектор — зовёт [select].
     * Пометка снимается в любом случае: если возвращать было некуда (человек успел выбрать
     * своё, конфиг сменился, группы больше нет), держать её дальше незачем.
     */
    fun release(config: String?, select: (group: String, tag: String) -> Unit) {
        runCatching {
            val group = Settings.autoModeStickyGroup
            val stickyTag = Settings.autoModeStickyTag
            if (group.isBlank() || stickyTag.isBlank()) return@runCatching

            val target = config?.let { restoreTarget(it, group, stickyTag) }
            if (target != null) {
                Log.i(TAG, "AutoModeSticky: залипший выбор автомата «$stickyTag» в «$group» возвращаю на «$target»")
                select(group, target)
            }
        }.onFailure { Log.w(TAG, "AutoModeSticky.release не удалось: ${it.message}") }
            .also { forget() }
    }
}
