@file:OptIn(ExperimentalTextApi::class)

package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * Дизайн-система Kelevra: единственный источник цвета, скруглений и типографики.
 *
 * Тот же набор токенов лежит в основе десктопа, поэтому здесь не должно быть
 * ничего Android-специфичного кроме шрифтовых ресурсов.
 */
@Immutable
data class KColors(
    val Bg: Color,
    val Bg2: Color,
    val Surface: Color,
    val Surface2: Color,
    val Border: Color,
    val Text: Color,
    val Dim: Color,
    val Dim2: Color,
    val Accent: Color,
    val Accent2: Color,
    val Warn: Color,
    val Bad: Color,
    val isDark: Boolean,
) {
    /** Прежнее имя из первой версии темы: приподнятая поверхность. */
    val SurfaceHi: Color get() = Surface2
}

val KDark = KColors(
    Bg = Color(0xFF0B0E13),
    Bg2 = Color(0xFF11151C),
    Surface = Color(0xFF161B24),
    Surface2 = Color(0xFF1D2430),
    Border = Color(0xFF262E3B),
    Text = Color(0xFFE8EDF5),
    Dim = Color(0xFF93A0B4),
    Dim2 = Color(0xFF5F6B7D),
    Accent = Color(0xFF35D0A5),
    Accent2 = Color(0xFF2BA6FF),
    Warn = Color(0xFFF5A524),
    Bad = Color(0xFFF2555A),
    isDark = true,
)

val KLight = KColors(
    Bg = Color(0xFFEEF1F6),
    Bg2 = Color(0xFFFFFFFF),
    Surface = Color(0xFFFFFFFF),
    Surface2 = Color(0xFFF2F5FA),
    Border = Color(0xFFDDE3EC),
    Text = Color(0xFF101620),
    Dim = Color(0xFF5B6779),
    Dim2 = Color(0xFF93A0B4),
    Accent = Color(0xFF12A37C),
    Accent2 = Color(0xFF1B7FD4),
    Warn = Color(0xFFC2790A),
    Bad = Color(0xFFD93A40),
    isDark = false,
)

val LocalKColors = staticCompositionLocalOf { KDark }

/** Цвета текущей темы. Пишется как раньше: `K.Bg`, `K.Accent`. */
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

// Шрифты переменные: без явных настроек начертания Android рисует все веса
// одинаково тонкими — заголовок выглядит бледным. Задаём вес осью wght.
private fun mont(weight: Int) =
    Font(
        R.font.montserrat,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

val Montserrat = FontFamily(mont(300), mont(400), mont(500), mont(600), mont(700))

val RobotoMono = FontFamily(
    Font(
        R.font.roboto_mono,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

val KelevraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Normal, fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Normal, fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoMono, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, letterSpacing = 1.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoMono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 1.4.sp,
    ),
)
