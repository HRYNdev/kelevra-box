package io.nekohasekai.sfa.compose.screen.home

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KSwitch
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppRow(
    val name: String,
    val packageName: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val system: Boolean,
)

/**
 * Приложения, которые ходят мимо туннеля.
 *
 * Чужой экран показывал весь список вперемешку с системными службами и писал
 * по-английски. Здесь: сначала выбранные, потом обычные приложения, поиск, и
 * никакого системного мусора, пока его не попросят.
 */
@Composable
fun AppsBypassScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = K
    val context = LocalContext.current

    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(Settings.perAppProxyList.toSet()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
        loading = false
    }

    fun toggle(pkg: String) {
        selected = if (pkg in selected) selected - pkg else selected + pkg
        Settings.perAppProxyList = selected
        // список исключений применяется при следующем запуске сети
        Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
    }

    val visible = apps
        .filter { showSystem || !it.system || it.packageName in selected }
        .filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        .sortedWith(compareByDescending<AppRow> { it.packageName in selected }.thenBy { it.name.lowercase() })

    Column(modifier = modifier.fillMaxSize().background(colors.Bg)) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Приложения мимо сети",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = colors.Text,
                )
                Text(
                    text = "Выбранные ходят напрямую, без туннеля",
                    fontFamily = Montserrat,
                    fontSize = 12.sp,
                    color = colors.Dim,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KDim.Pad)
                .background(colors.Surface, RoundedCornerShape(KDim.RadiusS))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Поиск по названию",
                    fontFamily = Montserrat,
                    fontSize = 14.sp,
                    color = colors.Dim2,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                cursorBrush = SolidColor(colors.Accent),
                textStyle = TextStyle(fontFamily = Montserrat, fontSize = 14.sp, color = colors.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KDim.Pad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selected.isEmpty()) "Ничего не выбрано" else "Выбрано: ${selected.size}",
                fontFamily = RobotoMono,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
                color = colors.Dim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (showSystem) "скрыть системные" else "показать системные",
                fontFamily = Montserrat,
                fontSize = 12.sp,
                color = colors.Accent,
                modifier = Modifier.clickable { showSystem = !showSystem },
            )
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.Accent, strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = KDim.Pad)) {
                items(visible, key = { it.packageName }) { app ->
                    KCard(onClick = { toggle(app.packageName) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                )
                            } else {
                                Box(modifier = Modifier.size(34.dp))
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    fontFamily = Montserrat,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    color = colors.Text,
                                )
                                Text(
                                    text = app.packageName,
                                    fontFamily = RobotoMono,
                                    fontSize = 10.sp,
                                    color = colors.Dim2,
                                )
                            }
                            Spacer(Modifier.size(12.dp))
                            KSwitch(app.packageName in selected) { toggle(app.packageName) }
                        }
                    }
                    Spacer(Modifier.height(KDim.Gap))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun loadApps(context: android.content.Context): List<AppRow> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != context.packageName }
        .map { info ->
            AppRow(
                name = pm.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                icon = runCatching {
                    pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                }.getOrNull(),
                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
            )
        }
}
