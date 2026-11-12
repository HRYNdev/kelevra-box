package io.nekohasekai.sfa.compose.screen.home

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Оплата подписки (KelevraServer/docs/OPLATA.md): `GET <база>/oplata`, `POST <база>/oplatil`,
 * где база — адрес сводки выбранного профиля ([bazaPodpiski]), тот же, что у `/info`.
 *
 * Всё в этом файле — чистая логика без Android и сети: разбор ответа и срок словами.
 * Проверяется юнит-тестами (OplataTest).
 */
data class OplataInfo(
    /** Показывать ли оплату вовсе. false — человек не платит (семья): на экране ничего нового. */
    val platit: Boolean,
    /** Рублей в месяц. */
    val cena: Int = 0,
    /** Ссылка Т-Банка на перевод; пусто — кнопки перевода нет. */
    val ssylka: String = "",
    /** Номер для перевода по СБП; пусто — строки номера нет. */
    val nomer: String = "",
    /** Доступ до (мс UTC); 0 — бессрочно или сервер не прислал. */
    val srokMs: Long = 0L,
    /** «Я оплатил» нажато, срок ещё не продлён. */
    val zhdet: Boolean = false,
    /** Подтверждённые оплаты, свежие первыми; нет — пусто. */
    val istoriya: List<OplataZapis> = emptyList(),
)

/** Подтверждённая оплата: когда заявка (мс UTC), сколько, до какого срока продлено (мс UTC; 0 — не прислано). */
data class OplataZapis(val dataMs: Long, val summa: Int, val doMs: Long)

/**
 * Разбор ответа `/oplata` и `/oplatil`. Ключа `platit` нет — это не ответ оплаты (старая выдача
 * на этом адресе отдаёт профиль целиком): null, оплату не показываем.
 */
fun razobratOplatu(json: JSONObject): OplataInfo? {
    if (!json.has("platit")) return null
    if (!json.optBoolean("platit")) return OplataInfo(platit = false)
    return OplataInfo(
        platit = true,
        cena = json.optInt("cena").coerceAtLeast(0),
        // Ссылку открываем браузером: только https, всё прочее — как нет ссылки.
        ssylka = json.optString("ssylka").chistyTekst().takeIf { it.startsWith("https://", ignoreCase = true) }.orEmpty(),
        nomer = json.optString("nomer").chistyTekst(),
        srokMs = srokMillis(json.optString("srok")),
        zhdet = json.optBoolean("zhdet"),
        istoriya = razobratIstoriyu(json),
    )
}

/** `istoriya`: записи без даты заявки пропускаем, порядок — как прислал сервер (свежие первыми). */
private fun razobratIstoriyu(json: JSONObject): List<OplataZapis> {
    val arr = json.optJSONArray("istoriya") ?: return emptyList()
    val out = mutableListOf<OplataZapis>()
    for (i in 0 until arr.length()) {
        val z = arr.optJSONObject(i) ?: continue
        val data = srokMillis(z.optString("data"))
        if (data <= 0L) continue
        out += OplataZapis(
            dataMs = data,
            summa = z.optInt("summa").coerceAtLeast(0),
            doMs = srokMillis(z.optString("do")),
        )
    }
    return out
}

/** ISO с зоной (как пишет мозг: `2026-12-12T07:00:00Z`); голая дата — полночь UTC; пусто или мусор — 0. */
internal fun srokMillis(tekst: String?): Long {
    val t = tekst.chistyTekst()
    if (t.isEmpty()) return 0L
    parseIsoMillis(t).takeIf { it > 0L }?.let { return it }
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(t)?.time ?: 0L
    }.getOrDefault(0L)
}

private fun String?.chistyTekst(): String = this?.trim()?.takeIf { it != "null" }.orEmpty()

/** Что со сроком: обычный, скоро кончится (≤ [SROK_SKORO_MS]), кончился. */
enum class SrokOplaty { Obychnyj, Skoro, Konchilsya }

/** Сколько до конца срока считается «скоро»: трое суток. */
const val SROK_SKORO_MS = 3L * 24 * 60 * 60 * 1000

fun srokOplaty(srokMs: Long, nowMs: Long): SrokOplaty = when {
    srokMs <= 0L -> SrokOplaty.Obychnyj
    srokMs <= nowMs -> SrokOplaty.Konchilsya
    srokMs - nowMs <= SROK_SKORO_MS -> SrokOplaty.Skoro
    else -> SrokOplaty.Obychnyj
}

