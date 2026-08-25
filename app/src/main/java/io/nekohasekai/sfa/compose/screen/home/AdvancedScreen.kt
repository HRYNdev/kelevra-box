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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KGroupTitle
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.plural
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import kotlinx.coroutines.delay

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
    onConnections: () -> Unit,
    onCheck: () -> Unit,
    onLog: () -> Unit,
    onOlcRtc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    val refresh by SubscriptionRefresh.state.collectAsState()
    val summary by SubscriptionRefresh.summary.collectAsState()
    val lastUpdated by SubscriptionRefresh.lastUpdated.collectAsState()

    // «5 минут назад» стареет само: без тика подпись врала бы, пока экран открыт.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        SubscriptionRefresh.loadLocal()
        while (true) {
            delay(30_000)
            tick++
        }
    }

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
            // Обновление подписки руками: сама она обновляется по расписанию, но когда
            // сеть начинает раздавать новое, ждать расписания незачем.
            KGroupTitle("Подписка")
            KCard {
                KRowItem(
                    title = "Состояние",
                    subtitle = summary?.words ?: "Пока неизвестно",
                )
                KDivider()
                // Реклама режется наборами, которые приезжают с сервера. Раньше про это
                // в приложении не было ни слова: человек не знал, что она вообще работает.
                KRowItem(
                    title = "Блокировка рекламы",
                    subtitle = "Включена сетью, списки приезжают с сервера",
                )
                KDivider()
                KRowItem(
                    title = "Обновление",
                    // tick участвует в чтении, иначе подпись застынет на времени открытия
                    subtitle = remember(lastUpdated, tick) { humanAgo(lastUpdated) },
                )
                Spacer(Modifier.height(14.dp))
                KButton(
                    text = if (refresh is SubscriptionRefresh.State.Running) "Обновляю" else "Обновить",
                    onClick = { SubscriptionRefresh.request() },
                    enabled = refresh !is SubscriptionRefresh.State.Running,
                )
                val message = when (val current = refresh) {
                    is SubscriptionRefresh.State.Ok -> current.note to colors.Ok
                    is SubscriptionRefresh.State.Failed -> current.reason to colors.Err
                    else -> null
                }
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message.first,
                        fontFamily = Montserrat,
                        fontSize = 13.sp,
                        color = message.second,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            KGroupTitle("Соединение")
            KCard {
                KRowItem(
                    title = "Приложения мимо сети",
                    subtitle = "Приложения, работающие в обход сети",
                    chevron = true,
                    onClick = onAppsBypass,
                )
                KDivider()
                // «olcRTC» — внутреннее имя канала, человеку оно ничего не говорит.
                // Наружу идёт то, чем эта штука для него является.
                KRowItem(
                    title = stringResource(R.string.room_title),
                    subtitle = stringResource(R.string.room_subtitle),
                    chevron = true,
                    onClick = onOlcRtc,
                )
            }

            KGroupTitle("Диагностика")
            KCard {
                KRowItem(
                    title = "Проверка сети",
                    subtitle = "Доступ к сайтам и правилам",
                    chevron = true,
                    onClick = onCheck,
                )
                KDivider()
                KRowItem(
                    title = "Соединения",
                    subtitle = if (connectionsCount > 0) {
                        "$connectionsCount " + plural(connectionsCount, "активное", "активных", "активных")
                    } else {
                        "Текущие подключения"
                    },
                    chevron = true,
                    onClick = onConnections,
                )
                KDivider()
                KRowItem(
                    title = "Журнал",
                    subtitle = stringResource(R.string.advanced_journal_subtitle),
                    chevron = true,
                    onClick = onLog,
                )
            }

            Spacer(Modifier.height(22.dp))
            // версия ядра строкой: отдельный экран для неё вёл в чужие настройки
            // с «Beta Settings» и кнопкой, стирающей данные
            Text(
                text = "ЯДРО ${remember { Libbox.version() }}",
                fontFamily = RobotoMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = colors.Dim2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
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
