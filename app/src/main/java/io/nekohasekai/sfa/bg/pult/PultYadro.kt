package io.nekohasekai.sfa.bg.pult

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Пульт без Android: приём блока, испытание и откат, память команд, заголовок `X-Pult`.
 * Всё здесь — чистые функции над [PultSostoyanie]; диск, сеть и исполнение команд —
 * в [Pult]. Договор — KelevraServer/docs/PULT.md.
 */
object PultYadro {
    const val V = 1

    /** Растёт с каждым новым умением клиента (PULT.md, `umeet`). */
    const val UMEET = 1

    const val PREFIKS_PODPISI = "kelevra-pult-v1\n"

    /** Вшитые открытые ключи: основной (мозг) и запасной (хост kelevra). */
    val KLYUCHI_BOEVYE: List<ByteArray> = listOf(
        Ed25519.izHex("89f9504b7dfa4bad83b8f781e8fb81225e5883739ac502804669429b1a6ab552"),
        Ed25519.izHex("56b5e97341b4aa0828a5660faee970aecbb25291ec1e9e57505bd462f5230e90"),
    )

    /**
     * Чем проверяется подпись: боевые и — только в debug-сборке ([io.nekohasekai.sfa.BuildConfig.DEBUG],
     * объект [PultProbaKlyuch] из `src/debug`) — третий, проверочный ключ стенда. В релизе его нет.
     */
    val KLYUCHI: List<ByteArray> = KLYUCHI_BOEVYE +
        listOfNotNull(PultProbaKlyuch.HEX?.takeIf { io.nekohasekai.sfa.BuildConfig.DEBUG }?.let { Ed25519.izHex(it) })

    const val ISPYTANIE_MS = 10 * 60_000L

    /** Шаг испытания между двумя отметками не больше этого: сон телефона не засчитывается. */
    const val SHAG_MAX_MS = 2 * 60_000L

    /**
     * Падений до отката. Падение = старт процесса, который не прожил [ZHIZN_BEZ_PADENIYA_MS]:
     * свой счётчик стартов растёт на каждом старте и обнуляется, когда процесс прожил столько
     * ([prozhilBezPadeniya]). Так ловятся и падения ядра на Go (мимо ловушки Java), а обычное
     * убийство процесса системой спустя часы падением не считается.
     */
    const val PADENIY_DO_OTKATA = 3
    const val ZHIZN_BEZ_PADENIYA_MS = 2 * 60_000L

    const val PAMYAT_KOMAND_MS = 7 * 24 * 3600_000L

    /** Команды v1 Box (PULT.md + «Главная цель», п. 4). */
    val KOMANDY = setOf(
        "prishli_zhurnal", "obnovi_profil", "pereydi_na_klyuch", "sbros_avtomata", "proverit_obnovlenie",
        "diagnostika", "proba",
    )

    /** Команды, которые ждут ответа сервера на журнал и потому идут мимо общей очереди. */
    val BEZ_OCHEREDI = setOf("prishli_zhurnal", "diagnostika")

    const val IDET = "idet"

    data class Komanda(val id: String, val chto: String, val doMs: Long?, val doBityy: Boolean, val param: JSONObject)

    /**
     * Блок `obnovlenie`: [versiyaKod] — versionCode сборки (как в kelevra-version.json, 711147), с ним
     * сравнивается установленный; [versiyaImya] — «1.14.147», только для показа.
     */
    data class Obnovlenie(
        val versiyaKod: Int,
        val url: String,
        val sha256: String,
        val razmer: Long,
        val zametka: String?,
        val versiyaImya: String? = null,
    ) {
        fun vJson(): JSONObject = JSONObject()
            .put("versiya_kod", versiyaKod).put("url", url).put("sha256", sha256).put("razmer", razmer)
            .apply { if (zametka != null) put("zametka", zametka) }
            .apply { if (versiyaImya != null) put("versiya_imya", versiyaImya) }

        companion object {
            fun izJson(j: JSONObject?): Obnovlenie? {
                j ?: return null
                val kod = j.optInt("versiya_kod", 0)
                val url = j.optString("url").trim()
                val sha = j.optString("sha256").trim().lowercase()
                if (kod <= 0 || !url.startsWith("https://") || !Regex("^[0-9a-f]{64}$").matches(sha)) return null
                return Obnovlenie(
                    kod, url, sha, j.optLong("razmer", 0L).coerceAtLeast(0L),
                    j.optString("zametka").takeIf { it.isNotBlank() && it != "null" },
                    j.optString("versiya_imya").trim().takeIf { it.isNotBlank() && it != "null" }?.take(40),
                )
            }
        }
    }

