package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Подключение по коду: одно поле и одна кнопка.
 *
 * Всё остальное (скачать настройки, проверить, сохранить, выбрать) делается само —
 * человеку, которому дали код, не нужно знать слов «профиль» и «подписка».
 */
@Composable
fun ConnectScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { importByCode(input.trim()) }
            }
            busy = false
            result.fold(
                onSuccess = { onDone() },
                onFailure = {
                    android.util.Log.w("KelevraConnect", "подключение по коду не удалось", it)
                    error = humanError(it)
                },
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(K.Bg).padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(34.dp))
        Text(
            text = "← НАЗАД",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = K.Dim,
            modifier = Modifier.clickable { onBack() },
        )

        Spacer(modifier = Modifier.height(46.dp))
        Text(
            text = "Подключение",
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            letterSpacing = (-0.8).sp,
            color = K.Text,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Введите код доступа, который вам дали. Дальше всё настроится само.",
            fontFamily = Montserrat,
            fontWeight = FontWeight.Light,
            fontSize = 15.sp,
            color = K.Dim,
        )

        Spacer(modifier = Modifier.height(34.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (error != null) K.Warn else K.Border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            if (input.isEmpty()) {
                Text(
                    text = "код или ссылка",
                    fontFamily = RobotoMono,
                    fontSize = 15.sp,
                    color = K.Border,
                )
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it; error = null },
                singleLine = true,
                enabled = !busy,
                cursorBrush = SolidColor(K.Accent),
                textStyle =
                    LocalTextStyle.current.merge(
                        TextStyle(fontFamily = RobotoMono, fontSize = 15.sp, color = K.Text),
                    ),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(onGo = { connect() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error!!,
                fontFamily = Montserrat,
                fontWeight = FontWeight.Light,
                fontSize = 14.sp,
                color = K.Warn,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        if (input.isBlank() || busy) K.Surface else K.Accent,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = input.isNotBlank() && !busy) { connect() },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = K.Accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "ПОДКЛЮЧИТЬСЯ",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    letterSpacing = 1.6.sp,
                    color = if (input.isBlank()) K.Dim else K.Bg,
                )
            }
        }
    }
}

/** Понятный текст вместо стектрейса: человек должен понять, что делать дальше. */
private fun humanError(e: Throwable): String {
    val text = (e.message ?: "").lowercase()
    return when {
        "unable to resolve host" in text || "failed to connect" in text || "timeout" in text ->
            "Нет связи с сервером. Проверьте интернет и попробуйте ещё раз."
        "404" in text || "not found" in text -> "Такого кода нет. Проверьте, правильно ли он введён."
        else -> "Не получилось подключиться. Проверьте код и интернет."
    }
}

/** Скачивает настройки по коду и делает подключение активным. */
private suspend fun importByCode(code: String) {
    val url = Kelevra.normalizeSubscription(code)
    val content = HTTPClient().use { it.getString(url) }
    Libbox.checkConfig(content)

    val typed =
        TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = url
            autoUpdate = true
            autoUpdateInterval = 60
            lastUpdated = Date()
        }
    val profile = Profile(name = "Своя сеть", typed = typed).apply { userOrder = ProfileManager.nextOrder() }

    val app = io.nekohasekai.sfa.Application.application
    val dir = File(app.filesDir, "configs").also { it.mkdirs() }
    val file = File(dir, "${ProfileManager.nextFileID()}.json")
    typed.path = file.path
    file.writeText(content)

    ProfileManager.create(profile, andSelect = true)
    io.nekohasekai.sfa.bg.UpdateProfileWork.reconfigureUpdater()
}
