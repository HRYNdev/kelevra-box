package dev.hryn.kelevra

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Десктопный клиент. Пока это интерфейс: круг, выход, подписка и настройки в
 * том же языке, что на телефоне. Управление ядром подключается следующим шагом.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kelevra",
        state = rememberWindowState(width = 900.dp, height = 620.dp),
    ) {
        var dark by remember { mutableStateOf(true) }
        val colors = if (dark) KDark else KLight
        CompositionLocalProvider(LocalKColors provides colors) {
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(primary = colors.Accent, background = colors.Bg, surface = colors.Surface)
                } else {
                    lightColorScheme(primary = colors.Accent, background = colors.Bg, surface = colors.Surface)
                },
            ) {
                App(onToggleTheme = { dark = !dark })
            }
        }
    }
}

private enum class Tab { Network, Settings }

@Composable
private fun App(onToggleTheme: () -> Unit) {
    val colors = K
    var tab by remember { mutableStateOf(Tab.Network) }
    var connected by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize().background(colors.Bg)) {
        Column(
            modifier = Modifier
                .width(186.dp)
                .fillMaxHeight()
                .background(colors.Bg2)
                .padding(horizontal = 10.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.Accent),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "Kelevra",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.Text,
                )
            }
            SideItem("Сеть", tab == Tab.Network) { tab = Tab.Network }
            SideItem("Настройки", tab == Tab.Settings) { tab = Tab.Settings }
            Spacer(Modifier.weight(1f))
            SideItem(if (colors.isDark) "Светлая тема" else "Тёмная тема", false, onToggleTheme)
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
            when (tab) {
                Tab.Network -> NetworkScreen(connected) { connected = !connected }
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun SideItem(title: String, active: Boolean, onClick: () -> Unit) {
    val colors = K
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.Surface else colors.Bg2)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            fontFamily = Montserrat,
            fontSize = 14.sp,
            color = if (active) colors.Text else colors.Dim,
        )
    }
}

@Composable
private fun NetworkScreen(connected: Boolean, onToggle: () -> Unit) {
    val colors = K
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(
            modifier = Modifier.width(240.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            KDial(
                state = if (connected) DialState.On else DialState.Off,
                title = if (connected) "Подключено" else "Отключено",
                badge = if (connected) "NL" else null,
                place = if (connected) "Нидерланды" else null,
                meta = if (connected) "reality · 63 мс" else null,
                onClick = onToggle,
                size = 190.dp,
            )
            Spacer(Modifier.height(16.dp))
            KHint(if (connected) "нажмите, чтобы выключить" else "нажмите, чтобы включить")
        }

        Column(modifier = Modifier.weight(1f)) {
            KCard(onClick = {}) {
                KRowItem(
                    label = "Выход",
                    title = "Нидерланды",
                    badge = "NL",
                    chevron = true,
                )
            }
            Spacer(Modifier.height(KDim.Gap))
            KCard {
                KRowItem(label = "Подписка", title = "Активна", subtitle = "без ограничений")
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        KGroupTitle("Сеть")
        KCard {
            KRowItem(
                title = "Автозапуск",
                subtitle = "Подключение восстанавливается при входе в систему",
            )
        }
        KGroupTitle("Приложение")
        KCard {
            KRowItem(title = "Проверка сети", subtitle = "Доступ к сайтам и правилам", chevron = true)
        }
        KGroupTitle("Подписка")
        KCard {
            KRowItem(title = "Подключить по коду", subtitle = "Изменить код доступа", chevron = true)
        }
    }
}
