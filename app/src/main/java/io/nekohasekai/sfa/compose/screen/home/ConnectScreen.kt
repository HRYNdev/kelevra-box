package io.nekohasekai.sfa.compose.screen.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.Aktivatsiya
import io.nekohasekai.sfa.KodPole
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono

/**
 * Первый запуск: код -> предупреждение о системном вопросе -> готово.
 *
 * Второй шаг существует ровно затем, чтобы человек не увидел английский
 * системный запрос на пустом месте: вид этого запроса мы поменять не можем,
 * но можем объяснить его заранее своими словами.
 */
@Composable
fun ConnectScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    val context = LocalContext.current
    val vhod = Vhod.vhod
    val sostoyanie by vhod.sostoyanie.collectAsState()

    // Вход живёт в [Vhod], а не здесь: уход с экрана его не обрывает. Экран отмечается,
    // чтобы вход, завершённый без человека, применился при нём (см. [VhodPoKodu]).
    DisposableEffect(Unit) {
        vhod.ekranOtkryt()
        onDispose { vhod.ekranZakryt() }
    }

    var step by remember { mutableIntStateOf(1) }
    // Идущий вход показывает свой код; иначе код из приглашения kelevra://import?kod=
    // подставляется один раз.
    var input by remember {
        mutableStateOf(
            (vhod.sostoyanie.value as? VhodPoKodu.Sostoyanie.Idet)?.vvod?.let { KodPole.syroe(it) }
                ?: Aktivatsiya.ozhidayushchijKod?.also { Aktivatsiya.ozhidayushchijKod = null }
                    ?.let { KodPole.syroe(it) } ?: "",
        )
    }
    val busy = sostoyanie is VhodPoKodu.Sostoyanie.Idet
    val oshibka = (sostoyanie as? VhodPoKodu.Sostoyanie.Oshibka)?.prichina
    val limit = (oshibka as? LimitUstroystv)?.tekst
    // Текст ошибки считается один раз на ошибку: он спрашивает систему, есть ли сеть.
    val error = remember(oshibka) { oshibka?.takeIf { it !is LimitUstroystv }?.let { humanError(it, context) } }

    LaunchedEffect(sostoyanie) {
        if (sostoyanie is VhodPoKodu.Sostoyanie.Gotovo && step == 1) step = 2
    }

    fun connect() {
        if (input.isBlank() || busy) return
        // Второе нажатие во время входа не стартует новый: экран и так показывает идущий.
        vhod.nachat(input.trim())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(26.dp))
        StepDots(step)

        when (step) {
            1 -> if (limit != null) {
                StepLimit(
                    tekst = limit.orEmpty(),
                    onAnother = { vhod.sbrositOshibku(); input = "" },
                    onBack = onBack,
                )
            } else StepCode(
                input = input,
                busy = busy,
                error = error,
                onInput = {
                    input = KodPole.syroe(it)
                    vhod.sbrositOshibku()
                },
                onPaste = { input = clipboardText(context)?.let { KodPole.vstavka(it) } ?: input },
                onNext = { connect() },
                onBack = onBack,
            )

            2 -> StepPermission(
                onNext = {
                    GlobalEventBus.tryEmit(UiEvent.RequestStartService)
                    vhod.zabratGotovo()
                    step = 3
                },
            )

            else -> StepDone(onDone = onDone)
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    val colors = K
    Row(modifier = Modifier.padding(bottom = 24.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = 26.dp, height = 3.dp)
                    .background(
                        if (index < step) colors.Accent else colors.Sunken,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, text: String) {
    val colors = K
    Spacer(Modifier.height(20.dp))
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = colors.Accent,
        modifier = Modifier.size(40.dp),
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        fontFamily = Montserrat,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = (-0.4).sp,
        color = colors.Text,
        textAlign = TextAlign.Center,
    )
    if (text.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            fontFamily = Montserrat,
            fontSize = 14.sp,
            color = colors.Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.StepCode(
    input: String,
    busy: Boolean,
    error: String?,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = K
    StepHeader(
        icon = Icons.Outlined.Lock,
        title = "Вход по коду",
        text = "",
    )

    Spacer(Modifier.height(26.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (error != null) colors.Err else colors.Border,
                RoundedCornerShape(KDim.RadiusM),
            )
            .background(colors.Surface, RoundedCornerShape(KDim.RadiusM))
            .padding(horizontal = 18.dp, vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (input.isEmpty()) {
            Text(
                text = "Код доступа",
                fontFamily = RobotoMono,
                fontSize = 15.sp,
                color = colors.Dim2,
            )
        }
        BasicTextField(
            value = input,
            onValueChange = onInput,
            singleLine = true,
            enabled = !busy,
            visualTransformation = KodPole.transformation,
            cursorBrush = SolidColor(colors.Accent),
            textStyle = TextStyle(
                fontFamily = RobotoMono,
                fontSize = 16.sp,
                letterSpacing = 1.6.sp,
                color = colors.Text,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onNext() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = error,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            color = colors.Err,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(10.dp))
    KButton(text = "Вставить из буфера", onClick = onPaste, ghost = true)

    Spacer(Modifier.weight(1f))
    if (busy) {
        Box(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = colors.Accent,
                strokeWidth = 2.dp,
            )
        }
    } else {
        KButton(text = "Продолжить", onClick = onNext, enabled = input.isNotBlank())
    }
    Spacer(Modifier.height(8.dp))
    KButton(text = "Назад", onClick = onBack, ghost = true)
    Spacer(Modifier.height(20.dp))
}

/** Экран отказа: у кода заведено столько устройств, сколько разрешено. */
@Composable
private fun ColumnScope.StepLimit(tekst: String, onAnother: () -> Unit, onBack: () -> Unit) {
    StepHeader(
        icon = Icons.Outlined.Lock,
        title = tekst,
        text = "",
    )
    Spacer(Modifier.weight(1f))
    KButton(text = "Ввести другой код", onClick = onAnother)
    Spacer(Modifier.height(8.dp))
    KButton(text = "Назад", onClick = onBack, ghost = true)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ColumnScope.StepPermission(onNext: () -> Unit) {
    val colors = K
    StepHeader(
        icon = Icons.Outlined.Shield,
        title = "Разрешения",
        text = "Android запросит разрешение на уведомления и на подключение к сети Kelevra. " +
            "Оба запроса нужно подтвердить.",
    )

    Spacer(Modifier.height(24.dp))
    // показываем оба запроса в том порядке, в каком они реально прилетают
    SystemAsk(
        title = stringResource(R.string.system_ask_notifications_title),
        yes = stringResource(R.string.system_ask_notifications_yes),
        no = stringResource(R.string.system_ask_notifications_no),
    )
    Spacer(Modifier.height(10.dp))
    SystemAsk(
        title = stringResource(R.string.system_ask_vpn_title),
        yes = stringResource(R.string.system_ask_vpn_yes),
        no = stringResource(R.string.system_ask_vpn_no),
    )

    Spacer(Modifier.weight(1f))
    KButton(text = "Понятно", onClick = onNext)
    Spacer(Modifier.height(20.dp))
}

/** Образец системного запроса: человек узнаёт его, когда тот прилетит. */
@Composable
private fun SystemAsk(title: String, yes: String, no: String) {
    val colors = K
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.Sunken, RoundedCornerShape(KDim.RadiusS))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            color = colors.Dim,
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text(text = no, fontFamily = Montserrat, fontSize = 13.sp, color = colors.Dim2)
            Spacer(Modifier.size(16.dp))
            Text(
                text = yes,
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = colors.Accent,
            )
        }
    }
}

@Composable
private fun ColumnScope.StepDone(onDone: () -> Unit) {
    StepHeader(
        icon = Icons.Outlined.CheckCircle,
        title = "Готово",
        text = "Сеть подключена.\nПодключение включается автоматически.",
    )
    Spacer(Modifier.weight(1f))
    KButton(text = "Открыть", onClick = onDone)
    Spacer(Modifier.height(20.dp))
}

/** Код часто присылают сообщением: вставка из буфера избавляет от ручного ввода. */
/** Есть ли у телефона рабочая сеть прямо сейчас. Свой туннель тут не мешает: он тоже сеть. */
private fun hasNetwork(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    val active = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(active) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    return manager?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * Понятный текст вместо стектрейса: человек должен понять, что делать дальше.
 *
 * Тип исключения надёжнее его текста: без сети сюда приходит UnknownHostException,
 * в message у которого лежит только имя хоста, и человек видел общее «не удалось
 * подключиться», хотя дело не в коде, а в том, что интернета нет вовсе.
 */
private fun humanError(e: Throwable, context: Context): String {
    val text = (e.message ?: "").lowercase()
    // Сначала спрашиваем систему, а не исключение. Конфиг качает ядро (libbox), и наружу
    // оно отдаёт свою ошибку от Go: ни UnknownHostException, ни знакомых слов в тексте там
    // нет. Проверено в эмуляторе 07.08.2026 с выключенными вайфаем и мобильной сетью:
    // код был верный, а интернета не было вовсе.
    if (!hasNetwork(context)) return "Нет связи с интернетом"
    if (e is KodNeNajden) return "Код не найден"
    val net = generateSequence(e) { it.cause }.any {
        it is java.net.UnknownHostException ||
            it is java.net.ConnectException ||
            it is java.net.SocketTimeoutException ||
            it is java.net.NoRouteToHostException
    }
    if (net) return "Нет связи с интернетом"
    return when {
        e is java.io.IOException && e !is java.net.ProtocolException -> "Нет связи с сервером"
        "unable to resolve host" in text || "failed to connect" in text || "timeout" in text ->
            "Нет связи с сервером"
        "404" in text || "not found" in text -> "Код не найден"
        else -> "Подключение не выполнено"
    }
}
