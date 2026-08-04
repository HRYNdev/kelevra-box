package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun schemeOf(c: KColors) =
    if (c.isDark) {
        darkColorScheme(
            primary = c.Accent,
            onPrimary = c.Bg,
            secondary = c.Accent2,
            tertiary = c.Accent,
            background = c.Bg,
            onBackground = c.Text,
            surface = c.Surface,
            onSurface = c.Text,
            surfaceVariant = c.Surface2,
            onSurfaceVariant = c.Dim,
            surfaceContainer = c.Surface,
            surfaceContainerHigh = c.Surface2,
            primaryContainer = c.Surface2,
            outline = c.Border,
            error = c.Bad,
        )
    } else {
        lightColorScheme(
            primary = c.Accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = c.Accent2,
            tertiary = c.Accent,
            background = c.Bg,
            onBackground = c.Text,
            surface = c.Surface,
            onSurface = c.Text,
            surfaceVariant = c.Surface2,
            onSurfaceVariant = c.Dim,
            surfaceContainer = c.Surface,
            surfaceContainerHigh = c.Surface2,
            primaryContainer = c.Surface2,
            outline = c.Border,
            error = c.Bad,
        )
    }

/**
 * Тема приложения. Светлая и тёмная — по настройке системы; подстройку под обои
 * телефона не делаем, свой узнаваемый вид важнее.
 */
@Composable
fun SFATheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) KDark else KLight
    val colorScheme = schemeOf(colors)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.Bg.toArgb()
            window.navigationBarColor = colors.Bg2.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalKColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KelevraTypography,
            shapes = Shapes,
            content = content,
        )
    }
}
