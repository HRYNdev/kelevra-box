package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.SubscriptionLink

/**
 * Команда `ubrat_lishnie_profili` (PULT.md): импорт по общему коду создаёт НОВЫЙ профиль
 * ([io.nekohasekai.sfa.database.ProfileManager.create] с `andSelect=true`) и не удаляет
 * прежний — рядом с рабочим `/u/<ключ>` остаётся неактивный дубль на `/k/<код>`, убрать
 * его человеку нечем (handoff KelevraBox 01-2).
 *
 * Правило — только безопасное сужение: удаляются ЧУЖИЕ выбранному Remote-профили на
 * `/k/` (общий код), и только если сам выбранный профиль — Remote на `/u/` (ключ устройства).
 * Наивное «есть /u/ → снести все /k/» нельзя: у части устройств несколько рабочих профилей
 * (несколько ключей/серверов), наугад снесённый `/k/` может оказаться чужим рабочим профилем,
 * а не дублем. Локальные профили и профили с чужим (не нашим) URL не трогаются вовсе.
 *
 * Сеть и база профилей подставляются снаружи ([Okruzhenie]) — логика проверяется тестом
 * без боевой базы профилей.
 */
object UbratLishnieProfili {
    /** Профиль, как он нужен этой команде: тип и адрес (для локальных — null). */
    data class Profil(val id: Long, val remote: Boolean, val url: String?)

    interface Okruzhenie {
        /** Все профили на устройстве. */
        suspend fun profili(): List<Profil>

        /** Удалить профиль по id. */
        suspend fun udalit(id: Long)
    }

    suspend fun vypolnit(vybranId: Long, set: Okruzhenie): String {
        // Копия: удаление идёт по ходу перебора, а [Okruzhenie.profili] не обязан отдавать
        // снимок — живой список окружения менялся бы под тем же циклом.
        val profili = set.profili().toList()
        val vybran = profili.firstOrNull { it.id == vybranId } ?: return "oshibka-vybran_ne_klyuch"
        if (!vybran.remote) return "oshibka-vybran_ne_klyuch"
        val vybranParsed = vybran.url?.let { SubscriptionLink.parse(it) }
        if (vybranParsed == null || vybranParsed.kind != 'u') return "oshibka-vybran_ne_klyuch"

        var udaleno = 0
        for (p in profili) {
            if (p.id == vybranId || !p.remote) continue
            val parsed = p.url?.let { SubscriptionLink.parse(it) } ?: continue
            if (parsed.kind != 'k') continue
            set.udalit(p.id)
            udaleno++
        }
        return "ok-$udaleno"
    }
}
