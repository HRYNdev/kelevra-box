package io.nekohasekai.sfa.bg

import org.json.JSONObject

/**
 * Приложения, которым положено ходить мимо туннеля, — список из самого конфига.
 *
 * Зачем читать конфиг, а не настройки. Базовый список ведёт сборщик на сервере: там
 * лежат Госуслуги, налоговая и те приложения, что ругаются на включённый VPN. До
 * телефона он доезжает двумя путями — полем `exclude_package` у входа `tun` в самом
 * конфиге и сводкой `/info` (`bypass_packages`), которая складывается в настройки.
 * Первый путь есть всегда: без конфига туннель не поднимется вовсе. Второй может
 * опоздать — сводка тянется отдельным запросом, и на свежем телефоне её ещё нет.
 *
 * Поэтому список для ядра собирается от конфига, а выбор человека добавляется сверху.
 * Иначе отметка одного приложения руками молча выбивала бы из исключений все серверные.
 *
 * Разбор молчаливый: конфиг приходит с сервера и может быть любой формы, а ронять
 * из-за него подъём туннеля нельзя — человек останется без связи из-за списка.
 */
object PaketyMimoSeti {

    /** Пакеты из всех входов типа `tun`. Ничего не нашлось — пустое множество. */
    fun izKonfiga(content: String): Set<String> = runCatching {
        val inbounds = JSONObject(content).optJSONArray("inbounds")
            ?: return@runCatching emptySet<String>()
        val naydeno = linkedSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            val spisok = inbound.optJSONArray("exclude_package") ?: continue
            for (j in 0 until spisok.length()) {
                spisok.optString(j).takeIf { it.isNotBlank() }?.let { naydeno.add(it) }
            }
        }
        naydeno
    }.getOrElse { emptySet() }
}
