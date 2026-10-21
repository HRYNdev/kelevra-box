package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.graphics.Color
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Внешний вид с сервера: цвета темы, тексты и включение функций — без выпуска новой версии.
 *
 * Приезжает необязательным блоком `vid` в той же сводке `/k/<код>/info`, что раздаёт
 * исключения и комнату (см. [io.nekohasekai.sfa.compose.screen.home.loadSubscription]).
 * Присланное ложится поверх встроенной темы, а не вместо неё: чего сервер не прислал,
 * то остаётся как было. Иконка, шрифты и код сюда не входят — только обновлением.
 * Кому что прислать (всем, человеку, устройству), решает сервер; сюда приходит итог.
 *
 * Последний годный блок хранится в настройках и применяется при старте без сети.
 * Сервер ответил без блока — вид снят, возвращаемся к встроенному. Сервер не ответил
 * или прислал мусор — держим последний годный.
 *
 * Формат (плоский): `{"versiya":1, "cveta":{"dark":{"Accent":"#RRGGBB"},"light":{...}},
 * "teksty":{"ключ":"строка"}, "funkcii":{"флаг":true}}`. Неизвестные токены и ключи,
 * невалидные цвета пропускаются поштучно, остальное применяется.
 */
object Vid {
    private const val TAG = "KelevraVid"

    /** Что можно перекрасить: ровно токены палитры [KColors], других нет. */
    private val TOKENY = setOf(
        "Bg", "Surface", "Sunken", "Border", "Text", "Dim", "Dim2",
        "Accent", "AccentInk", "Ok", "Warn", "Err",
    )
    private val CVET = Regex("^#[0-9A-Fa-f]{6}$")

    /** Длинная строка ломает раскладку круга и карточек — режем. */
    private const val TEKST_PREDEL = 80

    data class Blok(
        val dark: Map<String, Color> = emptyMap(),
        val light: Map<String, Color> = emptyMap(),
        val teksty: Map<String, String> = emptyMap(),
        val funkcii: Map<String, Boolean> = emptyMap(),
    ) {
        /** Текст по ключу; сервер его не прислал — встроенный [kak]. */
        fun tekst(klyuch: String, kak: String): String = teksty[klyuch] ?: kak

        /** Флаг по ключу; сервер его не прислал — поведение как в коде, [kak]. */
        fun funkciya(klyuch: String, kak: Boolean): Boolean = funkcii[klyuch] ?: kak
    }

    private val _blok = MutableStateFlow(izKesha())

    /** Текущий вид. Тема и экраны подписаны на него — смена видна без перезапуска. */
    val blok: StateFlow<Blok> = _blok.asStateFlow()

    /**
     * Сводка пришла. [syroy] — поле `vid` как есть: null — поля нет вовсе.
     * Вызывается только на успешный ответ сервера: сеть не ответила — сюда не попадаем.
     */
    fun izSvodki(syroy: Any?) {
        if (syroy == null || syroy == JSONObject.NULL) {
            if (Settings.vidServer.isNotEmpty()) {
                KLog.i(TAG, "сервер больше не присылает вид — возвращаю встроенный")
                Settings.vidServer = ""
            }
            _blok.value = Blok()
            return
        }
        val json = syroy as? JSONObject
        val blok = json?.let { runCatching { razobrat(it) }.getOrNull() }
        if (json == null || blok == null) {
            KLog.w(TAG, "вид от сервера не разобран — остаюсь на прежнем")
            return
        }
        val tekst = json.toString()
        if (tekst != Settings.vidServer) {
            KLog.i(
                TAG,
                "вид от сервера: цветов ${blok.dark.size}/${blok.light.size}, " +
                    "текстов ${blok.teksty.size}, флагов ${blok.funkcii.size}",
            )
            Settings.vidServer = tekst
        }
        _blok.value = blok
    }

    /** Наложить присланные цвета на встроенную палитру той же темы. */
    fun nalozhit(base: KColors, blok: Blok): KColors {
        val m = if (base.isDark) blok.dark else blok.light
        if (m.isEmpty()) return base
        return base.copy(
            Bg = m["Bg"] ?: base.Bg,
            Surface = m["Surface"] ?: base.Surface,
            Sunken = m["Sunken"] ?: base.Sunken,
            Border = m["Border"] ?: base.Border,
            Text = m["Text"] ?: base.Text,
            Dim = m["Dim"] ?: base.Dim,
            Dim2 = m["Dim2"] ?: base.Dim2,
            Accent = m["Accent"] ?: base.Accent,
            AccentInk = m["AccentInk"] ?: base.AccentInk,
            Ok = m["Ok"] ?: base.Ok,
            Warn = m["Warn"] ?: base.Warn,
            Err = m["Err"] ?: base.Err,
        )
    }

    /** null — блок целиком не того формата (другая версия). */
    internal fun razobrat(json: JSONObject): Blok? {
        if (json.optInt("versiya", 1) != 1) return null
        val cveta = json.optJSONObject("cveta")
        return Blok(
            dark = cvetaTemy(cveta?.optJSONObject("dark")),
            light = cvetaTemy(cveta?.optJSONObject("light")),
            teksty = buildMap {
                val obj = json.optJSONObject("teksty") ?: return@buildMap
                for (k in obj.keys()) {
                    val v = obj.opt(k) as? String ?: continue
                    v.trim().takeIf { it.isNotEmpty() }?.let { put(k, it.take(TEKST_PREDEL)) }
                }
            },
            funkcii = buildMap {
                val obj = json.optJSONObject("funkcii") ?: return@buildMap
                for (k in obj.keys()) (obj.opt(k) as? Boolean)?.let { put(k, it) }
            },
        )
    }

    private fun cvetaTemy(obj: JSONObject?): Map<String, Color> = buildMap {
        if (obj == null) return@buildMap
        for (k in obj.keys()) {
            if (k !in TOKENY) continue
            val v = (obj.opt(k) as? String)?.trim() ?: continue
            if (!CVET.matches(v)) continue
            put(k, Color(0xFF000000L or v.substring(1).toLong(16)))
        }
    }

    private fun izKesha(): Blok = runCatching {
        val tekst = Settings.vidServer
        if (tekst.isBlank()) Blok() else razobrat(JSONObject(tekst)) ?: Blok()
    }.getOrDefault(Blok())
}
