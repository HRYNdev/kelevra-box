package io.nekohasekai.sfa

import org.json.JSONObject

/**
 * Вход по коду: разбор кода, список дверей, выбор двери по ответам. Чистая логика без сети
 * и Android (кроме org.json): проверяется юнит-тестами. Устройство решения:
 * KelevraServer/docs/PRIGLASHENIE.md.
 */
object Priglashenie {
    const val SPRAVOCHNIK_URL = "https://github.com/HRYNdev/kelevra-box/releases/latest/download/dveri.json"
    const val TEKST_LIMIT = "Достигнут лимит устройств"
    private const val DLINA_KODA = 12
    private val DVER = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?$")

    // ---- код ----

    /** Похоже ли это на ссылку, а не на код. */
    fun etoSsylka(vvod: String): Boolean = vvod.contains("://")

    private fun bezRazdelitelej(s: String) = s.filter { !it.isWhitespace() && it != '-' }

    private fun ascii(c: Char) = c.isLetterOrDigit() && c.code < 128

    /**
     * Варианты кода для проверки, по порядку. Новый код (12 букв и цифр) приводится к верхнему
     * регистру без дефисов и пробелов. Старые коды (длинные, с - и _) уходят как есть, регистр
     * не трогается; вариант без вставленных маской дефисов идёт следом.
     */
    fun varianty(vvod: String): List<String> {
        val chistyj = vvod.filter { !it.isWhitespace() }
        if (chistyj.isEmpty() || etoSsylka(chistyj)) return emptyList()
        if (chistyj.any { !ascii(it) && it != '-' && it != '_' }) return emptyList()
        val szhatyj = bezRazdelitelej(vvod)
        val novyj = szhatyj.length == DLINA_KODA && szhatyj.all { ascii(it) }
        return if (novyj) {
            listOf(szhatyj.uppercase(), chistyj).distinct()
        } else {
            listOf(chistyj, szhatyj).filter { it.isNotEmpty() }.distinct()
        }
    }

    /** Маска поля: до 12 букв и цифр показываются группами по 4; всё остальное (старые коды, ссылки) не трогается. */
    fun maska(vvod: String): String {
        if (vvod.any { !ascii(it) && it != '-' && !it.isWhitespace() }) return vvod
        val szhatyj = bezRazdelitelej(vvod)
        if (szhatyj.length > DLINA_KODA) return vvod
        return szhatyj.uppercase().chunked(4).joinToString("-")
    }

    /** Код из `kelevra://import?kod=...`; другой вид ссылки или пустой код: null. */
    fun kodIzSsylki(ssylka: String): String? {
        val raw = ssylka.trim()
        if (!raw.lowercase().startsWith("kelevra://import")) return null
        val zapros = raw.substringAfter('?', "").substringBefore('#')
        val znachenie = zapros.split('&').firstOrNull { it.substringBefore('=') == "kod" }
            ?.substringAfter('=', "") ?: return null
        val kod = runCatching { java.net.URLDecoder.decode(znachenie, "UTF-8") }.getOrDefault(znachenie)
        return kod.trim().takeIf { varianty(it).isNotEmpty() }
    }

    // ---- двери ----

    fun dverGodna(dver: String): Boolean = DVER.matches(dver)

    private val ADRES_KLYUCHA = Regex("^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?/u/[A-Za-z0-9_-]{4,64}/?$")

    /** Адрес устройства из ответа входа: ровно `https://<хост>/u/<ключ>`. */
    fun adresKlyuchaGoden(profil: String): Boolean = ADRES_KLYUCHA.matches(profil)

    private fun ochistit(spisok: List<String>): List<String> =
        spisok.map { it.trim().lowercase() }.filter { dverGodna(it) }

