package dev.hryn.kelevra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Те же токены, что и на телефоне. Один язык оформления на обеих платформах:
 * значения меняются здесь и там одинаково, иначе клиенты разъедутся.
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
)

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

val K: KColors
    @Composable
    @ReadOnlyComposable
    get() = LocalKColors.current

object KDim {
    val RadiusL = 26.dp
    val RadiusM = 18.dp
    val RadiusS = 12.dp
    val Gap = 10.dp
    val Pad = 18.dp
    val DialSize = 210.dp
    val DialStroke = 2.dp
}

val Montserrat = FontFamily(
    Font("font/montserrat.ttf", FontWeight.Light),
    Font("font/montserrat.ttf", FontWeight.Normal),
    Font("font/montserrat.ttf", FontWeight.Medium),
    Font("font/montserrat.ttf", FontWeight.SemiBold),
    Font("font/montserrat.ttf", FontWeight.Bold),
)

val RobotoMono = FontFamily(Font("font/roboto_mono.ttf", FontWeight.Medium))
