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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.compose.screen.connections.ConnectionsViewModel
import io.nekohasekai.sfa.compose.screen.log.LogViewModel
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KScreenHeader
import io.nekohasekai.sfa.compose.theme.plural
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import io.nekohasekai.sfa.constant.Status

/** Общая шапка технических экранов: без неё из журнала нельзя было выйти. */
@Composable
private fun TechHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    val colors = K
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
        Column {
            Text(
                text = title,
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = colors.Text,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = Montserrat,
                    fontSize = 12.sp,
                    color = colors.Dim,
                )
            }
        }
    }
}

/** Куда идёт трафик прямо сейчас. */
@Composable
fun ConnectionsScreen(onBack: () -> Unit, serviceStatus: Status, modifier: Modifier = Modifier) {
    val colors = K
    val viewModel: ConnectionsViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(serviceStatus) { viewModel.updateServiceStatus(serviceStatus) }
    LaunchedEffect(Unit) { viewModel.setVisible(true) }
    DisposableEffect(Unit) { onDispose { viewModel.setVisible(false) } }

    val active = state.allConnections.filter { it.isActive }

    Column(modifier = modifier.fillMaxSize().background(colors.Bg)) {
        KScreenHeader(
            title = "Соединения",
            subtitle = if (active.isEmpty()) "Активных подключений нет" else "${active.size} " + plural(active.size, "активное", "активных", "активных"),
            onBack = onBack,
        )
        if (active.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Активных подключений нет",
                    fontFamily = Montserrat,
                    fontSize = 14.sp,
                    color = colors.Dim2,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = KDim.Pad)) {
                items(active, key = { it.id }) { conn ->
                    KCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conn.displayDestination.ifBlank { conn.destination },
                                    fontFamily = Montserrat,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = colors.Text,
                                )
                                Text(
                                    text = buildString {
                                        append(conn.outbound.ifBlank { "прямое подключение" })
                                        if (conn.network.isNotBlank()) append(" · ${conn.network}")
                                    },
                                    fontFamily = RobotoMono,
                                    fontSize = 10.sp,
                                    color = colors.Dim2,
                                )
                            }
                            Text(
                                text = "↑ ${Libbox.formatBytes(conn.uploadTotal)}  ↓ ${Libbox.formatBytes(conn.downloadTotal)}",
                                fontFamily = RobotoMono,
                                fontSize = 10.sp,
                                color = colors.Dim,
                            )
                        }
                    }
                    Spacer(Modifier.height(KDim.Gap))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Журнал работы: техническая простыня, но со своей шапкой и выходом. */
@Composable
fun JournalScreen(onBack: () -> Unit, serviceStatus: Status, modifier: Modifier = Modifier) {
    val colors = K
    val viewModel: LogViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(serviceStatus) { viewModel.updateServiceStatus(serviceStatus) }

    Column(modifier = modifier.fillMaxSize().background(colors.Bg)) {
        KScreenHeader(
            title = "Журнал",
            subtitle = if (state.logs.isEmpty()) "Пока пусто" else "${state.logs.size} " + plural(state.logs.size, "строка", "строки", "строк"),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = KDim.Pad),
            reverseLayout = true,
        ) {
            items(state.logs.reversed()) { line ->
                Text(
                    text = AnsiColorUtils.stripAnsi(line.entry.message),
                    fontFamily = RobotoMono,
                    fontSize = 10.sp,
                    color = colors.Dim,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
