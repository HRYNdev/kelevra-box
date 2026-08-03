package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * Дизайн-система Kelevra.
 *
 * Взята из DESIGN.md (тёмный формат): глубокий navy, один cyan-акцент,
 * Montserrat для текста и Roboto Mono для всех чисел и лейблов.
 * Числа с tabular-nums, лейблы капсом с разрядкой, 1px-линии вместо коробок.
 */
object K {
    val Bg = Color(0xFF07121C)
    val Surface = Color(0xFF0D2031)
    val SurfaceHi = Color(0xFF195066)
    val Border = Color(0xFF252D33)
    val Accent = Color(0xFF37BDF8)
    val Warn = Color(0xFFF09025)
    val Text = Color(0xFFFFFFFF)
    val Dim = Color(0xFF96A2B6)
}

// Шрифты переменные: без явных настроек начертания Android рисует все веса
// одинаково тонкими — заголовок выглядит бледным. Задаём вес осью wght.
@OptIn(ExperimentalTextApi::class)
private fun mont(weight: Int) =
    Font(
        R.font.montserrat,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

val Montserrat = FontFamily(mont(300), mont(400), mont(600), mont(700))

@OptIn(ExperimentalTextApi::class)
val RobotoMono = FontFamily(
    Font(
        R.font.roboto_mono,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

/** Заголовки крупные с отрицательным трекингом, тело лёгкое. */
val KelevraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoMono, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, letterSpacing = 1.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoMono, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 1.4.sp,
    ),
)
