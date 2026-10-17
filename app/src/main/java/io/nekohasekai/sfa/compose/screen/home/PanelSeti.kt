package io.nekohasekai.sfa.compose.screen.home

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.SubscriptionLink
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KScreenHeader
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Панель своей сети внутри приложения (17.10.2026: «вшить админку в kelevra… чтоб я мог пользоваться»).
 *
 * Пароль не нужен: у телефона в своей сети есть ключ устройства (профиль /u/<ключ>). Приложение
 * предъявляет его мозгу, мозг узнаёт устройство админа и отдаёт одноразовую ссылку на минуту —
 * она открывается здесь же и меняется на обычную сессию панели. Пункт виден только тем, кому
 * сервер прислал в сводке `panel: true` ([SubscriptionInfo.panel]).
 */
sealed interface PanelSostoyanie {
    data object Zhdem : PanelSostoyanie
    data class Gotovo(val ssylka: String) : PanelSostoyanie
    data class Oshibka(val tekst: String) : PanelSostoyanie
}

/** Одноразовая ссылка на панель для этого устройства или слова, почему её нет. */
suspend fun propuskPaneli(): PanelSostoyanie = withContext(Dispatchers.IO) {
    runCatching {
        val profil = ProfileManager.get(Settings.selectedProfile)
            ?: return@runCatching PanelSostoyanie.Oshibka("Профиль не выбран")
        if (profil.typed.type != TypedProfile.Type.Remote) return@runCatching PanelSostoyanie.Oshibka("Профиль не из сети")
        val p = SubscriptionLink.parse(profil.typed.remoteURL)
        if (p == null || p.kind != 'u') {
            return@runCatching PanelSostoyanie.Oshibka("Панель открывается с профиля, подключённого по коду")
        }
        val conn = URL(Kelevra.adresSeti("https://${p.host}/panel/api/v1/vhod/ustroystvo")).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", "kelevra")
        conn.setRequestProperty("X-Device-Key", p.code)
        Kelevra.deviceHeaders().forEach { (name, value) -> conn.setRequestProperty(name, value) }
        conn.outputStream.use { }
        val kod = conn.responseCode
        val tekst = (if (kod in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        val json = runCatching { JSONObject(tekst) }.getOrNull()
        val ssylka = json?.optString("ssylka").orEmpty()
        when {
            kod in 200..299 && ssylka.startsWith("https://") -> PanelSostoyanie.Gotovo(ssylka)
            kod == 403 -> PanelSostoyanie.Oshibka("Этому устройству панель не положена")
            kod in 500..599 -> PanelSostoyanie.Oshibka("Мозг сети сейчас недоступен — связь при этом работает")
            else -> PanelSostoyanie.Oshibka(json?.optString("oshibka")?.takeIf { it.isNotBlank() } ?: "Панель ответила $kod")
        }
    }.getOrElse { PanelSostoyanie.Oshibka("Нет связи с сетью: ${it.javaClass.simpleName}") }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelSetiScreen(onBack: () -> Unit) {
    var sost by remember { mutableStateOf<PanelSostoyanie>(PanelSostoyanie.Zhdem) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(Unit) { sost = propuskPaneli() }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }
    // Тема приложения уходит панели в User-Agent: по «KelevraApp» она прячет свою шапку
    // (шапка здесь своя, со стрелкой выхода), по «tema/…» берёт светлую или тёмную.
    val tyomnaya = K.isDark
    val fon = K.Bg.toArgb()
    // Отступы под системные панели даёт Scaffold главного окна, как всем внутренним экранам.
    Column(Modifier.fillMaxSize().background(K.Bg)) {
        KScreenHeader(title = "Панель сети", onBack = onBack)
        // imePadding: клавиатура ужимает окно панели, а не ложится поверх шторки
        // (иначе в «Новом человеке» кнопка «Создать» под клавишами).
        Box(Modifier.fillMaxWidth().weight(1f).imePadding(), contentAlignment = Alignment.Center) {
            when (val s = sost) {
                PanelSostoyanie.Zhdem -> Podpis("Открываю панель…")
                is PanelSostoyanie.Oshibka -> Podpis(s.tekst)
                is PanelSostoyanie.Gotovo -> AndroidView(
                    // Без обрезки WebView рисует поверх шапки.
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    factory = { ctx ->
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        WebView(ctx).apply {
                            // Без явного MATCH_PARENT WebView внутри Compose считает высоту окна
                            // нулевой: 100vh = 0, и шторки панели (QR, новый человек) выходили
                            // высотой в пиксель — затемнение есть, содержимого нет (замер 17.10).
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            // Без своего фона WebView до первой отрисовки страницы белый — вспышка в тёмной теме.
                            setBackgroundColor(fon)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Панель свёрстана в px: системный крупный шрифт ломал ей строки.
                            settings.textZoom = 100
                            settings.userAgentString = settings.userAgentString +
                                " KelevraApp " + (if (tyomnaya) "tema/dark" else "tema/light")
                            // Тёмную тему панель рисует сама; затемнение WebView поверх неё портит цвета.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            // Ссылки панели остаются внутри окна; уходить в браузер незачем.
                            webViewClient = WebViewClient()
                            loadUrl(s.ssylka)
                            webView = this
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Podpis(tekst: String) {
    Text(
        text = tekst,
        fontFamily = Montserrat,
        fontSize = 15.sp,
        color = K.Dim,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}
