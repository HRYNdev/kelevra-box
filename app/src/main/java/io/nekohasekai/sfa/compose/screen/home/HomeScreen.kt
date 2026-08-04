package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.DialState
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KBadge
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDial
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KHint
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.constant.Status

/** Канал в списке: имя, задержка, выбран ли сейчас. */
data class ChannelRow(val name: String, val delayMs: Int, val selected: Boolean)

/**
 * Код выхода в бейдже. Флаги-эмодзи не используем: на части устройств и в
 * браузере они не рисуются, а двухбуквенный код читается везде одинаково.
 */
fun badgeOf(name: String?): String? {
    val n = name?.lowercase() ?: return null
    return when {
        n.contains("нидерланд") || n.contains("netherl") || n.contains("nl") -> "NL"
        n.contains("герман") || n.contains("german") || n.contains("de") -> "DE"
        n.contains("екатеринб") || n.contains("дом") || n.contains("home") || n.contains("ru") -> "RU"
        n.contains("авто") || n.contains("auto") -> "AUTO"
        else -> name.take(2).uppercase()
    }
}

/**
 * Главный экран: круг-состояние по центру, под ним выход и подписка.
 *
 * Круг — единственное действие, попадать по нему нужно не глядя. Всё остальное
 * на экране только рассказывает, что происходит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    serviceStatus: Status,
    activeOutbound: String?,
    channels: List<ChannelRow>,
    hasProfile: Boolean,
    transport: String? = null,
    activeChannel: String? = null,
    onToggle: () -> Unit,
    onSelectChannel: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    val running = serviceStatus == Status.Started
    val busy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    var showExits by remember { mutableStateOf(false) }
    var subscription by remember { mutableStateOf<SubscriptionInfo?>(null) }

    LaunchedEffect(hasProfile, running) {
        if (hasProfile) subscription = loadSubscription()
    }

    val state = when {
        busy -> DialState.Busy
        running -> DialState.On
        else -> DialState.Off
    }

    val activeDelay = channels.firstOrNull { it.selected }?.delayMs ?: 0
    val place = when {
        busy -> null
        running -> activeOutbound
        else -> null
    }
    // транспорт активного выхода знает только сервер: ядро наверх его не отдаёт
    val activeTransport = transport ?: subscription?.transports?.get(activeChannel)
    val meta = buildString {
        if (running) {
            if (!activeTransport.isNullOrBlank()) append(activeTransport)
            if (activeDelay > 0) {
                if (isNotEmpty()) append(" · ")
                append("$activeDelay мс")
            }
        }
    }.ifBlank { null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        KDial(
            state = state,
            title = when {
                !hasProfile -> "Подключить"
                busy && serviceStatus == Status.Starting -> "Подключаюсь"
                busy -> "Отключаюсь"
                running -> "Подключено"
                else -> "Отключено"
            },
            badge = if (running) badgeOf(activeOutbound) else null,
            place = place,
            meta = meta,
            onClick = { if (hasProfile) onToggle() else onConnect() },
        )

        Spacer(Modifier.height(16.dp))
        KHint(
            when {
                !hasProfile -> "нажмите, чтобы подключить"
                busy -> "подождите"
                running -> "нажмите, чтобы выключить"
                else -> "нажмите, чтобы включить"
            },
        )

        Spacer(Modifier.height(26.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            // карточка выхода видна всегда, когда подписка есть: до запуска сети
            // ядро ещё не отдало список, но человек должен понимать, куда пойдёт трафик
            if (hasProfile) {
                KCard(onClick = { if (channels.isNotEmpty()) showExits = true }) {
                    KRowItem(
                        label = "Выход",
                        title = activeOutbound ?: "Авто",
                        subtitle = if (channels.isEmpty() && !running) "определится при подключении" else null,
                        badge = badgeOf(activeOutbound),
                        chevron = channels.isNotEmpty(),
                    )
                }
                Spacer(Modifier.height(KDim.Gap))
            }

            val sub = subscription
            if (sub != null) {
                KCard {
                    // имя аккаунта из панели человеку ничего не говорит: показываем состояние
                    KRowItem(
                        label = "Подписка",
                        title = if (sub.active) "Активна" else "Приостановлена",
                        subtitle = sub.note,
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    if (showExits) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showExits = false },
            sheetState = sheetState,
            containerColor = colors.Bg2,
            contentColor = colors.Text,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 4.dp)) {
                Text(
                    text = "Выбор выхода",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.Text,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                channels.forEach { ch ->
                    ExitRow(
                        name = ch.name,
                        delayMs = ch.delayMs,
                        selected = ch.selected,
                        onClick = {
                            onSelectChannel(ch.name)
                            showExits = false
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ExitRow(name: String, delayMs: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = K
    KCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            badgeOf(name)?.let { KBadge(it, accent = selected) }
            Text(
                text = name,
                fontFamily = Montserrat,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp,
                color = colors.Text,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            if (selected) {
                Text(text = "✓", fontSize = 15.sp, color = colors.Accent)
            } else {
                Text(
                    text = if (delayMs > 0) "$delayMs мс" else "—",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    color = colors.Dim,
                )
            }
        }
    }
}

/** Пустое место под будущую карточку подписки, чтобы экран не прыгал. */
@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxWidth().height(0.dp))
}
