package io.nekohasekai.sfa.bg

import java.io.File
import org.json.JSONObject

/**
 * «Российское», которое под белым списком оператора идёт напрямую, а не в комнату: домены,
 * без которых сервис из списка не работает (картинки WB и Ozon, статика VK и банков).
 *
 * Раньше список был только вшитым в APK ([OlcRtcConfigPatch.DOBAVKI_DOMENOV]) и менялся
 * выпуском. С одной истиной правил (решение 25.10.2026) он живёт в мозге — записи «напрямую»
 * с отметкой «пропускается и при белом списке» (`pri_belom`) — и приходит удалённым набором
 * в профиле. Набор узнаём по тегу ([TEGI], с дверью: `svoi-pri-belom-2`), содержимое берём
 * из своего кэша наборов ([RuleSetCache]): он есть и без сети, а решение о белом списке
 * принимается как раз тогда, когда сети почти нет.
 *
 * Набора в профиле нет, он ещё не скачан или пуст — вшитый список: под белым списком
 * лучше старое знание, чем никакого.
 *
 * Весь набор `svoi-mimo` сюда не идёт и не нужен: правило «напрямую» сервер ставит в
 * профиль выше туннеля, и под белым списком оно и так ведёт напрямую.
 */
object BelyeDobavki {
    /** Имена набора «разрешено при белом списке» в профиле (договор выдачи и ранний план). */
    val TEGI = listOf("svoi-pri-belom", "svoi-belyy")

    /** Заглушка выдачи для пустого набора: ничему не равна, в список не идёт. */
    private const val ZAGLUSHKA = ".invalid"

    data class Itog(val domeny: List<String>, val izProfilya: Boolean, val note: String)

    /**
     * @param content конфиг (до или после подмены наборов кэшем — теги те же).
     * @param cached тег набора → файл в кэше ([RuleSetCache.cached]).
     */
    fun iz(content: String, cached: Map<String, File>): Itog {
        val tegi = runCatching { tegiNabora(content) }.getOrElse { emptyList() }
        if (tegi.isEmpty()) {
            return Itog(OlcRtcConfigPatch.DOBAVKI_DOMENOV, false, "разрешённое при белом списке: набора в профиле нет — вшитый список")
        }
        val domeny = LinkedHashSet<String>()
        var prochitano = 0
        for (teg in tegi) {
            val file = cached[teg] ?: continue
            val iz = RuleSetCache.sourceDomains(file) ?: continue
            prochitano++
            iz.asSequence()
                .map { it.trim().removePrefix("*.").trim('.').lowercase() }
                .filter { it.contains('.') && !it.endsWith(ZAGLUSHKA) }
                .forEach { domeny += it }
        }
        if (domeny.isEmpty()) {
            val pochemu = if (prochitano == 0) "ещё не скачан" else "пуст"
            return Itog(OlcRtcConfigPatch.DOBAVKI_DOMENOV, false, "разрешённое при белом списке: набор ${tegi.joinToString("/")} $pochemu — вшитый список")
        }
        return Itog(domeny.toList(), true, "разрешённое при белом списке: из профиля (${domeny.size})")
    }

    private fun tegiNabora(content: String): List<String> {
        val sets = JSONObject(content).optJSONObject("route")?.optJSONArray("rule_set") ?: return emptyList()
        return (0 until sets.length())
            .mapNotNull { sets.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank) }
            .filter { tag -> TEGI.any { tag == it || tag.startsWith("$it-") } }
    }
}
