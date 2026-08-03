package io.nekohasekai.sfa.compose.screen.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono

/**
 * Настройки в том же языке, что и главный: navy, моно-лейблы капсом,
 * 1px-линии вместо карточек, один cyan-акцент, стрелка вместо шеврона.
 */
@Composable
fun SimpleSettingsScreen(
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    onConnectByCode: () -> Unit,
    onAppsBypass: () -> Unit,
    onCheck: () -> Unit,
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(34.dp))

        Text(
            text = stringResource(R.string.title_settings).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = K.Dim,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(26.dp))

        SectionLabel(stringResource(R.string.settings_group_network))
        Line()
        Item(
            title = stringResource(R.string.settings_connect_title),
            value = stringResource(R.string.settings_connect_hint),
            onClick = onConnectByCode,
        )
        Line()
        Item(
            title = stringResource(R.string.settings_apps_title),
            value = stringResource(R.string.settings_apps_hint),
            onClick = onAppsBypass,
        )
        Line()

        Spacer(modifier = Modifier.height(30.dp))

        SectionLabel(stringResource(R.string.settings_group_behavior))
        Line()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_autostart_title),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    color = K.Text,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_autostart_hint),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Light,
                    fontSize = 13.sp,
                    color = K.Dim,
                )
            }
            Switch(
                checked = autoStart,
                onCheckedChange = onAutoStartChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = K.Bg,
                        checkedTrackColor = K.Accent,
                        uncheckedThumbColor = K.Dim,
                        uncheckedTrackColor = K.Surface,
                        uncheckedBorderColor = K.Border,
                    ),
            )
        }
        Line()

        Spacer(modifier = Modifier.height(30.dp))

        SectionLabel(stringResource(R.string.settings_group_service))
        Line()
        Item(
            title = stringResource(R.string.title_diagnostics),
            value = stringResource(R.string.settings_check_hint),
            onClick = onCheck,
        )
        Line()
        Item(
            title = stringResource(R.string.settings_advanced),
            value = "",
            onClick = onAdvanced,
            dimmed = true,
        )
        Line()

        Spacer(modifier = Modifier.height(34.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(K.Border))
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "KELEVRA ${BuildConfig.VERSION_NAME}",
                fontFamily = RobotoMono,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
                color = K.Border,
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = K.Accent,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun Line() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(K.Border))
}

@Composable
private fun Item(
    title: String,
    value: String,
    onClick: () -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = Montserrat,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = if (dimmed) K.Dim else K.Text,
            )
            if (value.isNotEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = value,
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Light,
                    fontSize = 13.sp,
                    color = K.Dim,
                )
            }
        }
        Text(text = "→", fontFamily = RobotoMono, fontSize = 14.sp, color = K.Accent)
    }
}
