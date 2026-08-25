// VREMENNO: vybor palitry, ubrat posle resheniya Vovy (25.08.2026)
// Весь файл временный: удаляется целиком вместе с экраном PaletteTrialScreen.kt
// и точками подключения, помеченными тем же комментарием.
package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.nekohasekai.sfa.database.Settings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Один пробный оттенок акцента: пара «тёмная тема / светлая тема». */
data class TrialAccent(
    val title: String,
    val dark: Color,
    val light: Color,
)

/**
 * Ровно тот список и в том порядке, который хозяин попросил посмотреть вживую.
 * Первый вариант — то, что собрано сейчас.
 */
val TrialAccents = listOf(
    TrialAccent("домашнихва (сейчас)", Color(0xFFA8CC6B), Color(0xFF5F7A33)),
    TrialAccent("Лайм", Color(0xFFB6E05A), Color(0xFF5C7A22)),
    TrialAccent("Трава", Color(0xFF7ED957), Color(0xFF3F7A2E)),
    TrialAccent("Изумруд", Color(0xFF4ADE80), Color(0xFF157F45)),
    TrialAccent("Мята (как было)", Color(0xFF35D0A5), Color(0xFF12796A)),
    TrialAccent("Шалфей приглушённый", Color(0xFF9CB380), Color(0xFF55663F)),
    TrialAccent("Кислотный", Color(0xFFC7F04A), Color(0xFF63801A)),
    TrialAccent("Тёмный лес", Color(0xFF6FA83C), Color(0xFF3D6B1E)),
    TrialAccent("Янтарь (для сравнения)", Color(0xFFE0A34A), Color(0xFF96601A)),
    TrialAccent("Сталь (для сравнения)", Color(0xFF7FB3D5), Color(0xFF2E6389)),
)

/** Вид круга на главном экране. */
enum class TrialDial(val title: String, val note: String) {
    A("Как сейчас", "подключено красится ролью Ok (бирюза)"),
    B("В тон акценту", "подключено красится выбранным акцентом"),
    C("Нейтральный", "кольцо нейтральное, состояние читается текстом"),
    D("В тон, толще", "как «в тон», но обводка 4dp вместо 2dp"),
}

/**
 * Живой выбор: читается темой и кругом, пишется экраном «Пробные палитры».
 *
 * Состояние в объекте, а не во ViewModel, потому что читать его надо из [SFATheme] —
 * выше любого экрана. Значение поднимается из настроек при первом обращении и
 * записывается обратно при каждом переключении, поэтому переживает перезапуск.
 */
object PaletteTrial {
    var accentIndex by mutableIntStateOf(
        runCatching { Settings.paletteTrialAccent }.getOrDefault(0).coerceIn(0, TrialAccents.lastIndex),
    )
        private set

    var dialIndex by mutableIntStateOf(
        runCatching { Settings.paletteTrialDial }.getOrDefault(0).coerceIn(0, TrialDial.entries.lastIndex),
    )
        private set

    val accent: TrialAccent get() = TrialAccents[accentIndex]
    val dial: TrialDial get() = TrialDial.entries[dialIndex]

    fun selectAccent(index: Int) {
        val safe = index.coerceIn(0, TrialAccents.lastIndex)
        accentIndex = safe
        runCatching { Settings.paletteTrialAccent = safe }
    }

    fun selectDial(index: Int) {
        val safe = index.coerceIn(0, TrialDial.entries.lastIndex)
        dialIndex = safe
        runCatching { Settings.paletteTrialDial = safe }
    }

    /** Подменяет акцент в наборе токенов. Всё остальное — нейтрали и семантика — не трогается. */
    fun apply(colors: KColors): KColors {
        val a = accent
        return colors.copy(Accent = if (colors.isDark) a.dark else a.light)
    }
}

/** Относительная яркость по WCAG 2.1 (sRGB → линейное пространство). */
private fun luminance(c: Color): Double {
    fun ch(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

/** Контраст двух цветов по WCAG: (L1 + 0.05) / (L2 + 0.05). */
fun wcagContrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
