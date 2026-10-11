@file:OptIn(ExperimentalTextApi::class)

package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * Дизайн-система Kelevra на телефоне.
 *
 * Цвета и размеры сюда не пишутся: они лежат в общем файле токенов
 * `design-tokens/src/main/kotlin/dev/hryn/kelevra/KTokens.kt`, который подключён
 * исходником и в это приложение, и в десктопный клиент. Ниже только пробросы под
 * привычными именами (`K`, `KColors`, `KDim`) и шрифты — единственное, что здесь
 * действительно платформенное.
 */
typealias KColors = dev.hryn.kelevra.KColors

val KDark = dev.hryn.kelevra.KDark
val KLight = dev.hryn.kelevra.KLight
val LocalKColors = dev.hryn.kelevra.LocalKColors

/** Скругления и отступы: одни и те же числа на телефоне и на десктопе. */
val KDim = dev.hryn.kelevra.KDim

/** Цвета текущей темы. Пишется как раньше: `K.Bg`, `K.Accent`. */
val K: KColors
    @Composable
    @ReadOnlyComposable
    get() = LocalKColors.current

// Шрифты статичные: свой файл на каждый вес. Раньше был один переменный файл и вес
// задавался осью wght, но у переменного Montserrat вес по умолчанию 100 (Thin), и
// прошивка, которая ось не применяет (HyperOS у пользователя, 11.10.2026), рисовала весь
// текст волосяным — светлая тема «сливалась». Файлы нарезаны из того же переменного
// fontTools-инстансером, глифы те же.
val Montserrat = FontFamily(
    Font(R.font.montserrat_300, FontWeight.Light),
    Font(R.font.montserrat_400, FontWeight.Normal),
    Font(R.font.montserrat_500, FontWeight.Medium),
    Font(R.font.montserrat_600, FontWeight.SemiBold),
    Font(R.font.montserrat_700, FontWeight.Bold),
)

val RobotoMono = FontFamily(Font(R.font.roboto_mono_500, FontWeight.Medium))

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
