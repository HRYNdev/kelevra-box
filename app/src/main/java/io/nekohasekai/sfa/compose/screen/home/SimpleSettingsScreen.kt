package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KGroupTitle
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.KSwitch
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono

/** Человеческое имя режима маршрутизации вместо clash-терминов. */
fun routeModeTitle(mode: String): String = when (mode.lowercase()) {
    "rule" -> "Только заблокированные сайты"
    "global" -> "Весь трафик через сеть"
    "direct" -> "Напрямую, без сети"
    else -> mode
}

/**
 * Настройки: только то, что нужно обычному человеку. Всё техническое —
 * за строкой «Расширенные настройки», жалоба — конвертом в шапке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleSettingsScreen(
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    routeMode: String,
    routeModes: List<String>,
    onRouteMode: (String) -> Unit,
    notifications: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    subscriptionName: String?,
    onConnectByCode: () -> Unit,
    onAppsBypass: () -> Unit,
    onCheck: () -> Unit,
    onAdvanced: () -> Unit,
    onComplaint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    var showModes by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Настройки",
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                letterSpacing = (-0.4).sp,
                color = colors.Text,
                modifier = Modifier.weight(1f),
            )
            // «Столкнулись с проблемой? Опишите её, и мы исправим»
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = "Сообщить о проблеме",
                tint = colors.Dim,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onComplaint() },
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            KGroupTitle("Сеть")
            KCard {
                KRowItem(
                    title = "Автозапуск",
                    subtitle = "Сеть поднимется вместе с телефоном",
                    trailing = { KSwitch(autoStart, onAutoStartChange) },
                )
                // пункт появляется только когда ядро реально отдало режимы:
                // мёртвая строка, которую нельзя изменить, хуже её отсутствия
                if (routeModes.size > 1) {
                    KDivider()
                    KRowItem(
                        title = "Маршрутизация",
                        subtitle = routeModeTitle(routeMode),
                        chevron = true,
                        onClick = { showModes = true },
                    )
                }
                KDivider()
                KRowItem(
                    title = "Приложения мимо сети",
                    subtitle = "Банки и госуслуги идут напрямую",
                    chevron = true,
                    onClick = onAppsBypass,
                )
            }

            KGroupTitle("Приложение")
            KCard {
                KRowItem(
                    title = "Уведомления",
                    subtitle = "Показывать состояние сети в шторке",
                    trailing = { KSwitch(notifications, onNotificationsChange) },
                )
                KDivider()
                KRowItem(
                    title = "Проверка",
                    subtitle = "Посмотреть, что работает, а что нет",
                    chevron = true,
                    onClick = onCheck,
                )
            }

            KGroupTitle("Подписка")
            KCard {
                KRowItem(
                    title = subscriptionName ?: "Подключить по коду",
                    subtitle = if (subscriptionName != null) "Заменить другим кодом" else null,
                    chevron = true,
                    onClick = onConnectByCode,
                )
            }

            Spacer(Modifier.height(18.dp))
            KCard(onClick = onAdvanced) {
                KRowItem(title = "Расширенные настройки", chevron = true)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "KELEVRA ${BuildConfig.VERSION_NAME}",
                fontFamily = RobotoMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = colors.Dim2,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showModes) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showModes = false },
            sheetState = sheetState,
            containerColor = colors.Bg2,
            contentColor = colors.Text,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 4.dp)) {
                Text(
                    text = "Что идёт через сеть",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.Text,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                routeModes.forEach { mode ->
                    KCard(
                        onClick = {
                            onRouteMode(mode)
                            showModes = false
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = routeModeTitle(mode),
                                fontFamily = Montserrat,
                                fontWeight = if (mode == routeMode) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 15.sp,
                                color = colors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            if (mode == routeMode) {
                                Text(text = "✓", fontSize = 15.sp, color = colors.Accent)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** Полоска-заглушка, чтобы список не прилипал к краю экрана. */
@Composable
private fun Tail() {
    Box(Modifier.fillMaxWidth().height(0.dp))
}
