package io.nekohasekai.sfa.compose.screen.home

import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.Montserrat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сообщение о проблеме.
 *
 * К тексту сама прикладывается техническая справка: без неё человек не сможет
 * рассказать то, что нужно для разбора, а сочинять её он не должен.
 */
@Composable
fun ComplaintScreen(onBack: () -> Unit, serviceRunning: Boolean, activeOutbound: String?) {
    val colors = K
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (ticket != null) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.Bg).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(120.dp))
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                tint = colors.Accent,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Отправлено",
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                color = colors.Text,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Обращение №${ticket}.\nРазберёмся и починим.",
                fontFamily = Montserrat,
                fontSize = 14.sp,
                color = colors.Dim,
            )
            Spacer(Modifier.height(28.dp))
            KButton(text = "Готово", onClick = onBack)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.Bg)) {
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
            Text(
                text = "Сообщить о проблеме",
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = colors.Text,
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            Text(
                text = "Опишите, что работает не так. К сообщению будет приложена техническая информация о подключении.",
                fontFamily = Montserrat,
                fontSize = 14.sp,
                color = colors.Dim,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(KDim.RadiusM))
                    .background(colors.Surface)
                    .padding(14.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Например: сайт не открывается при мобильном интернете",
                        fontFamily = Montserrat,
                        fontSize = 14.sp,
                        color = colors.Dim2,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        fontFamily = Montserrat,
                        fontSize = 14.sp,
                        color = colors.Text,
                    ),
                    cursorBrush = SolidColor(colors.Accent),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "К сообщению прилагается техническая информация",
                    fontFamily = Montserrat,
                    fontSize = 13.sp,
                    color = colors.Dim,
                )
            }

            if (error != null) {
                Text(
                    text = error!!,
                    fontFamily = Montserrat,
                    fontSize = 13.sp,
                    color = colors.Err,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            KButton(
                text = if (sending) "Отправка…" else "Отправить",
                enabled = text.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            sendComplaint(text, serviceRunning, activeOutbound)
                        }
                        sending = false
                        result.fold(
                            onSuccess = { ticket = it },
                            onFailure = { error = "Не удалось отправить. Проверьте подключение и повторите." },
                        )
                    }
                },
            )
        }
    }
}

/**
 * Отправка жалобы на свой сервер. Телеграм не дёргаем: обращения копятся на
 * сервере, там их и разбирают.
 */
private fun sendComplaint(
    text: String,
    serviceRunning: Boolean,
    activeOutbound: String?,
): Result<String> = runCatching {
    val body = JSONObject().apply {
        put("text", text)
        // Кто это и с чего — одним местом на всё приложение: те же значения уходят
        // заголовками с запросом конфига, сводки и логов.
        put("device_id", Kelevra.deviceId)
        put("app_version", Kelevra.appVersion)
        put("android", Build.VERSION.RELEASE)
        put("model", Kelevra.deviceModel)
        put("running", serviceRunning)
        put("outbound", activeOutbound ?: "")
    }.toString()

    val conn = URL("https://${Kelevra.SUBSCRIPTION_HOST}/report").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    Kelevra.deviceHeaders().forEach { (name, value) -> conn.setRequestProperty(name, value) }
    conn.outputStream.use { it.write(body.toByteArray()) }
    val code = conn.responseCode
    val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.readText().orEmpty()
    conn.disconnect()
    if (code !in 200..299) error("http $code")
    runCatching { JSONObject(response).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
}
