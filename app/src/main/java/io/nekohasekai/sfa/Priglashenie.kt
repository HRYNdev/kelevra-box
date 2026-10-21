package io.nekohasekai.sfa

import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Сроки входа устройства (первый проход [vybratDver]). Дверь, которую режут по дороге или которая
     * лежит, не должна держать вход: иначе человек старой системы ждал бы полный срок чужой двери.
     * Соединение (TCP и TLS) ждём [SROK_SOEDINENIYA_MS], весь запрос — [SROK_OTVETA_MS], весь первый
     * проход по всем дверям — [SROK_PERVOGO_PROHODA_MS]. Старый путь идёт прежним, полным сроком.
     */
    const val SROK_SOEDINENIYA_MS = 5_000L
    const val SROK_OTVETA_MS = 10_000L
    const val SROK_PERVOGO_PROHODA_MS = 12_000L

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

    private val PUT_KLYUCHA = Regex("^/u/([A-Za-z0-9_-]{4,64})/?$")
    private val HOST_ASCII = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
    private val IPV6 = Regex("^\\[[0-9a-f:.]+(%[0-9a-z._~-]+)?]$")
    private val PORT = Regex("^(:\\d{1,5})?$")

    /**
     * Адрес устройства из ответа входа в одном виде: `https://<хост>/u/<ключ>`. Схема в любом регистре,
     * хост — имя (в том числе не латиницей: приводится к xn--), IPv4 или IPv6 в скобках, с портом;
     * параметры и якорь отбрасываются. null — не https либо путь не `/u/<ключ>`: такой адрес не вход.
     */
    fun adresKlyucha(profil: String): String? {
        val s = profil.trim()
        val i = s.indexOf("://")
        if (i <= 0 || !s.substring(0, i).equals("https", ignoreCase = true)) return null
        val rest = s.substring(i + 3).substringBefore('#').substringBefore('?')
        val avtoritet = rest.substringBefore('/')
        val klyuch = PUT_KLYUCHA.matchEntire(rest.substring(avtoritet.length))?.groupValues?.get(1) ?: return null
        val host = normHost(avtoritet) ?: return null
        return "https://$host/u/$klyuch"
    }

    /** Хост с портом из адреса в виде для хранения; учётные данные в адресе и мусор — null. */
    private fun normHost(avtoritet: String): String? {
        if (avtoritet.isEmpty() || avtoritet.contains('@')) return null
        if (avtoritet.startsWith("[")) {
            val ip = avtoritet.substringBefore(']') + "]"
            val port = avtoritet.substring(ip.length.coerceAtMost(avtoritet.length))
            val ipMal = ip.lowercase()
            return if (IPV6.matches(ipMal) && PORT.matches(port)) ipMal + port else null
        }
        val imya = avtoritet.substringBefore(':')
        val port = avtoritet.substring(imya.length)
        if (!PORT.matches(port)) return null
        val ascii = runCatching { java.net.IDN.toASCII(imya, java.net.IDN.ALLOW_UNASSIGNED) }.getOrNull()
            ?.lowercase() ?: return null
        return if (HOST_ASCII.matches(ascii)) ascii + port else null
    }

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
            // 200 без адреса https://<хост>/u/<ключ> (пусто, общий /k/, другая схема, лишние части пути) —
            // сбой двери, а не вход: такой адрес не сохраняется.
            val profil = adresKlyucha(j?.optString("profil").orEmpty())
            if (profil == null) {
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
     * Вход в два прохода. [aktivaciya] получает дверь и оставшийся срок первого прохода в мс и возвращает
     * ответ двери на `POST /k/<код>/ustroystvo` либо null, если дверь не ответила; [znaetKod] проверяет
     * старый путь `GET /k/<код>/info`.
     *
     * Первый проход — вход устройства у дверей по порядку, весь не дольше [srokPervogoProhodaMs]: адрес
     * /u/<ключ> или лимит завершают обход сразу. Только если ни одна дверь устройство не завела, второй
     * проход — старый путь: сперва у дверей, ответивших 404/405, потом у тех, до кого первый проход не
     * дошёл за свой срок, последними — у не ответивших; в каждой группе в исходном порядке.
     *
     * Первый проход по очереди, а не ко всем дверям сразу: двери одной сети заводят устройство в одном
     * мозге, и каждое заведение выдаёт новый ключ — одновременные входы оставили бы уже заменённый ключ.
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
        aktivaciya: suspend (dver: String, srokMs: Long) -> Otvet?,
        znaetKod: suspend (String) -> Boolean,
        srokPervogoProhodaMs: Long = SROK_PERVOGO_PROHODA_MS,
    ): Itog {
        val starymPutem = mutableListOf<String>()
        val neSprosheny = mutableListOf<String>()
        val molchali = mutableListOf<String>()
        val konec = System.nanoTime() + srokPervogoProhodaMs * 1_000_000
        for (dver in dveri) {
            val ostalos = (konec - System.nanoTime()) / 1_000_000
            if (ostalos <= 0) {
                neSprosheny += dver
                continue
            }
            when (val o = withTimeoutOrNull(ostalos) { aktivaciya(dver, ostalos) }) {
                null -> molchali += dver
                Otvet.Sboy -> {}
                is Otvet.Ok -> return Itog.Novyj(dver, o.profil)
                is Otvet.Limit -> return Itog.Limit(o.tekst)
                Otvet.NetProtokola -> starymPutem += dver
            }
        }
        for (dver in starymPutem + neSprosheny + molchali) {
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
