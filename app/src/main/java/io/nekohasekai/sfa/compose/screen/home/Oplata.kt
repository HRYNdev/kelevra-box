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
)

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
    )
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
