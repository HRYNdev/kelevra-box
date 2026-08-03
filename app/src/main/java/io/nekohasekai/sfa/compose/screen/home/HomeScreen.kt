package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.constant.Status

/** Канал в списке: имя, задержка, выбран ли сейчас. */
data class ChannelRow(val name: String, val delayMs: Int, val selected: Boolean)

/**
 * Главный экран по дизайн-системе: navy, один cyan-акцент, числа моноширинным,
 * лейблы капсом с разрядкой, разделители 1px вместо коробок.
 */
@Composable
fun HomeScreen(
    serviceStatus: Status,
    activeOutbound: String?,
    uptimeText: String?,
    channels: List<ChannelRow>,
    hasProfile: Boolean,
    onToggle: () -> Unit,
    onChannelClick: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = serviceStatus == Status.Started
    val busy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    val dot by animateColorAsState(if (running) K.Accent else K.Border, tween(300), label = "dot")

    Column(
        modifier = modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // eyebrow: капсом, моно, с разрядкой
        Text(
            text = stringResource(R.string.app_eyebrow),
            style = MaterialTheme.typography.labelMedium,
            color = K.Dim,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Панель состояния: приподнятая поверхность, тонкая рамка
        Surface(
            color = K.Surface,
            border = BorderStroke(1.dp, K.Border),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text =
                            if (running && !activeOutbound.isNullOrBlank()) {
                                activeOutbound
                            } else {
                                stringResource(R.string.home_not_connected)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = K.Dim,
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text =
                        stringResource(
                            when (serviceStatus) {
                                Status.Started -> R.string.state_protected
                                Status.Starting -> R.string.state_connecting
                                Status.Stopping -> R.string.state_stopping
                                else -> R.string.state_off
                            },
                        ),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = (-0.8).sp,
                    color = K.Text,
                )

                if (running && !uptimeText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uptimeText,
                        fontFamily = RobotoMono,
                        fontSize = 13.sp,
                        color = K.Dim,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Кнопка-пилюля: прозрачная, обводка акцентом, моно капсом
        Surface(
            onClick = { if (!hasProfile) onConnect() else if (!busy) onToggle() },
            shape = RoundedCornerShape(50),
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.5.dp, if (busy) K.Border else K.Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = K.Accent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text =
                            stringResource(
                                when {
                                    !hasProfile -> R.string.home_btn_connect
                                    running -> R.string.home_btn_off
                                    else -> R.string.home_btn_on
                                },
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        color = K.Accent,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (channels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(34.dp))
            Text(
                text = stringResource(R.string.home_channels),
                style = MaterialTheme.typography.labelMedium,
                color = K.Dim,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            channels.forEach { ch ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChannelClick(ch.name) }
                            .padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (ch.selected) K.Accent else K.Border),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = ch.name,
                        fontFamily = Montserrat,
                        fontWeight = if (ch.selected) FontWeight.SemiBold else FontWeight.Light,
                        fontSize = 15.sp,
                        color = if (ch.selected) K.Text else K.Dim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (ch.delayMs > 0) "${ch.delayMs} MS" else "—",
                        fontFamily = RobotoMono,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = if (ch.selected) K.Accent else K.Dim,
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(1.dp)
                            .background(K.Border),
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.title_settings).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = K.Dim,
            )
            Text(text = "→", fontFamily = RobotoMono, fontSize = 14.sp, color = K.Accent)
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
