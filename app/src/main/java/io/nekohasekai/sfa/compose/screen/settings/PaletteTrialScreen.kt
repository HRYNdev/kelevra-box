// VREMENNO: vybor palitry, ubrat posle resheniya Vovy (25.08.2026)
// Весь файл временный: экран живёт только до выбора оттенка и вида круга.
package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.PaletteTrial
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.compose.theme.TrialAccents
import io.nekohasekai.sfa.compose.theme.TrialDial
import io.nekohasekai.sfa.compose.theme.wcagContrast
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import java.util.Locale

/** Фон тёмной темы: контраст акцента считается именно к нему, там и «болото». */
private val DarkBg = Color(0xFF111310)

private val DialLetters = listOf("A", "B", "C", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteTrialScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text("Пробные палитры") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
        )
    }

    val colors = K

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            text = "Временный экран для выбора цвета. Как решишь — он исчезнет вместе " +
                "с этим разделом. Цифра справа — контраст акцента к фону тёмной темы " +
                "(#111310) по WCAG: чем меньше, тем сильнее оттенок тонет в фоне. " +
                "Жёлтым помечено то, что ниже порога AAA (7.0).",
            fontFamily = Montserrat,
            fontSize = 13.sp,
            color = colors.Dim,
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("ОТТЕНОК АКЦЕНТА")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KDim.RadiusM))
                .background(colors.Surface)
                .border(1.dp, colors.Border, RoundedCornerShape(KDim.RadiusM)),
        ) {
            TrialAccents.forEachIndexed { index, accent ->
                val selected = index == PaletteTrial.accentIndex
                val ratio = wcagContrast(accent.dark, DarkBg)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PaletteTrial.selectAccent(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (colors.isDark) accent.dark else accent.light)
                            .border(1.dp, colors.Border, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = accent.title,
                        fontFamily = Montserrat,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        color = colors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f", ratio),
                        fontFamily = RobotoMono,
                        fontSize = 12.sp,
                        // 7.0 — порог AAA по WCAG. Ниже него оттенок заметно садится в фон.
                        color = if (ratio < 7.0) colors.Warn else colors.Dim,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Выбрано",
                                tint = colors.Accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("КРУГ НА ГЛАВНОМ")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KDim.RadiusM))
                .background(colors.Surface)
                .border(1.dp, colors.Border, RoundedCornerShape(KDim.RadiusM)),
        ) {
            TrialDial.entries.forEachIndexed { index, variant ->
                val selected = index == PaletteTrial.dialIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PaletteTrial.selectDial(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = DialLetters[index] + ". " + variant.title,
                            fontFamily = Montserrat,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 15.sp,
                            color = colors.Text,
                        )
                        Text(
                            text = variant.note,
                            fontFamily = Montserrat,
                            fontSize = 12.sp,
                            color = colors.Dim,
                        )
                    }
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Выбрано",
                                tint = colors.Accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Выбор применяется сразу и переживает перезапуск.",
            fontFamily = Montserrat,
            fontSize = 12.sp,
            color = colors.Dim2,
        )
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontFamily = RobotoMono,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        color = K.Dim2,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}