    /** Справочник `{"dveri": [...]}`; мусор даёт пустой список. */
    fun razobratSpravochnik(tekst: String): List<String> {
        val vse = runCatching {
            val arr = JSONObject(tekst).optJSONArray("dveri")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
        return ochistit(vse)
    }

    /** Встроенная дверь первой, затем справочник, затем двери из прошлых /info; без повторов. */
    fun spisokDverej(vstroennaya: String, spravochnik: List<String>, zapomnennye: List<String>): List<String> =
        ochistit(listOf(vstroennaya) + spravochnik + zapomnennye).distinct()

    // ---- ответ двери ----

    sealed class Otvet {
        data class Ok(val profil: String, val slot: String?) : Otvet()
        data class Limit(val tekst: String) : Otvet()

        /** 404/405: код неизвестен либо дверь без нового протокола. */
        object NetProtokola : Otvet()
        object Sboy : Otvet()
    }

    /** Разбор ответа `POST /k/<код>/ustroystvo`. */
    fun razobratOtvet(status: Int, telo: String): Otvet = when (status) {
        200 -> {
            val j = runCatching { JSONObject(telo) }.getOrNull()
            val profil = j?.optString("profil")?.trim().orEmpty()
            // 200 без адреса https://<хост>/u/<ключ> (пусто, общий /k/, другая схема, лишние части пути) —
            // сбой двери, а не вход: такой адрес не сохраняется.
            if (!adresKlyuchaGoden(profil)) {
                Otvet.Sboy
            } else {
                Otvet.Ok(profil, j?.optString("slot")?.takeIf { it.isNotBlank() })
            }
        }
        403 -> {
            val j = runCatching { JSONObject(telo) }.getOrNull()
            if (j?.optString("oshibka") == "limit_ustroystv") {
                Otvet.Limit(j.optString("tekst").ifBlank { TEKST_LIMIT })
            } else {
                Otvet.Sboy
            }
        }
        404, 405 -> Otvet.NetProtokola
        else -> Otvet.Sboy
    }

    // ---- выбор двери ----

    sealed class Itog {
        /** Новый протокол: профиль на /u/<ключ>. */
        data class Novyj(val dver: String, val profil: String) : Itog()

        /** Дверь знает код по старому пути: профиль /k/<код>. */
        data class Staryj(val dver: String) : Itog()
        data class Limit(val tekst: String) : Itog()
        object NeNajden : Itog()

        /** Ни одна дверь не ответила. */
        object NetSvyazi : Itog()
    }

    /**
     * Вход в два прохода. [aktivaciya] возвращает ответ двери на `POST /k/<код>/ustroystvo` либо null,
     * если дверь не ответила; [znaetKod] проверяет старый путь `GET /k/<код>/info`.
     *
     * Первый проход — вход устройства у всех дверей по порядку: адрес /u/<ключ> или лимит завершают
     * обход сразу. Только если ни одна дверь устройство не завела, второй проход — старый путь у дверей,
     * ответивших 404/405, в том же порядке.
     *
     * Почему не дверь за дверью. Старый сервер пересылает старый путь переехавшего человека в новую
     * сеть, а вход устройства не пересылает (404). При обходе по одной двери первая же отдала бы
     * переехавшему общий /k/<код>, и телефон жил бы мимо ключа устройства — без лимита устройств и
     * отключения по одному. Человек старой системы новой дверью не узнан (404) и остаётся, где был.
     *
     * 404 хотя бы от одной двери и ни одной знающей: код не найден; ответов нет вовсе: нет связи.
     */
    suspend fun vybratDver(
        dveri: List<String>,
        aktivaciya: suspend (String) -> Otvet?,
        znaetKod: suspend (String) -> Boolean,
    ): Itog {
        val starymPutem = mutableListOf<String>()
        for (dver in dveri) {
            when (val o = aktivaciya(dver)) {
                null, Otvet.Sboy -> {}
                is Otvet.Ok -> return Itog.Novyj(dver, o.profil)
                is Otvet.Limit -> return Itog.Limit(o.tekst)
                Otvet.NetProtokola -> starymPutem += dver
            }
        }
        for (dver in starymPutem) {
            if (znaetKod(dver)) return Itog.Staryj(dver)
        }
        return if (starymPutem.isNotEmpty()) Itog.NeNajden else Itog.NetSvyazi
    }

    // ---- устройство и профили ----

    /** X-Device-Id для новой установки: sha256(ANDROID_ID + "kelevra") в hex, 32 знака. */
    fun idIzAndroidId(androidId: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((androidId + "kelevra").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    /**
     * Имя нового профиля из входа по коду. Первый профиль называется «Kelevra», как раньше.
     * Если такое имя уже занято, к нему добавляется сервер, выдавший профиль (первая часть
     * имени хоста: «Kelevra · node2kv»), затем дата добавления, затем номер. Так новый
     * профиль в списке отличается от старого, а сервер берётся из самого адреса профиля.
     */
    fun imyaProfilya(url: String, zanyatye: Collection<String>, data: String): String {
        val zanyato = zanyatye.map { it.trim().lowercase() }.toSet()
        fun svobodno(imya: String) = imya.lowercase() !in zanyato
        val baza = "Kelevra"
        if (svobodno(baza)) return baza
        val server = korotkoeImyaServera(url)
        val sServerom = if (server.isEmpty()) baza else "$baza · $server"
        if (svobodno(sServerom)) return sServerom
        val sDatoy = "$sServerom · $data"
        if (svobodno(sDatoy)) return sDatoy
        var n = 2
        while (!svobodno("$sDatoy ($n)")) n++
        return "$sDatoy ($n)"
    }

    /** Первая часть имени хоста из адреса: `https://node2kv.example.com/u/…` → `node2kv`. IP — целиком. */
    internal fun korotkoeImyaServera(url: String): String {
        val host = (SubscriptionLink.parse(url)?.host ?: url.substringAfter("://", "").substringBefore('/'))
            .substringAfterLast('@')
            .let { h -> if (h.startsWith("[")) h.substringBefore(']').removePrefix("[") else h.substringBefore(':') }
            .lowercase()
        if (host.isEmpty()) return ""
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
        return host.substringBefore('.')
    }

    /** Тот же ли это профиль: одинаковый адрес либо старый /k/<код> с одним из введённых кодов. */
    fun totZhe(profilUrl: String, imeyushijsya: String, vvedennyeKody: List<String>): Boolean {
        val a = SubscriptionLink.parse(profilUrl)
        val b = SubscriptionLink.parse(imeyushijsya) ?: return false
        if (a != null && a.kind == b.kind && a.host == b.host && a.code == b.code) return true
        // Повторная активация того же устройства выдаёт НОВЫЙ ключ (сервер хранит только хеш): тот же вход
        // на той же двери — тот же профиль устройства, иначе после повторного ввода кода их было бы два.
        if (a != null && a.kind == 'u' && b.kind == 'u' && a.host == b.host) return true
        return b.kind == 'k' && vvedennyeKody.any { it == b.code }
    }
}
