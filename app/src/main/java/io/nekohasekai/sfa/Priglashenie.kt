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
            val razobrano = SubscriptionLink.parse(profil)
            if (razobrano == null || razobrano.kind != 'u') {
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
     * Обходит двери по очереди. [aktivaciya] возвращает ответ двери либо null, если дверь не ответила;
     * [znaetKod] проверяет `GET /k/<код>/info` на двери, ответившей 404/405.
     * Успех и лимит завершают обход сразу; дверь, знающая код по старому пути, даёт старый путь.
     * 404 хотя бы от одной двери и ни одной знающей: код не найден; ответов нет вовсе: нет связи.
     */
    suspend fun vybratDver(
        dveri: List<String>,
        aktivaciya: suspend (String) -> Otvet?,
        znaetKod: suspend (String) -> Boolean,
    ): Itog {
        var otvetili404 = false
        for (dver in dveri) {
            when (val o = aktivaciya(dver)) {
                null, Otvet.Sboy -> {}
                is Otvet.Ok -> return Itog.Novyj(dver, o.profil)
                is Otvet.Limit -> return Itog.Limit(o.tekst)
                Otvet.NetProtokola -> {
                    if (znaetKod(dver)) return Itog.Staryj(dver)
                    otvetili404 = true
                }
            }
        }
        return if (otvetili404) Itog.NeNajden else Itog.NetSvyazi
    }

    // ---- устройство и профили ----

    /** X-Device-Id для новой установки: sha256(ANDROID_ID + "kelevra") в hex, 32 знака. */
    fun idIzAndroidId(androidId: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((androidId + "kelevra").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

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
