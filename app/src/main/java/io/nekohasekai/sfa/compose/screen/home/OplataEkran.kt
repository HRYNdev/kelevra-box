package io.nekohasekai.sfa.compose.screen.home

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KGroupTitle
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.Montserrat

/** Срок словами для карточки: «Подписка до 14.11» / «Подписка закончилась»; срока нет — null. */
@Composable
fun oplataSrokTekst(info: OplataInfo, now: Long): String? = when (srokOplaty(info.srokMs, now)) {
    SrokOplaty.Konchilsya -> stringResource(R.string.oplata_zakonchilas)
    SrokOplaty.Skoro, SrokOplaty.Obychnyj ->
        if (info.srokMs > 0L) stringResource(R.string.oplata_podpiska_do, srokDdMm(info.srokMs)) else null
}

/** Заголовок карточки «Подписка» у платящего по [vidOplaty]; бессрочно — null (заголовок как был). */
@Composable
fun oplataZagolovok(info: OplataInfo, vid: VidOplaty): String? = when (vid.zagolovok) {
    ZagolovokOplaty.OplachenoDo -> stringResource(R.string.oplata_oplacheno_do, srokDdMm(info.srokMs))
    ZagolovokOplaty.PodpiskaDo -> stringResource(R.string.oplata_podpiska_do, srokDdMm(info.srokMs))
    ZagolovokOplaty.Zakonchilas -> stringResource(R.string.oplata_zakonchilas)
    ZagolovokOplaty.KakBylo -> null
}

/**
 * Блок «Оплата» в разделе подписки: срок, цена, «Оплатить» всегда (заплатить заранее), «Оплата
 * проверяется» при ждущей заявке, ниже «История» подтверждённых оплат (пусто — блока нет).
 */
@Composable
fun OplataRazdel(info: OplataInfo, now: Long, onOplatit: () -> Unit) {
    val colors = K
    val srok = srokOplaty(info.srokMs, now)
    val srokTekst = when {
        srok == SrokOplaty.Konchilsya -> stringResource(R.string.oplata_zakonchilas)
        info.srokMs > 0L -> stringResource(R.string.oplata_oplacheno_do, srokDdMm(info.srokMs))
        else -> null
    }
    val cenaTekst = stringResource(R.string.oplata_v_mesyac, info.cena)
    KGroupTitle(stringResource(R.string.oplata_zagolovok))
    KCard {
        KRowItem(
            title = srokTekst ?: cenaTekst,
            subtitle = if (srokTekst != null) cenaTekst else null,
            titleColor = if (srok == SrokOplaty.Obychnyj) null else colors.Warn,
        )
    }
    Spacer(Modifier.height(KDim.Gap))
    KButton(
        text = stringResource(R.string.oplata_oplatit),
        onClick = onOplatit,
    )
    if (info.zhdet) {
        Soobshchenie(stringResource(R.string.oplata_proveryaetsya))
    }
    if (info.istoriya.isNotEmpty()) {
        KGroupTitle(stringResource(R.string.oplata_istoriya))
        KCard {
            info.istoriya.forEachIndexed { i, z ->
                if (i > 0) KDivider()
                KRowItem(
                    title = if (z.doMs > 0L) {
                        stringResource(R.string.oplata_istoriya_zapis, dataDdMmGggg(z.dataMs), z.summa, srokDdMm(z.doMs))
                    } else {
                        stringResource(R.string.oplata_istoriya_zapis_bez_sroka, dataDdMmGggg(z.dataMs), z.summa)
                    },
                )
            }
        }
    }
}

/**
 * Строка оплаты внутри карточки подписки: «249 ₽» и справа «Оплатить» либо «Оплата проверяется».
 * Нажатие — шторка оплаты.
 */
