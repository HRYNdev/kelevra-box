package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.nekohasekai.sfa.update.UpdateInfo

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