    data class Soderzhimoe(
        val v: Int,
        val versiya: Long,
        val komu: String,
        val nastroyki: JSONObject,
        val komandy: List<Komanda>,
        val obnovlenie: Obnovlenie?,
    )

    /** Задание исполнителю: команда, которую пора выполнить. */
    data class Zadanie(val id: String, val chto: String, val param: JSONObject)

    data class Itog(
        val sostoyanie: PultSostoyanie,
        val zadaniya: List<Zadanie> = emptyList(),
        /** Строки журнала: без кодов и ключей, только факты. */
        val zhurnal: List<String> = emptyList(),
        /** Настройки в силе поменялись (новые на испытании). */
        val nastroykiIzmenilis: Boolean = false,
    )

    // ---- разбор и проверка ----

    sealed class Razbor {
        data class Ok(val s: Soderzhimoe) : Razbor()
        data class Otkaz(val prichina: String) : Razbor()
    }

    fun razobrat(
        blok: JSONObject,
        deviceId: String,
        sohranyonnayaVersiya: Long,
        klyuchi: List<ByteArray> = KLYUCHI,
    ): Razbor {
        val dannye = PultBase64.razobrat(blok.optString("dannye")) ?: return Razbor.Otkaz("podpis")
        val podpis = PultBase64.razobrat(blok.optString("podpis")) ?: return Razbor.Otkaz("podpis")
        val podpisano = PREFIKS_PODPISI.toByteArray(Charsets.UTF_8) + dannye
        if (klyuchi.none { Ed25519.proverit(it, podpisano, podpis) }) return Razbor.Otkaz("podpis")
        val j = runCatching { JSONObject(String(dannye, Charsets.UTF_8)) }.getOrNull() ?: return Razbor.Otkaz("format")
        val v = j.optInt("v", -1)
        if (v != V) return Razbor.Otkaz("v")
        if (j.optString("komu") != deviceId) return Razbor.Otkaz("komu")
        val versiya = j.optLong("versiya", -1L)
        if (versiya < 0) return Razbor.Otkaz("format")
        if (versiya < sohranyonnayaVersiya) return Razbor.Otkaz("versiya")
        val komandy = mutableListOf<Komanda>()
        j.optJSONArray("komandy")?.let { arr ->
            for (i in 0 until arr.length()) {
                val k = arr.optJSONObject(i) ?: continue
                val id = k.optString("id").trim()
                if (id.isEmpty()) continue
                val doTekst = k.optString("do").takeIf { it.isNotBlank() && it != "null" }
                val doMs = PultVremya.razobrat(doTekst)
                komandy += Komanda(
                    id = id,
                    chto = k.optString("chto").trim(),
                    doMs = doMs,
                    doBityy = doTekst != null && doMs == null,
                    param = k.optJSONObject("param") ?: JSONObject(),
                )
            }
        }
        return Razbor.Ok(
            Soderzhimoe(
                v = v,
                versiya = versiya,
                komu = deviceId,
                nastroyki = j.optJSONObject("nastroyki") ?: JSONObject(),
                komandy = komandy,
                obnovlenie = Obnovlenie.izJson(j.optJSONObject("obnovlenie")),
            ),
        )
    }

    /** Ключи пульта → проверенные значения и ключи, которые не приняты. */
    fun proveritNastroyki(n: JSONObject): Pair<Map<String, Any>, List<String>> {
        val ok = sortedMapOf<String, Any>()
        val plohie = mutableListOf<String>()
        for (imya in n.keys()) {
            val v = PultReestr.proverit(imya, n.opt(imya))
            if (v == null) plohie += imya else ok[imya] = v
        }
        return ok to plohie.sorted()
    }

