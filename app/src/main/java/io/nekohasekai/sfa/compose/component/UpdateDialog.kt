package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateState

/**
 * Диалог обновления в языке приложения: тёмная карточка, cyan-акцент, два действия.
 *
 * Список изменений и ссылка на релиз убраны: человеку, который просто пользуется
 * сетью, ссылка на GitHub не говорит ничего, а выбор из трёх кнопок тормозит.
 */
@Composable
fun UpdateAvailableDialog(updateInfo: UpdateInfo, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = K.Surface,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = "Есть обновление",
                fontFamily = Montserrat,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.4).sp,
                color = K.Text,
            )
        },
        text = {
            Column {
                Text(
                    text = "Новая версия приложения готова к установке.",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Light,
                    fontSize = 14.sp,
                    color = K.Dim,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ВЕРСИЯ ${updateInfo.versionName}",
                    fontFamily = RobotoMono,
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    color = K.Border,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onUpdate()
            }) {
                Text(
                    text = "ОБНОВИТЬ",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    letterSpacing = 1.4.sp,
                    color = K.Accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "ПОЗЖЕ",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    letterSpacing = 1.4.sp,
                    color = K.Dim,
                )
            }
        },
    )
}

/**
 * Отказ установки словами. На отказ системной проверки приложений — короткая инструкция:
 * кодом этот отказ не снимается, нужен человек у телефона.
 */
@Composable
fun UpdateFailedDialog(otkaz: UpdateState.InstallStatus.Failed, onRetry: (() -> Unit)?, onDismiss: () -> Unit) =
    UpdateNeUdalosDialog(
        zagolovok = "Обновление не установилось",
        tekst = otkaz.error,
        instrukciya = IshodUstanovki.instrukciya(otkaz.prichina),
        onRetry = onRetry,
        onDismiss = onDismiss,
    )

/** Общее окно неудачи обновления — скачивания или установки: причина, повтор сразу и «Позже». */
@Composable
fun UpdateNeUdalosDialog(
    zagolovok: String,
    tekst: String,
    instrukciya: String?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = K.Surface,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = zagolovok,
                fontFamily = Montserrat,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.4).sp,
                color = K.Text,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = tekst,
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Light,
                    fontSize = 14.sp,
                    color = K.Dim,
                )
                if (instrukciya != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = instrukciya,
                        fontFamily = Montserrat,
                        fontSize = 13.sp,
                        color = K.Text,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onRetry?.invoke()
            }) {
                Text(
                    text = if (onRetry != null) "ПОПРОБОВАТЬ ЕЩЁ РАЗ" else "ПОНЯТНО",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    letterSpacing = 1.4.sp,
                    color = K.Accent,
                )
            }
        },
        dismissButton = if (onRetry != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "ПОЗЖЕ",
                        fontFamily = RobotoMono,
                        fontSize = 13.sp,
                        letterSpacing = 1.4.sp,
                        color = K.Dim,
                    )
                }
            }
        } else {
            null
        },
    )
}
