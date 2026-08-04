package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono

/**
 * Расширенные настройки — свои.
 *
 * Раньше эта строка вела в экран настроек sing-box целиком: чужие разделы,
 * чужие ссылки, половина пунктов про функции, которых у нас нет. Здесь только
 * то, что относится к нашей сети, и в нашем языке.
 */
@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    connectionsCount: Int,
    onAppsBypass: () -> Unit,
    onLog: () -> Unit,
    onConnections: () -> Unit,
    onCheck: () -> Unit,
    onCore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Назад",
                tint = colors.Text,
                modifier = Modifier.size(22.dp).clickable { onBack() },
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "Расширенные",
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = colors.Text,
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            KGroupTitle("Соединение")
            KCard {
                KRowItem(
                    title = "Приложения мимо сети",
                    subtitle = "Банки, госуслуги и МТС идут напрямую",
                    chevron = true,
                    onClick = onAppsBypass,
                )
                KDivider()
                KRowItem(
                    title = "Ядро",
                    subtitle = "Как работает соединение внутри",
                    chevron = true,
                    onClick = onCore,
                )
            }

            KGroupTitle("Диагностика")
            KCard {
                KRowItem(
                    title = "Проверка сети",
                    subtitle = "Что открывается, а что нет",
                    chevron = true,
                    onClick = onCheck,
                )
                KDivider()
                KRowItem(
                    title = "Соединения",
                    subtitle = if (connectionsCount > 0) "$connectionsCount активных" else "Куда идёт трафик прямо сейчас",
                    chevron = true,
                    onClick = onConnections,
                )
                KDivider()
                KRowItem(
                    title = "Журнал",
                    subtitle = "Подробный лог работы",
                    chevron = true,
                    onClick = onLog,
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "KELEVRA ${BuildConfig.VERSION_NAME}",
                fontFamily = RobotoMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = colors.Dim2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
