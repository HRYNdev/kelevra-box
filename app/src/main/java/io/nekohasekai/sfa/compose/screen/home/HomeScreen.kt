package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

/** Канал в списке: имя, задержка, выбран ли сейчас. */
data class ChannelRow(val name: String, val delayMs: Int, val selected: Boolean)

private val Ink = Color(0xFF08090A)
private val Line = Color(0xFF17181B)
private val Dim = Color(0xFF62666D)
private val Soft = Color(0xFFD0D3D7)
private val Live = Color(0xFF4ADE80)

/**
 * Главный экран.
 *
 * Наполнение взято от того, чем человек реально пользуется: состояние, одно действие,
 * живые каналы с задержкой и два факта о работе. Ни профилей, ни правил, ни байтов.
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
    val dotColor by animateColorAsState(if (running) Live else Dim, tween(300), label = "dot")

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Ink)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp),
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // строка состояния: точка + канал
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text =
                    if (running && !activeOutbound.isNullOrBlank()) {
                        activeOutbound.lowercase()
                    } else {
                        stringResource(R.string.home_not_connected)
                    },
                fontSize = 13.sp,
                color = Dim,
            )
        }

        Spacer(modifier = Modifier.height(56.dp))

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
            fontSize = 52.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-2).sp,
            color = Color.White,
        )

        if (running && !uptimeText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = uptimeText, fontSize = 15.sp, color = Dim)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // главное действие
        Surface(
            onClick = { if (hasProfile && !busy) onToggle() else if (!hasProfile) onConnect() },
            shape = RoundedCornerShape(14.dp),
            color = if (running) Color.Transparent else Color.White,
            border = if (running) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF23252A)) else null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = if (running) Soft else Ink,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text =
                            stringResource(
                                when {
                                    !hasProfile -> R.string.profile_add_by_code_confirm
                                    running -> R.string.home_turn_off
                                    else -> R.string.home_turn_on
                                },
                            ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (running) Soft else Ink,
                    )
                }
            }
        }

        if (channels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.home_channels),
                fontSize = 13.sp,
                color = Dim,
            )
            Spacer(modifier = Modifier.height(6.dp))
            channels.forEach { ch ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChannelClick(ch.name) }
                            .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (ch.selected) Live else Color(0xFF2A2D33)),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = ch.name,
                        fontSize = 15.sp,
                        color = if (ch.selected) Color.White else Soft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (ch.delayMs > 0) "${ch.delayMs} мс" else "—",
                        fontSize = 14.sp,
                        color = Dim,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.title_settings), fontSize = 15.sp, color = Soft)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF3B3F45),
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
