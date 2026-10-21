package io.nekohasekai.sfa.bg.pult

import org.json.JSONArray

/**
 * Реестр пульта: какие ключи клиент понимает, их тип, границы и значение по умолчанию.
 *
 * Значение по умолчанию — ровно нынешняя константа в коде: без пульта поведение байт в байт
 * прежнее. Ключ, которого здесь нет, клиент не принимает (`oshibki=<ключ>`). Таблица
 * `docs/PULT-KLYUCHI-box.md` генерируется отсюда ([markdown]) и сверяется тестом.
 */
object PultReestr {
    enum class Tip(val slovo: String) {
        CELOE("int"),
        LOGICH("bool"),

        /** ISO-8601 с зоной; хранится строкой, читается миллисекундами. */
        VREMYA("ISO-время"),

        /** Один из [Klyuch.varianty]. */
        VYBOR("выбор"),

        /** Список непустых строк (домены, подсети, адреса); каждая — по [Klyuch.shablon]. */
        SPISOK("список"),

        /** Одна строка по [Klyuch.shablon] (имя, цель пробы). */
        STROKA("строка"),
    }

    data class Klyuch(
        val imya: String,
        val tip: Tip,
        /** Значение из кода; null — «нет» (у времени и списков). */
        val umolch: Any?,
        val min: Long? = null,
        val max: Long? = null,
        val varianty: List<String> = emptyList(),
        val opisanie: String,
        /** Где в коде живёт значение. */
        val gde: String,
        /** Шаблон строки (у [Tip.STROKA] и у каждой строки [Tip.SPISOK]); null — любая. */
        val shablon: Regex? = null,
    )

    /** Потолок длины списка: белый список оператора в APK — сотни строк, с запасом. */
    const val SPISOK_MAX = 5000
    private const val STROKA_SPISKA_MAX = 256

    private fun celoe(imya: String, umolch: Int, min: Long, max: Long, opisanie: String, gde: String) =
        Klyuch(imya, Tip.CELOE, umolch, min, max, opisanie = opisanie, gde = gde)

    private fun logich(imya: String, umolch: Boolean, opisanie: String, gde: String) =
        Klyuch(imya, Tip.LOGICH, umolch, opisanie = opisanie, gde = gde)

    private fun spisok(imya: String, opisanie: String, gde: String) =
        Klyuch(imya, Tip.SPISOK, null, opisanie = opisanie, gde = gde)

    /** Выключатель подсистемы: `vykl.<имя>` = true гасит её с сервера. */
    private fun vykl(imya: String, opisanie: String, gde: String) =
        Klyuch("vykl.$imya", Tip.LOGICH, false, opisanie = opisanie, gde = gde)

    // ---- v1 по договору (PULT.md, «Настройки v1») ----
    private val V1 = listOf(
        celoe("opros.info_min", 60, 5, 240, "интервал профиля и сводки /info (расписание и опрос из службы), мин", "UpdateProfileWork, PultSvyaz"),
        celoe("zhurnal.otpravka_min", 60, 10, 1440, "период отправки журнала, мин (WorkManager не чаще 15)", "LogUploadWork.schedule0"),
        Klyuch("zhurnal.chasto_do", Tip.VREMYA, null, opisanie = "до этого времени журнал уходит каждые 10 мин", gde = "LogUploadWork"),
        celoe("avtomat.podtverzhdeniy", 3, 1, 10, "сколько одинаковых наблюдений подряд меняют обстановку", "AutoMode.CONFIRMATIONS"),
        celoe("avtomat.tishina_rezolvera_min", 15, 1, 120, "сколько держим вердикт, пока резолверы молчат, мин", "AutoMode.SILENCE_HOLD_MILLIS"),
        celoe("uzel.porog_otkazov", 3, 1, 20, "отказов узла подряд до перехода на следующий", "CoreLog.UZEL_POROG"),
        celoe("uzel.vozvrat_na_pervyy_min", 4, 1, 120, "через сколько минут на запасном узле пробовать первый", "AutoModeNodeWatch.RETURN_AFTER_MILLIS"),
        logich("obnovleniya.tiho", false, "ставить обновления без подтверждения (поверх настройки в приложении)", "Settings.silentInstallEnabled"),
        celoe("obnovleniya.napominat_chas", 24, 1, 48, "напоминание о неустановленном обновлении, ч", "IshodUstanovki.NAPOMINANIE_MINUT"),
        logich("funkcii.komnata", true, "комната (olcRTC) разрешена", "OlcRtcParams.hasRoom"),
        logich("funkcii.avtorezhim", true, "автомат выбирает выход сам", "AutoMode"),
        Klyuch(
            "obnovleniya.kanal", Tip.VYBOR, "obychnyy", varianty = listOf("obychnyy", "proba", "stop"),
            opisanie = "obychnyy — выпуски GitHub; proba — только блок obnovlenie из пульта; stop — не обновляться",
            gde = "Vendor.checkUpdateAsync",
        ),
        logich("kanal.dolgiy_opros", false, "долгий опрос …/pult/zhdat из службы (мгновенные команды)", "PultSvyaz"),
    )