    /** Отпечаток набора настроек: одинаковые наборы под разными версиями — одно и то же. */
    fun otpechatok(n: Map<String, Any>): String {
        val tekst = n.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=${if (v is List<*>) v.joinToString(",") else v}" }
        return MessageDigest.getInstance("SHA-256").digest(tekst.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }

    // ---- приём ----

    /**
     * Приём блока `pult` из /info. [blok] null — сервер блока не прислал: не меняется ничего.
     */
    fun prinyat(
        s: PultSostoyanie,
        blok: JSONObject?,
        deviceId: String,
        nowMs: Long,
        klyuchi: List<ByteArray> = KLYUCHI,
    ): Itog {
        if (blok == null) return Itog(s)
        val razbor = razobrat(blok, deviceId, s.versiya, klyuchi)
        if (razbor is Razbor.Otkaz) {
            return Itog(
                s.copy(oshibkaPulta = razbor.prichina),
                zhurnal = listOf("пульт отвергнут: ${razbor.prichina}, держу принятую версию ${s.versiya}"),
            )
        }
        val d = (razbor as Razbor.Ok).s
        val zhurnal = mutableListOf<String>()
        var novoe = s.copy(versiya = d.versiya, oshibkaPulta = null, obnovlenie = d.obnovlenie)
        // Другая выкатка — прошлые её сбои в заголовке больше не про неё.
        if (d.obnovlenie != s.obnovlenie) novoe = novoe.copy(oshibkiObnovleniya = emptyList())

        // Настройки.
        val (ok, plohieKlyuchi) = proveritNastroyki(d.nastroyki)
        novoe = novoe.copy(oshibkiKlyuchey = plohieKlyuchi)
        if (plohieKlyuchi.isNotEmpty()) zhurnal += "пульт v${d.versiya}: не приняты ключи ${plohieKlyuchi.joinToString(",")}"
        val otp = otpechatok(ok)
        var izmenilis = false
        when {
            otp == otpechatok(novoe.nastroyki) -> Unit
            d.versiya in novoe.plohieVersii || otp in novoe.plohieOtpechatki -> {
                zhurnal += "пульт v${d.versiya}: эти настройки уже откатывались — не применяю"
            }
            else -> {
                val proshlye = if (novoe.ispytanie != null) novoe.proshlye else novoe.nastroyki
                novoe = novoe.copy(
                    nastroyki = ok,
                    proshlye = proshlye,
                    ispytanie = PultSostoyanie.Ispytanie(versiya = d.versiya, otpechatok = otp, nachaloMs = nowMs, otmetkaMs = nowMs, posledniyStartMs = nowMs),
                    otkat = false,
                )
                izmenilis = true
                zhurnal += "пульт v${d.versiya}: новые настройки (${ok.size} шт.) на испытании ${ISPYTANIE_MS / 60_000} мин"
            }
        }

        // Команды.
        val pamyat = novoe.komandy.toMutableMap()
        val zadaniya = mutableListOf<Zadanie>()
        val prislannye = d.komandy.map { it.id }.toSet()
        for (k in d.komandy) {
            val bylo = pamyat[k.id]
            if (bylo != null) {
                pamyat[k.id] = bylo.copy(snyata = false)
                continue
            }
            val itog = when {
                k.doBityy -> "oshibka-srok"
                k.doMs != null && nowMs > k.doMs -> "istekla"
                k.chto !in KOMANDY -> "ne_umeyu"
                else -> null
            }
            if (itog == null) {
                pamyat[k.id] = PultSostoyanie.Kvit(IDET, nowMs)
                zadaniya += Zadanie(k.id, k.chto, k.param)
            } else {
                pamyat[k.id] = PultSostoyanie.Kvit(itog, nowMs)
                zhurnal += "команда ${chistyyId(k.id)} (${chistyyId(k.chto)}): $itog"
            }
        }
        // Сервер команду больше не шлёт — квитанция дошла, в заголовке её больше не держим.
        for ((id, kv) in pamyat.entries.toList()) {
            if (id !in prislannye && kv.itog != IDET) pamyat[id] = kv.copy(snyata = true)
        }
        novoe = novoe.copy(komandy = pamyat).ubrat(nowMs)
        return Itog(novoe, zadaniya, zhurnal, izmenilis)
    }

    /** Команда выполнена исполнителем. */
    fun zavershit(s: PultSostoyanie, id: String, itog: String, nowMs: Long): PultSostoyanie {
        val kv = s.komandy[id] ?: PultSostoyanie.Kvit(itog, nowMs)
        return s.copy(komandy = s.komandy + (id to kv.copy(itog = itog, kogdaMs = nowMs)))
    }

    /** Процесс умер посреди команды: «идёт» после перезапуска не кончится уже никогда. */
    fun prervannye(s: PultSostoyanie, nowMs: Long): PultSostoyanie =
        s.copy(komandy = s.komandy.mapValues { (_, kv) -> if (kv.itog == IDET) kv.copy(itog = "oshibka-prervano", kogdaMs = nowMs) else kv })

    // ---- испытание ----

    /**
     * Отметка испытания. [nablyudaem] — служба жива и сеть есть (судить можно);
     * [zhivoy] — канал проверен живым ([io.nekohasekai.sfa.bg.AutoMode.Link.Alive]).
     */
    fun otmetit(s: PultSostoyanie, nowMs: Long, nablyudaem: Boolean, zhivoy: Boolean): Itog {
        val isp = s.ispytanie ?: return Itog(s)
        val shag = (nowMs - isp.otmetkaMs).coerceIn(0L, SHAG_MAX_MS)
        val nablyudeno = isp.nablyudenoMs + if (nablyudaem) shag else 0L
        val novoeIsp = isp.copy(otmetkaMs = nowMs, nablyudenoMs = nablyudeno, zhivoy = isp.zhivoy || (nablyudaem && zhivoy))
        if (nablyudeno < ISPYTANIE_MS) return Itog(s.copy(ispytanie = novoeIsp))
        if (novoeIsp.zhivoy) {
            return Itog(
                s.copy(ispytanie = null, proshlye = null),
                zhurnal = listOf("пульт v${isp.versiya}: испытание пройдено, настройки приняты"),
            )
        }
        return otkatit(s, "за ${ISPYTANIE_MS / 60_000} мин при сети канал ни разу не ожил")
    }

    /**
     * Старт процесса на испытании. Первый старт после начала испытания — ещё не падение
     * (процесс, где пульт принят, жил); каждый следующий без [prozhilBezPadeniya] между
     * ними — падение. [PADENIY_DO_OTKATA] подряд — откат.
     */
    fun naStarte(s: PultSostoyanie, nowMs: Long): Itog {
        val s1 = prervannye(s, nowMs)
        val isp = s1.ispytanie ?: return Itog(s1)
        val startov = isp.startov + 1
        if (startov >= PADENIY_DO_OTKATA) return otkatit(s1, "процесс падал $startov раза подряд на испытании, не проживая ${ZHIZN_BEZ_PADENIYA_MS / 60_000} мин")
        // Время, пока процесса не было, испытанию не засчитывается.
        return Itog(s1.copy(ispytanie = isp.copy(startov = startov, posledniyStartMs = nowMs, otmetkaMs = nowMs)))
    }

    /** Процесс прожил [ZHIZN_BEZ_PADENIYA_MS] — счётчик падений с нуля. */
    fun prozhilBezPadeniya(s: PultSostoyanie): PultSostoyanie {
        val isp = s.ispytanie ?: return s
        return if (isp.startov == 0) s else s.copy(ispytanie = isp.copy(startov = 0))
    }

    fun otkatit(s: PultSostoyanie, prichina: String): Itog {
        val isp = s.ispytanie ?: return Itog(s)
        return Itog(
            s.copy(
                nastroyki = s.proshlye ?: emptyMap(),
                proshlye = null,
                ispytanie = null,
                plohieVersii = (s.plohieVersii + isp.versiya).toList().takeLast(50).toSet(),
                plohieOtpechatki = (s.plohieOtpechatki + isp.otpechatok).toList().takeLast(50).toSet(),
                otkat = true,
            ),
            zhurnal = listOf("пульт v${isp.versiya}: откат на прошлые настройки — $prichina"),
            nastroykiIzmenilis = true,
        )
    }

    // ---- заголовок ----

    private val NE_ASCII = Regex("[^A-Za-z0-9_.:-]")

    fun chistyyId(id: String): String = id.replace(NE_ASCII, "_").take(48)

    /**
     * `X-Pult: v=1;umeet=1;versiya=42;kod=711147;kvit=k17:ok;oshibki=opros.info_min,pult:otkat`.
     * [kod] — versionCode установленного приложения: по нему сервер решает, новее ли сборка блока
     * `obnovlenie` (0 — не слать).
     */
    fun zagolovok(s: PultSostoyanie, dop: List<String> = emptyList(), kod: Int = 0): String {
        val chasti = mutableListOf("v=$V", "umeet=$UMEET", "versiya=${s.versiya}")
        if (kod > 0) chasti += "kod=$kod"
        val kvit = s.komandy.entries
            .filter { !it.value.snyata && it.value.itog != IDET }
            .sortedBy { it.value.kogdaMs }
            .takeLast(20)
            .joinToString(",") { "${chistyyId(it.key)}:${chistyyId(it.value.itog)}" }
        if (kvit.isNotEmpty()) chasti += "kvit=$kvit"
        val oshibki = buildList {
            addAll(s.oshibkiKlyuchey.take(20).map(::chistyyId))
            s.oshibkaPulta?.let { add("pult:${chistyyId(it)}") }
            if (s.otkat) add("pult:otkat")
            addAll(s.oshibkiObnovleniya.map { "obnovlenie:${chistyyId(it)}" })
            addAll(dop.map(::chistyyId))
        }
        if (oshibki.isNotEmpty()) chasti += "oshibki=" + oshibki.joinToString(",")
        return chasti.joinToString(";")
    }
}

/** Всё, что пульт помнит между запусками. Пишется одним файлом ([vJson]). */
data class PultSostoyanie(
    /** Последняя принятая версия пульта: ниже неё блоки не принимаются (откат версии). */
    val versiya: Long = 0L,
    /** Настройки в силе (на испытании — новые). */
    val nastroyki: Map<String, Any> = emptyMap(),
    /** Что вернуть при откате; null — испытания нет. */
    val proshlye: Map<String, Any>? = null,
    val ispytanie: Ispytanie? = null,
    val plohieVersii: Set<Long> = emptySet(),
    val plohieOtpechatki: Set<String> = emptySet(),
    val otkat: Boolean = false,
    val oshibkiKlyuchey: List<String> = emptyList(),
    val oshibkaPulta: String? = null,
    val komandy: Map<String, Kvit> = emptyMap(),
    /** Блок `obnovlenie` последнего принятого пульта. */
    val obnovlenie: PultYadro.Obnovlenie? = null,
    /** Сбои выкатки через пульт для заголовка: `hesh`, `skachat`, … (по одному на версию). */
    val oshibkiObnovleniya: List<String> = emptyList(),
    /** Выкатка, которую больше не пробуем: «versiya_kod:sha256» — файл не сошёлся с подписанным хешем. */
    val plohieObnovleniya: Set<String> = emptySet(),
) {
    data class Ispytanie(
        val versiya: Long,
        val otpechatok: String,
        val nachaloMs: Long,
        val otmetkaMs: Long,
        val nablyudenoMs: Long = 0L,
        val zhivoy: Boolean = false,
        val startov: Int = 0,
        val posledniyStartMs: Long = nachaloMs,
    )

    data class Kvit(val itog: String, val kogdaMs: Long, val snyata: Boolean = false)

    /** Память команд не дольше 7 суток. */
    fun ubrat(nowMs: Long): PultSostoyanie =
        copy(komandy = komandy.filterValues { nowMs - it.kogdaMs < PultYadro.PAMYAT_KOMAND_MS || it.itog == PultYadro.IDET })

    fun vJson(): JSONObject {
        val j = JSONObject()
        j.put("versiya", versiya)
        j.put("nastroyki", mapVJson(nastroyki))
        proshlye?.let { j.put("proshlye", mapVJson(it)) }
        ispytanie?.let {
            j.put(
                "ispytanie",
                JSONObject().put("versiya", it.versiya).put("otpechatok", it.otpechatok).put("nachalo", it.nachaloMs)
                    .put("otmetka", it.otmetkaMs).put("nablyudeno", it.nablyudenoMs).put("zhivoy", it.zhivoy)
                    .put("startov", it.startov).put("posledniy_start", it.posledniyStartMs),
            )
        }
        j.put("plohie_versii", JSONArray(plohieVersii.toList()))
        j.put("plohie_otpechatki", JSONArray(plohieOtpechatki.toList()))
        j.put("otkat", otkat)
        j.put("oshibki_klyuchey", JSONArray(oshibkiKlyuchey))
        oshibkaPulta?.let { j.put("oshibka_pulta", it) }
        val k = JSONObject()
        for ((id, kv) in komandy) k.put(id, JSONObject().put("itog", kv.itog).put("kogda", kv.kogdaMs).put("snyata", kv.snyata))
        j.put("komandy", k)
        obnovlenie?.let { j.put("obnovlenie", it.vJson()) }
        j.put("oshibki_obnovleniya", JSONArray(oshibkiObnovleniya))
        j.put("plohie_obnovleniya", JSONArray(plohieObnovleniya.toList()))
        return j
    }

    companion object {
        private fun mapVJson(m: Map<String, Any>): JSONObject {
            val j = JSONObject()
            for ((k, v) in m) j.put(k, if (v is List<*>) JSONArray(v) else v)
            return j
        }

        /** Настройки с диска проходят ту же проверку, что и с сети: устаревший ключ просто выпадает. */
        private fun mapIzJson(j: JSONObject?): Map<String, Any> {
            j ?: return emptyMap()
            return PultYadro.proveritNastroyki(j).first
        }

        private fun <T> spisok(arr: JSONArray?, f: (JSONArray, Int) -> T?): List<T> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { f(arr, it) }

        fun izJson(j: JSONObject): PultSostoyanie {
            val isp = j.optJSONObject("ispytanie")?.let {
                PultSostoyanie.Ispytanie(
                    versiya = it.optLong("versiya"),
                    otpechatok = it.optString("otpechatok"),
                    nachaloMs = it.optLong("nachalo"),
                    otmetkaMs = it.optLong("otmetka"),
                    nablyudenoMs = it.optLong("nablyudeno"),
                    zhivoy = it.optBoolean("zhivoy"),
                    startov = it.optInt("startov"),
                    posledniyStartMs = it.optLong("posledniy_start", it.optLong("nachalo")),
                )
            }
            val komandy = mutableMapOf<String, Kvit>()
            j.optJSONObject("komandy")?.let { k ->
                for (id in k.keys()) {
                    val o = k.optJSONObject(id) ?: continue
                    komandy[id] = Kvit(o.optString("itog"), o.optLong("kogda"), o.optBoolean("snyata"))
                }
            }
            return PultSostoyanie(
                versiya = j.optLong("versiya"),
                nastroyki = mapIzJson(j.optJSONObject("nastroyki")),
                proshlye = j.optJSONObject("proshlye")?.let { mapIzJson(it) },
                ispytanie = isp,
                plohieVersii = spisok(j.optJSONArray("plohie_versii")) { a, i -> a.optLong(i) }.toSet(),
                plohieOtpechatki = spisok(j.optJSONArray("plohie_otpechatki")) { a, i -> a.optString(i) }.toSet(),
                otkat = j.optBoolean("otkat"),
                oshibkiKlyuchey = spisok(j.optJSONArray("oshibki_klyuchey")) { a, i -> a.optString(i) },
                oshibkaPulta = j.optString("oshibka_pulta").takeIf { it.isNotBlank() },
                komandy = komandy,
                obnovlenie = PultYadro.Obnovlenie.izJson(j.optJSONObject("obnovlenie")),
                oshibkiObnovleniya = spisok(j.optJSONArray("oshibki_obnovleniya")) { a, i -> a.optString(i) },
                plohieObnovleniya = spisok(j.optJSONArray("plohie_obnovleniya")) { a, i -> a.optString(i) }.toSet(),
            )
        }
    }
}
