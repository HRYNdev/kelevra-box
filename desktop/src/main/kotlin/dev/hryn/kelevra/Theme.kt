package dev.hryn.kelevra

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Оформление десктопного клиента.
 *
 * Цвета и размеры лежат в общем файле `design-tokens/src/main/kotlin/dev/hryn/kelevra/KTokens.kt`,
 * который подключён исходником и сюда, и в телефонное приложение: пакет тот же, поэтому
 * `K`, `KColors`, `KDim` видны без импортов. Здесь остались только шрифты — единственное,
 * что на платформах отличается.
 */
val Montserrat = FontFamily(
    Font("font/montserrat.ttf", FontWeight.Light),
    Font("font/montserrat.ttf", FontWeight.Normal),
    Font("font/montserrat.ttf", FontWeight.Medium),
    Font("font/montserrat.ttf", FontWeight.SemiBold),
    Font("font/montserrat.ttf", FontWeight.Bold),
)

val RobotoMono = FontFamily(Font("font/roboto_mono.ttf", FontWeight.Medium))
