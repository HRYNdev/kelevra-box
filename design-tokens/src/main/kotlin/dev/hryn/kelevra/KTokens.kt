package dev.hryn.kelevra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Токены фирменного стиля: единственный источник правды для телефона и для компьютера.
 *
 * Файл лежит вне обоих модулей и подключается исходником и в `:app`, и в `:desktop`
 * (см. `sourceSets` в их build.gradle.kts). Раньше эта палитра существовала двумя
 * побайтовыми копиями и расходилась при каждой правке.
 *
 * Канон: `D:\Projects\DESIGN.md`, раздел 1. Здесь не должно быть ничего
 * платформенного: шрифты и ресурсы остаются в своих модулях.
 *
 * Семь нейтральных токенов на тему, один акцент на всю систему, семантика состояний
 * отдельно от акцента. Светлая и тёмная — разные наборы, а не инверсия друг друга:
 * в тёмной поверхность светлее фона, утопленное темнее.
 */
@Immutable
data class KColors(
    /** Фон экрана. */
    val Bg: Color,
    /** Карточки, панели, боковая колонка. */
    val Surface: Color,
    /** Утопленное: вложенные блоки, неактивные дорожки, зебра. */
    val Sunken: Color,
    /** Границы и разделители. */
    val Border: Color,
    /** Основной текст. */
    val Text: Color,
    /** Вторичный текст, подписи. */
    val Dim: Color,
    /** Третичный текст, плейсхолдеры, выключенное состояние. */
    val Dim2: Color,
    /** Акцент системы: олива. Второго акцента нет и не будет. */
    val Accent: Color,
    /** Текст и иконки поверх акцентной заливки. */
    val AccentInk: Color,
    /** Норма, подключено. */
    val Ok: Color,
    /** Внимание, деградация. */
    val Warn: Color,
    /** Ошибка, отключено. */
    val Err: Color,
    val isDark: Boolean,
)

/** Тёмная тема. Поверхность светлее фона: свет падает сверху. */
val KDark = KColors(
    Bg = Color(0xFF111310),
    Surface = Color(0xFF191C18),
    Sunken = Color(0xFF0C0E0B),
    Border = Color(0xFF2B2F29),
    Text = Color(0xFFF0F1EC),
    Dim = Color(0xFFA5ABA3),
    Dim2 = Color(0xFF71766F),
    Accent = Color(0xFFA8CC6B),
    AccentInk = Color(0xFF111310),
    Ok = Color(0xFF4FBFA4),
    Warn = Color(0xFFE0A34A),
    Err = Color(0xFFF08379),
    isDark = true,
)

/** Светлая тема. Отдельный набор, а не инверсия тёмной. */
val KLight = KColors(
    Bg = Color(0xFFFBFBFA),
    Surface = Color(0xFFFFFFFF),
    Sunken = Color(0xFFF1F2EE),
    Border = Color(0xFFE3E5DF),
    Text = Color(0xFF16181A),
    Dim = Color(0xFF5B615D),
    Dim2 = Color(0xFF8D938E),
    Accent = Color(0xFF5F7A33),
    AccentInk = Color(0xFFFFFFFF),
    Ok = Color(0xFF1F7A68),
    Warn = Color(0xFFB06F16),
    Err = Color(0xFFB03A2E),
    isDark = false,
)

val LocalKColors = staticCompositionLocalOf { KDark }

/** Цвета текущей темы: `K.Bg`, `K.Accent`, `K.Ok`. */
val K: KColors
    @Composable
    @ReadOnlyComposable
    get() = LocalKColors.current

/** Скругления и отступы: одни и те же числа на телефоне и на десктопе. */
object KDim {
    val RadiusL = 26.dp
    val RadiusM = 18.dp
    val RadiusS = 12.dp
    val Gap = 10.dp
    val Pad = 18.dp
    val DialSize = 210.dp
    val DialStroke = 2.dp
}