/** Срок «ДД.ММ» по часам телефона. */
fun srokDdMm(srokMs: Long, zona: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("dd.MM", Locale.US).apply { timeZone = zona }.format(java.util.Date(srokMs))

/** Как часто спрашивать `/oplata`, пока приложение на экране: час; ждёт подтверждения — 5 минут. */
fun shagOprosaOplaty(zhdet: Boolean): Long = if (zhdet) 5L * 60 * 1000 else 60L * 60 * 1000

/** Дата заявки «ДД.ММ.ГГГГ» по часам телефона. */
fun dataDdMmGggg(ms: Long, zona: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = zona }.format(java.util.Date(ms))

/** Заголовок карточки «Подписка» у платящего. */
enum class ZagolovokOplaty {
    /** «Оплачено до ДД.ММ». */
    OplachenoDo,

    /** «Подписка до ДД.ММ» — срок на исходе. */
    PodpiskaDo,

    /** «Подписка закончилась». */
    Zakonchilas,

    /** Бессрочно: «Активна» / «Приостановлена», как у неплатящих. */
    KakBylo,
}

/** Строка оплаты под заголовком карточки. */
enum class StrokaOplaty { Oplatit, Proveryaetsya }

/**
 * Что показать в карточке «Подписка» на главном экране.
 * [zametno] — цвет предупреждения (рамка и заголовок); [stroka] null — строки оплаты нет.
 */
data class VidOplaty(val zagolovok: ZagolovokOplaty, val zametno: Boolean, val stroka: StrokaOplaty?)

/**
 * Обычный срок (> 3 суток) и бессрочно — без строки оплаты: платить заранее — из раздела подписки.
 * ≤ 3 суток и кончился — заметно и со строкой «Оплатить». Ждёт заявка — строка «Оплата проверяется» всегда.
 */
fun vidOplaty(info: OplataInfo, nowMs: Long): VidOplaty {
    val srok = srokOplaty(info.srokMs, nowMs)
    val zagolovok = when {
        srok == SrokOplaty.Konchilsya -> ZagolovokOplaty.Zakonchilas
        srok == SrokOplaty.Skoro -> ZagolovokOplaty.PodpiskaDo
        info.srokMs > 0L -> ZagolovokOplaty.OplachenoDo
        else -> ZagolovokOplaty.KakBylo
    }
    val zametno = srok == SrokOplaty.Skoro || srok == SrokOplaty.Konchilsya
    val stroka = when {
        info.zhdet -> StrokaOplaty.Proveryaetsya
        zametno -> StrokaOplaty.Oplatit
        else -> null
    }
    return VidOplaty(zagolovok, zametno, stroka)
}

/** Напоминание об оплате: за трое суток до срока или в день окончания. */
enum class VidNapominaniya { Skoro, Konec }

data class Napominanie(val vid: VidNapominaniya, val kogdaMs: Long)

/** Час напоминаний по часам телефона. */
const val CHAS_NAPOMINANIYA = 12

/** Срок позже этого часа — «закончилась» переносится на полдень следующего дня, не ночью. */
const val CHAS_NOCH = 22

/** [CHAS_NAPOMINANIYA]:00 того же календарного дня, что [ms], в зоне [zona]. */
private fun poldenDnya(ms: Long, zona: TimeZone, sdvigDney: Int = 0): Long =
    java.util.Calendar.getInstance(zona, Locale.US).apply {
        timeInMillis = ms
        if (sdvigDney != 0) add(java.util.Calendar.DAY_OF_MONTH, sdvigDney)
        set(java.util.Calendar.HOUR_OF_DAY, CHAS_NAPOMINANIYA)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Какие напоминания поставить по ответу `/oplata`. Нет оплаты, бессрочно или заявка ждёт — никаких.
 *
 * - [VidNapominaniya.Skoro] — в 12:00 дня, который на трое суток раньше срока. Это время прошло — не ставим
 *   (задним числом не показываем).
 * - [VidNapominaniya.Konec] — не раньше срока: срок до полудня — в 12:00 того же дня; с полудня до 22:00 —
 *   в момент срока; с 22:00 — в 12:00 следующего дня. Срок уже прошёл — не ставим: человек видит это в приложении.
 */
fun napominaniya(info: OplataInfo?, nowMs: Long, zona: TimeZone = TimeZone.getDefault()): List<Napominanie> {
    if (info == null || !info.platit || info.zhdet || info.srokMs <= 0L) return emptyList()
    val srok = info.srokMs
    if (srok <= nowMs) return emptyList()
    val out = mutableListOf<Napominanie>()
    val skoro = poldenDnya(srok, zona, sdvigDney = -3)
    if (skoro > nowMs && skoro < srok) out += Napominanie(VidNapominaniya.Skoro, skoro)
    val chas = java.util.Calendar.getInstance(zona, Locale.US).apply { timeInMillis = srok }
        .get(java.util.Calendar.HOUR_OF_DAY)
    val konec = when {
        chas < CHAS_NAPOMINANIYA -> poldenDnya(srok, zona)
        chas < CHAS_NOCH -> srok
        else -> poldenDnya(srok, zona, sdvigDney = 1)
    }
    out += Napominanie(VidNapominaniya.Konec, konec)
    return out
}
