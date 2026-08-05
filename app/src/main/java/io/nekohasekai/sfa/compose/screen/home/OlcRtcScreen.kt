package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.bg.OlcRtcCore
import io.nekohasekai.sfa.bg.OlcRtcParams
import io.nekohasekai.sfa.bg.OlcRtcWatchdog
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KGroupTitle
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.KScreenHeader
import io.nekohasekai.sfa.compose.theme.KSwitch
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Выход через комнату olcRTC.
 *
 * Параметры комнаты приезжают с сервера вместе с остальной подпиской. Поля ниже —
 * переопределение для отладки: пустое поле означает «как у сети». Токен WB личный,
 * на сервер не кладётся и живёт только здесь.
 */
@Composable
fun OlcRtcScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = K
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(Settings.olcrtcEnabled) }
    var carrier by remember { mutableStateOf(Settings.olcrtcCarrier) }
    var roomId by remember { mutableStateOf(Settings.olcrtcRoomId) }
    var clientId by remember { mutableStateOf(Settings.olcrtcClientId) }
    var keyHex by remember { mutableStateOf(Settings.olcrtcKeyHex) }
    var transport by remember { mutableStateOf(Settings.olcrtcTransport) }
    var socksPort by remember {
        mutableStateOf(Settings.olcrtcSocksPort.takeIf { it > 0 }?.toString().orEmpty())
    }
    var wbToken by remember { mutableStateOf(Settings.olcrtcWbToken) }

    // Состояние тянем не из статики один раз, а перечитываем: канал живёт своей жизнью,
    // и экран должен показывать её, а не момент открытия.
    //
    // Сам экран канал НЕ проверяет: владелец проверки один — [OlcRtcWatchdog], он же по
    // её результату поднимает ядро. Два параллельных SOCKS-запроса дали бы лишний трафик
    // через комнату и сбили бы присмотру счёт отказов. Здесь только чтение.
    var status by remember { mutableStateOf(currentStatus()) }
    LaunchedEffect(enabled) {
        while (true) {
            status = currentStatus()
            delay(1_000)
        }
    }

    fun persist(block: () -> Unit) {
        scope.launch(Dispatchers.IO) { block() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        KScreenHeader(title = "olcRTC", subtitle = "выход через комнату", onBack = onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            KCard {
                KRowItem(
                    title = "Использовать комнату",
                    subtitle = "Ядро поднимает локальный SOCKS5 и уводит трафик через WebRTC. " +
                        "Применяется при следующем подключении.",
                    trailing = {
                        KSwitch(checked = enabled) { checked ->
                            enabled = checked
                            persist { Settings.olcrtcEnabled = checked }
                        }
                    },
                )
                KDivider()
                KRowItem(title = "Состояние", subtitle = status)
            }

            KGroupTitle("Параметры")
            KCard {
                KRowItem(title = "Источник", subtitle = sourceText())
                KDivider()
                KRowItem(title = "Комната", subtitle = roomSummary())
                KDivider()
                KRowItem(
                    title = "Токен WB",
                    subtitle = "Личный, на сервер не кладётся. " +
                        if (wbToken.isBlank()) "Не задан — в комнату пустят только гостем." else "Задан.",
                )
                Spacer(Modifier.height(10.dp))
                OlcRtcField("токен WB", wbToken, secret = true) {
                    wbToken = it
                    persist { Settings.olcrtcWbToken = it }
                }
            }

            KGroupTitle("Переопределение")
            KCard {
                KRowItem(
                    title = "Ручные значения",
                    subtitle = "Пустое поле — берём то, что даёт сеть. Заполненное перебивает её.",
                )
                Spacer(Modifier.height(10.dp))
                OlcRtcField("carrier", carrier) {
                    carrier = it
                    persist { Settings.olcrtcCarrier = it }
                }
                Spacer(Modifier.height(10.dp))
                OlcRtcField("room id", roomId) {
                    roomId = it
                    persist { Settings.olcrtcRoomId = it }
                }
                Spacer(Modifier.height(10.dp))
                OlcRtcField("client id", clientId) {
                    clientId = it
                    persist { Settings.olcrtcClientId = it }
                }
                Spacer(Modifier.height(10.dp))
                OlcRtcField("ключ (hex)", keyHex, secret = true) {
                    keyHex = it
                    persist { Settings.olcrtcKeyHex = it }
                }
                Spacer(Modifier.height(10.dp))
                OlcRtcField("transport", transport) {
                    transport = it
                    persist { Settings.olcrtcTransport = it }
                }
                Spacer(Modifier.height(10.dp))
                OlcRtcField("порт SOCKS", socksPort, numeric = true) { value ->
                    val digits = value.filter { it.isDigit() }.take(5)
                    socksPort = digits
                    val port = digits.toIntOrNull()
                    when {
                        digits.isEmpty() -> persist { Settings.olcrtcSocksPort = 0 }
                        port != null && port in 1..65535 -> persist { Settings.olcrtcSocksPort = port }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * Состояние словами. Отвечает на два разных вопроса отдельно: поднялось ли ядро
 * и идут ли через него данные. «Поднят» без прошедших байтов — это ещё не выход,
 * поэтому такой случай назван своими словами, а не спрятан за общим «поднят».
 */
private fun currentStatus(): String {
    val base = when (val state = OlcRtcCore.state) {
        is OlcRtcCore.State.Unavailable -> "в этой сборке ядра olcRTC нет"
        is OlcRtcCore.State.Starting -> "поднимается"
        is OlcRtcCore.State.Failed -> "не поднят: ${state.reason}"
        is OlcRtcCore.State.Idle -> if (Settings.olcrtcEnabled) "не запускался" else "выключен"
        is OlcRtcCore.State.Ready -> if (!OlcRtcCore.isRunning()) {
            "не поднят: ядро вышло"
        } else {
            when (val health = OlcRtcCore.health) {
                is OlcRtcCore.Health.Live -> "данные идут, ответ за ${health.latencyMs} мс"
                is OlcRtcCore.Health.Dead -> "поднят, но данные не идут: ${health.reason}"
                OlcRtcCore.Health.Unknown -> "поднят, канал ещё не проверен"
            }
        }
    }
    // Подъёмы прячутся от человека только если их не было: сессия, где канал падал
    // и вставал, выглядит иначе, чем ровная.
    val watchdog = OlcRtcWatchdog.note.takeIf { it.isNotBlank() }
        ?: if (OlcRtcWatchdog.restarts > 0) "подъёмов за сессию: ${OlcRtcWatchdog.restarts}" else null
    return if (watchdog == null) base else "$base · $watchdog"
}

private fun sourceText(): String = when (OlcRtcParams.source) {
    OlcRtcParams.Source.Server -> "сеть"
    OlcRtcParams.Source.Manual ->
        if (OlcRtcParams.serverOffers) "вручную (сеть тоже даёт, но перебито)" else "вручную"

    OlcRtcParams.Source.Mixed -> "сеть, часть перебита вручную"
    OlcRtcParams.Source.None -> "нет: сеть комнату не раздаёт, руками тоже не задано"
}

/** Короткая сводка того, с чем реально пойдём в комнату. Секретов нет: ключ — только факт. */
private fun roomSummary(): String {
    val params = OlcRtcParams.resolve()
    if (params.roomId.isBlank()) return "не задана"
    val room = if (params.roomId.length > 8) params.roomId.take(8) + "…" else params.roomId
    val key = if (params.keyHex.isBlank()) "ключа нет" else "ключ есть"
    return "$room · ${params.carrier} · ${params.transport} · порт ${params.socksPort} · $key"
}

@Composable
private fun OlcRtcField(
    label: String,
    value: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val colors = K
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            fontFamily = RobotoMono,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = colors.Dim2,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.Border, RoundedCornerShape(KDim.RadiusM))
                .background(colors.Surface2, RoundedCornerShape(KDim.RadiusM))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "как у сети",
                    fontFamily = Montserrat,
                    fontSize = 14.sp,
                    color = colors.Dim2,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(colors.Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(
                    fontFamily = RobotoMono,
                    fontSize = 14.sp,
                    color = colors.Text,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