    /** Все ключи: v1 договора и настроечные константы подсистем ([PultKonstanty], п. 1 «Главной цели»). */
    val vse: List<Klyuch> by lazy { (V1 + PultKonstanty.KLYUCHI).distinctBy { it.imya } }

    private val poImeni: Map<String, Klyuch> by lazy { vse.associateBy { it.imya } }

    fun klyuch(imya: String): Klyuch? = poImeni[imya]

    internal fun vyklyuchatel(imya: String, opisanie: String, gde: String) = vykl(imya, opisanie, gde)
    internal fun celoeKlyuch(imya: String, umolch: Int, min: Long, max: Long, opisanie: String, gde: String) =
        celoe(imya, umolch, min, max, opisanie, gde)
    internal fun spisokKlyuch(imya: String, opisanie: String, gde: String) = spisok(imya, opisanie, gde)

    /**
     * Проверка одного значения из пульта. Успех — значение в виде, в каком его хранить
     * (Int, Boolean, String, List<String>); провал — null.
     */
    fun proverit(imya: String, syroe: Any?): Any? {
        val k = klyuch(imya) ?: return null
        return when (k.tip) {
            Tip.CELOE -> {
                val n = syroe as? Number ?: return null
                val d = n.toDouble()
                if (d != Math.floor(d) || d.isInfinite()) return null
                val v = n.toLong()
                if (k.min != null && v < k.min) return null
                if (k.max != null && v > k.max) return null
                v.toInt()
            }
            Tip.LOGICH -> syroe as? Boolean
            Tip.VREMYA -> (syroe as? String)?.takeIf { PultVremya.razobrat(it) != null }
            Tip.VYBOR -> (syroe as? String)?.takeIf { it in k.varianty }
            Tip.SPISOK -> {
                val arr = syroe as? JSONArray ?: return null
                if (arr.length() > SPISOK_MAX) return null
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.opt(i) as? String ?: return null
                    val t = s.trim()
                    if (t.isEmpty() || t.length > STROKA_SPISKA_MAX) return null
                    if (k.shablon != null && !k.shablon.matches(t)) return null
                    out += t
                }
                out
            }
            Tip.STROKA -> (syroe as? String)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= STROKA_SPISKA_MAX && (k.shablon == null || k.shablon.matches(it)) }
        }
    }

    /** Таблица ключей для `docs/PULT-KLYUCHI-box.md`. */
    fun markdown(): String = buildString {
        append("# Ключи пульта: Kelevra Box\n\n")
        append("Генерируется из реестра клиента (`app/src/main/java/io/nekohasekai/sfa/bg/pult/PultReestr.kt`, ")
        append("`PultKonstanty.kt`); правка руками затрётся. Сверяет тест `PultKlyuchiDocTest`.\n")
        append("Значение по умолчанию = константа в коде: без пульта поведение прежнее. Протокол — `PULT.md`.\n\n")
        append("| Ключ | Тип | Границы | По умолчанию | Что | Где в коде |\n")
        append("|---|---|---|---|---|---|\n")
        for (k in vse.sortedBy { it.imya }) {
            val granicy = when (k.tip) {
                Tip.CELOE -> "${k.min}–${k.max}"
                Tip.VYBOR -> k.varianty.joinToString("\\|")
                Tip.SPISOK -> "≤ $SPISOK_MAX строк" + (k.shablon?.let { ", " + PultKonstanty.slovoShablona(it) } ?: "")
                Tip.STROKA -> k.shablon?.let { PultKonstanty.slovoShablona(it) } ?: "—"
                else -> "—"
            }
            val umolch = when (val u = k.umolch) {
                null -> if (k.tip == Tip.SPISOK || k.tip == Tip.STROKA) "из кода" else "нет"
                else -> u.toString()
            }
            append("| `${k.imya}` | ${k.tip.slovo} | $granicy | $umolch | ${k.opisanie.replace("|", "\\|")} | ${k.gde} |\n")
        }
    }
}

/** Разбор ISO-времени пульта: `2026-10-22T12:00:00Z`, со смещением, с долями секунды. */
object PultVremya {
    private val SHABLONY = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssX",
    )

    fun razobrat(tekst: String?): Long? {
        val t = tekst?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (s in SHABLONY) {
            val r = runCatching {
                java.text.SimpleDateFormat(s, java.util.Locale.US).apply { isLenient = false }.parse(t)
            }.getOrNull()
            if (r != null) return r.time
        }
        return null
    }
}
