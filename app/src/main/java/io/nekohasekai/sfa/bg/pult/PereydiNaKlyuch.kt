package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.Priglashenie
import io.nekohasekai.sfa.SubscriptionLink

/**
 * Команда `pereydi_na_klyuch` (PULT.md): профиль по общему коду `/k/<код>` переводится на
 * ключ своего устройства `/u/<ключ>` — тем же запросом, что ручной вход по коду
 * (`POST /k/<код>/ustroystvo` по дверям, [Priglashenie.vybratDver]). Адрес переписывается
 * **в том же профиле** и только после того, как конфиг по новому адресу скачан и принят
 * ядром. Любой отказ — профиль остаётся как был.
 *
 * Сеть, ядро и база профилей подставляются снаружи ([Okruzhenie]) — логика проверяется тестом
 * без боевых дверей: вход с выдуманным устройством на боевой двери завёл бы человеку
 * лишнее устройство.
 */
object PereydiNaKlyuch {
    interface Okruzhenie {
        /** Справочник дверей (как у ручного входа). */
        suspend fun dveri(): List<String>

        /** `POST /k/<код>/ustroystvo`; null — дверь не ответила. */
        suspend fun aktivirovat(dver: String, kod: String): Priglashenie.Otvet?

        /** `GET /k/<код>/info` == 200. */
        suspend fun znaetKod(dver: String, kod: String): Boolean

        /** Конфиг по адресу профиля; бросает при сбое. */
        suspend fun skachatKonfig(url: String): String

        /** Проверка конфига ядром ([io.nekohasekai.libbox.Libbox.checkConfig]); бросает, если не годен. */
        fun proveritKonfig(konfig: String)

        /** Переписать адрес и конфиг в том же профиле и перечитать службу. */
        suspend fun perepisat(novyyUrl: String, konfig: String)
    }

    suspend fun vypolnit(profilUrl: String?, set: Okruzhenie): String {
        val url = profilUrl ?: return "oshibka-net_profilya"
        val p = SubscriptionLink.parse(url) ?: return "oshibka-chuzhoy_profil"
        // Уже на ключе — делать нечего, это и есть желаемое состояние.
        if (p.kind == 'u') return "ok"
        // Первой — дверь, выдавшая профиль: она код точно знает.
        val dveri = (listOf(p.host) + set.dveri()).map { it.trim().lowercase() }.distinct()
        val itog = Priglashenie.vybratDver(
            dveri,
            aktivaciya = { dver, _ -> set.aktivirovat(dver, p.code) },
            znaetKod = { set.znaetKod(it, p.code) },
        )
        return when (itog) {
            is Priglashenie.Itog.Novyj -> {
                val konfig = try {
                    set.skachatKonfig(itog.profil)
                } catch (e: Exception) {
                    return "oshibka-konfig_ne_skachan"
                }
                try {
                    set.proveritKonfig(konfig)
                } catch (e: Exception) {
                    return "oshibka-konfig_negoden"
                }
                set.perepisat(itog.profil, konfig)
                "ok"
            }
            is Priglashenie.Itog.Staryj -> "oshibka-dver_bez_klyuchey"
            is Priglashenie.Itog.Limit -> "oshibka-limit"
            Priglashenie.Itog.NeNajden -> "oshibka-kod_ne_nayden"
            Priglashenie.Itog.NetSvyazi -> "oshibka-net_svyazi"
        }
    }
}
