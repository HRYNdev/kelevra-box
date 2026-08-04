package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.KScreenHeader
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Проверка «что работает» одним экраном.
 *
 * Смысл: когда что-то отвалилось, не надо читать простыню логов ядра — видно,
 * какой именно кусок цепочки лёг: раздача правил, подписка, туннель или блокировка рекламы.
 */
private data class Check(
    val title: String,
    val hint: String,
    val run: suspend () -> String,
)

private sealed class CheckState {
    object Idle : CheckState()

    object Running : CheckState()

    data class Done(val ok: Boolean, val detail: String) : CheckState()
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit = {}) {

    val scope = rememberCoroutineScope()

    var states by remember { mutableStateOf<Map<String, CheckState>>(emptyMap()) }

    val checks =
        remember {
            listOf(
                Check(
                    title = "Доступ к правилам",
                    hint = "Списки правил загружаются с сервера",
                ) {
                    val body = HTTPClient().use { it.getString("https://${Kelevra.SUBSCRIPTION_HOST}/rules/main-domains.srs") }
                    if (body.isEmpty()) throw Exception("пустой ответ") else "список получен"
                },
                Check(
                    title = "Доступ через сеть",
                    hint = "Сайт из списка правил открывается через сеть",
                ) {
                    HTTPClient().use { it.getString("https://rutracker.org/forum/index.php") }
                    "доступен"
                },
                Check(
                    title = "Прямой доступ",
                    hint = "Российские сайты открываются в обход сети",
                ) {
                    HTTPClient().use { it.getString("https://www.gosuslugi.ru/robots.txt") }
                    "доступен напрямую"
                },
                Check(
                    title = "Блокировка рекламы",
                    hint = "Рекламные домены не открываются",
                ) {
                    val failed =
                        try {
                            HTTPClient().use { it.getString("https://an.yandex.ru/") }
                            false
                        } catch (e: Exception) {
                            true
                        }
                    if (failed) "блокируется" else throw Exception("не блокируется")
                },
            )
        }

    fun runAll() {
        scope.launch {
            checks.forEach { check ->
                states = states + (check.title to CheckState.Running)
                val result =
                    withContext(Dispatchers.IO) {
                        try {
                            CheckState.Done(true, check.run())
                        } catch (e: Exception) {
                            CheckState.Done(false, e.message ?: "ошибка")
                        }
                    }
                states = states + (check.title to result)
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(K.Bg)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
    ) {
        KScreenHeader(title = "Проверка сети", subtitle = "Доступ к сайтам и правилам", onBack = onBack)
        KCard(modifier = Modifier.padding(horizontal = KDim.Pad, vertical = 8.dp)) {
            Column {
                checks.forEachIndexed { index, check ->
                    if (index > 0) KDivider()
                    val state = states[check.title] ?: CheckState.Idle
                    KRowItem(
                        title = check.title,
                        subtitle = when (state) {
                            is CheckState.Done -> state.detail
                            CheckState.Running -> "проверка…"
                            CheckState.Idle -> check.hint
                        },
                        trailing = {
                            when (state) {
                                CheckState.Running -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = K.Accent,
                                )
                                is CheckState.Done -> Text(
                                    text = if (state.ok) "✓" else "✕",
                                    color = if (state.ok) K.Accent else K.Bad,
                                )
                                CheckState.Idle -> Text(text = "—", color = K.Dim2)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        KButton(
            text = stringResource(R.string.diagnostics_run),
            onClick = { runAll() },
            modifier = Modifier.padding(horizontal = KDim.Pad),
        )
        Spacer(Modifier.height(24.dp))
    }
}
