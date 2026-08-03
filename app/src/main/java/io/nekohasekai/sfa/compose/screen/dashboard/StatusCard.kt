package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.UptimeText
import io.nekohasekai.sfa.constant.Status

/**
 * Состояние понятным языком: работает или нет, по какому профилю и сколько времени.
 * Никаких гороутин и байтов — они живут в отладочных карточках.
 */
@Composable
fun StatusCard(
    serviceStatus: Status,
    profileName: String?,
    serviceStartTime: Long?,
    modifier: Modifier = Modifier,
) {
    val running = serviceStatus == Status.Started
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (running) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Shield else Icons.Outlined.ShieldMoon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint =
                    if (running) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!profileName.isNullOrBlank()) {
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running && serviceStartTime != null) {
                    UptimeText(startTime = serviceStartTime)
                }
            }
        }
    }
}
