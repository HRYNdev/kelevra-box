package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * Главный экран: круг-выключатель по центру, под ним состояние и каналы.
 *
 * Круг — потому что это единственное действие, и попадать по нему нужно не глядя.
 * Палитра и типографика из DESIGN.md: navy, один cyan-акцент, моно для чисел и лейблов.
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

    val ring by animateColorAsState(if (running) K.Accent else K.Border, tween(400), label = "ring")
    // еле заметное дыхание кольца, когда сеть работает
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "pulseValue",
    )

    Column(
        modifier = modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(30.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = K.Dim,
            )
            Text(
                text = stringResource(R.string.title_settings).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = K.Dim,
                modifier = Modifier.clickable { onOpenSettings() },
            )
        }

        Spacer(modifier = Modifier.height(56.dp))

        // Круг: внешнее кольцо + внутренний круг с надписью
        Box(contentAlignment = Alignment.Center) {
            if (running) {
                Box(
                    modifier =
                        Modifier
                            .size(230.dp)
                            .alpha(pulse)
                            .border(1.dp, K.Accent, CircleShape),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(196.dp)
                        .clip(CircleShape)
                        .background(if (running) K.SurfaceHi.copy(alpha = 0.25f) else K.Surface)
                        .border(1.5.dp, ring, CircleShape)
                        .clickable(enabled = !busy) { if (hasProfile) onToggle() else onConnect() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = K.Accent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text =
                                stringResource(
                                    when {
                                        !hasProfile -> R.string.home_circle_connect
                                        running -> R.string.state_protected
                                        else -> R.string.state_off
                                    },
                                ),
                            fontFamily = Montserrat,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            letterSpacing = (-0.5).sp,
                            color = if (running) K.Text else K.Dim,
                        )
                        if (running && !uptimeText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uptimeText,
                                fontFamily = RobotoMono,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp,
                                color = K.Dim,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        Text(
            text =
                if (running && !activeOutbound.isNullOrBlank()) {
                    activeOutbound
                } else {
                    stringResource(R.string.home_not_connected)
                },
            style = MaterialTheme.typography.labelMedium,
            color = if (running) K.Accent else K.Dim,
        )

        if (channels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(46.dp))
            Text(
                text = stringResource(R.string.home_channels),
                style = MaterialTheme.typography.labelMedium,
                color = K.Dim,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
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

        Spacer(modifier = Modifier.height(40.dp))
    }
}