@Composable
fun OplataStroka(info: OplataInfo, onOpen: () -> Unit) {
    val colors = K
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.oplata_summa, info.cena),
            fontFamily = Montserrat,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = colors.Text,
            modifier = Modifier.weight(1f),
        )
        if (info.zhdet) {
            Text(
                text = stringResource(R.string.oplata_proveryaetsya),
                fontFamily = Montserrat,
                fontSize = 13.sp,
                color = colors.Dim,
            )
        } else {
            Text(
                text = stringResource(R.string.oplata_oplatit),
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = colors.AccentInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(KDim.RadiusM))
                    .background(colors.Accent)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Шторка оплаты: сумма, перевод по ссылке, номер для другого банка, «Я оплатил». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OplataShtorka(info: OplataInfo, onDismiss: () -> Unit) {
    val colors = K
    val context = LocalContext.current
    val otpravka by OplataSostoyanie.otpravka.collectAsState()
    var ssylkaNeOtkrylas by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = System.currentTimeMillis()
    ModalBottomSheet(
        onDismissRequest = {
            OplataSostoyanie.zabytOtpravku()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = colors.Surface,
        contentColor = colors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KDim.Pad, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.oplata_zagolovok),
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = colors.Text,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // Сумма крупно, рядом — копирование числа (для перевода из другого банка).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.oplata_summa, info.cena),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 36.sp,
                    color = colors.Text,
                    modifier = Modifier.weight(1f),
                )
                KnopkaKopirovat(context, info.cena.toString())
            }
            oplataSrokTekst(info, now)?.let { srok ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = srok,
                    fontFamily = Montserrat,
                    fontSize = 14.sp,
                    color = if (srokOplaty(info.srokMs, now) == SrokOplaty.Obychnyj) colors.Dim else colors.Warn,
                )
            }
            if (info.ssylka.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                KButton(
                    text = stringResource(R.string.oplata_perevesti),
                    onClick = { ssylkaNeOtkrylas = !otkrytSsylku(context, info.ssylka) },
                )
                if (ssylkaNeOtkrylas) {
                    Soobshchenie(stringResource(R.string.oplata_ssylka_ne_otkrylas))
                }
            }
            if (info.nomer.isNotEmpty()) {
                KGroupTitle(stringResource(R.string.oplata_drugoy_bank))
                KCard {
                    KRowItem(
                        label = stringResource(R.string.oplata_nomer),
                        title = info.nomer,
                        trailing = { KnopkaKopirovat(context, info.nomer) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (info.zhdet) {
                Text(
                    text = stringResource(R.string.oplata_proveryaetsya),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colors.Dim,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                if (info.ssylka.isNotEmpty()) {
                    // Есть кнопка перевода — она главная; «Я оплатил» вторая, но явно рабочая (не «серая»).
                    KnopkaVtoraya(
                        text = stringResource(R.string.oplata_ya_oplatil),
                        enabled = otpravka != OplataSostoyanie.Otpravka.Idet,
                        onClick = { OplataSostoyanie.oplatil() },
                    )
                } else {
                    KButton(
                        text = stringResource(R.string.oplata_ya_oplatil),
                        enabled = otpravka != OplataSostoyanie.Otpravka.Idet,
                        onClick = { OplataSostoyanie.oplatil() },
                    )
                }
                when (otpravka) {
                    OplataSostoyanie.Otpravka.UzheOtpravleno -> Soobshchenie(stringResource(R.string.oplata_uzhe_otpravleno))
                    OplataSostoyanie.Otpravka.Oshibka -> Soobshchenie(stringResource(R.string.oplata_net_svyazi), colors.Err)
                    else -> Unit
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun KnopkaVtoraya(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = K
    val shape = RoundedCornerShape(KDim.RadiusM)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.5.dp, if (enabled) colors.Accent else colors.Border, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = Montserrat,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = if (enabled) colors.Accent else colors.Dim2,
        )
    }
}

@Composable
private fun Soobshchenie(tekst: String, cvet: androidx.compose.ui.graphics.Color = K.Dim) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = tekst,
        fontFamily = Montserrat,
        fontSize = 13.sp,
        color = cvet,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun KnopkaKopirovat(context: Context, tekst: String) {
    val opisanie = stringResource(R.string.oplata_kopirovat)
    val skopirovano = stringResource(R.string.oplata_skopirovano)
    Icon(
        imageVector = Icons.Outlined.ContentCopy,
        contentDescription = opisanie,
        tint = K.Dim,
        modifier = Modifier
            .clip(RoundedCornerShape(KDim.RadiusM))
            .clickable { kopirovat(context, tekst, skopirovano) }
            .padding(10.dp)
            .size(20.dp),
    )
}

private fun kopirovat(context: Context, tekst: String, skopirovano: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText("Kelevra", tekst)) }
    // С Android 13 система сама показывает, что скопировано; раньше — своё короткое сообщение.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, skopirovano, Toast.LENGTH_SHORT).show()
    }
}

/** Ссылка на перевод — в браузер или приложение банка. false — открыть нечем. */
private fun otkrytSsylku(context: Context, ssylka: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ssylka)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
