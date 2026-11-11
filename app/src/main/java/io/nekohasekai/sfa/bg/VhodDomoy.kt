package io.nekohasekai.sfa.bg

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.SubscriptionLink
import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultBase64
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вход домой на телефоне (договор KelevraServer docs/VHOD-DOMOY-API.md, часть устройства; Desktop —
 * `internal/sluzhba/dom.go`). Включается пультом `dom.vhod` — пока только телефону Вовы.
 *
 *  - Ключ WireGuard рождается здесь один раз ([X25519]); закрытый не покидает телефон, открытый уходит в мозг
 *    `POST /k/<ключ устройства>/dom/klyuch` (Box кода человека не хранит — в пути ключ устройства с `/u/`,
 *    тот же в `X-Device-Key`). Мозг держит его «ждёт», пока владелец не подтвердит в панели отпечатком —
 *    первыми 16 знаками открытого ключа, отпечаток пишется в журнал.
 *  - Подтверждённому мозг кладёт в сводку `/info` подписанный блок `dom` ([VhodDomoyBlok]); принятый блок
 *    лежит на диске и проверяется заново при чтении — вход домой живёт и без мозга.
 *  - Ядро вписывает «Дом» ([VhodDomoyPatch]) только вне дома: дома туннеля нет вовсе.
 *  - Блок сменился или снят — ядро перечитывает конфиг ([Libbox] serviceReload; дома — при подъёме туннеля).
 */
object VhodDomoy {
    private const val TAG = "KelevraDom"
    private const val FAYL = "kelevra_dom"
    private const val PAUZA_OTPRAVKI_MS = 10 * 60_000L

    @Volatile
    private var posledOtpravka = 0L

    @Volatile
    private var keshBloka: Pair<String, VhodDomoyBlok>? = null

    private fun p() = Application.application.getSharedPreferences(FAYL, Context.MODE_PRIVATE)

    fun vklyuchen(): Boolean = Pult.bool("dom.vhod")

    /** (закрытый, открытый) base64; нет — рождается. */
    @Synchronized
    fun klyuch(): Pair<String, String> {
        val pr = p()
        val z = pr.getString("zakrytyy", null)
        val o = pr.getString("otkrytyy", null)
        if (z != null && o != null) return z to o
        val k = X25519.novyy()
        val zz = PultBase64.zakodirovat(k)
        val oo = PultBase64.zakodirovat(X25519.otkrytyy(k))
        pr.edit().putString("zakrytyy", zz).putString("otkrytyy", oo).remove("sostoyanie").commit()
        KLog.i(TAG, "родился ключ входа домой, отпечаток ${otpechatok(oo)}")
        return zz to oo
    }

    /** Отпечаток для глаз владельца: первые 16 знаков открытого ключа группами по 4 (как мозг). */
    fun otpechatok(pub: String): String = pub.take(16).chunked(4).joinToString(" ")

    private fun slot(): String = p().getString("slot", "").orEmpty()

    /**
     * Сводка `/info` пришла свежей (не с диска): свой слот, блок `dom`, при надобности — отправка ключа.
     * Зовётся из [io.nekohasekai.sfa.compose.screen.home.loadSubscription] на потоке ввода-вывода.
     */
    fun prinyatSvodku(json: JSONObject) {
        json.optJSONObject("device")?.optString("slot")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let {
            if (it != slot()) {
                p().edit().putString("slot", it).apply()
                keshBloka = null
            }
        }
        if (!vklyuchen()) return
        val (_, pub) = klyuch()
        val bylo = blok()
        val syroe = json.optJSONObject("dom")?.toString()
        if (syroe == null) {
            if (bylo != null) {
                KLog.i(TAG, "блока dom в сводке нет — вход домой снят")
                p().edit().remove("blok").apply()
                keshBloka = null
                perechitatYadro()
            }
        } else {
            when (val r = VhodDomoyBlok.razobrat(syroe, slot())) {
                is VhodDomoyBlok.Razbor.Otkaz -> KLog.w(TAG, "блок dom не принят (${r.prichina}) — держу прежний: ${bylo ?: "нет"}")
                is VhodDomoyBlok.Razbor.Ok -> {
                    val b = r.blok
                    val maks = p().getLong("maks_versiya", 0L)
                    when {
                        b.versiya < maks -> KLog.w(TAG, "блок dom версии ${b.versiya} старше принятой $maks — не принят")
                        b.otozvan && b.pub == pub -> {
                            KLog.w(TAG, "подписанный отзыв своего ключа — рождаю новый")
                            p().edit().remove("blok").remove("zakrytyy").remove("otkrytyy").remove("sostoyanie")
                                .putLong("maks_versiya", b.versiya).commit()
                            keshBloka = null
                            klyuch()
                            posledOtpravka = 0L
                            if (bylo != null) perechitatYadro()
                        }
                        b.otozvan -> p().edit().putLong("maks_versiya", b.versiya).apply()
                        b.pub != pub -> KLog.w(TAG, "подписанный блок выдан на другой ключ — не применяю")
                        else -> {
                            p().edit().putString("blok", syroe).putLong("maks_versiya", b.versiya)
                                .putString("sostoyanie", "podtverzhden").apply()
                            keshBloka = syroe to b
                            if (bylo != b) {
                                KLog.i(TAG, "принят блок dom: $b")
                                perechitatYadro()
                            }
                        }
                    }
                }
            }
        }
        if (blok() == null) otpravitKlyuchEsliPora()
    }

    /** Действующий блок с диска (подпись, слот, ключ — заново); нет — null. */
    fun blok(): VhodDomoyBlok? {
        val syroe = p().getString("blok", null) ?: return null
        keshBloka?.let { (s, b) -> if (s == syroe) return b }
        val pub = p().getString("otkrytyy", null) ?: return null
        val r = VhodDomoyBlok.razobrat(syroe, slot())
        val b = (r as? VhodDomoyBlok.Razbor.Ok)?.blok
        if (b == null || b.otozvan || b.pub != pub || b.versiya < p().getLong("maks_versiya", 0L)) {
            KLog.w(TAG, "блок dom на диске не принят (${(r as? VhodDomoyBlok.Razbor.Otkaz)?.prichina ?: "ключ или версия"})")
            return null
        }
        keshBloka = syroe to b
        return b
    }

    /** Для сборки конфига ядра: включено пультом и блок действует. */
    fun dlyaKonfiga(): VhodDomoyPatch.Vhod? {
        if (!vklyuchen()) return null
        val b = blok() ?: return null
        val z = p().getString("zakrytyy", null) ?: return null
        return VhodDomoyPatch.Vhod(z, b)
    }

    private fun perechitatYadro() {
        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
            .onFailure { KLog.i(TAG, "ядро не перечитано (служба не поднята?): ${it.javaClass.simpleName}") }
    }

    /** Открытый ключ — в мозг, пока блока нет (ждёт подтверждения или «Дом» выключен). Не чаще раза в 10 мин. */
    private fun otpravitKlyuchEsliPora() {
        val seychas = System.currentTimeMillis()
        if (seychas - posledOtpravka < PAUZA_OTPRAVKI_MS) return
        posledOtpravka = seychas
        val profil = runCatching {
            runBlocking { ProfileManager.get(Settings.selectedProfile) }
        }.getOrNull()
        if (profil == null || profil.typed.type != TypedProfile.Type.Remote) return
        val l = SubscriptionLink.parse(profil.typed.remoteURL) ?: return
        val (_, pub) = klyuch()
        val url = Kelevra.adresSeti("https://${l.host}/k/${l.code}/dom/klyuch")
        val telo = JSONObject().put("pub", pub).apply { slot().takeIf { it.isNotEmpty() }?.let { put("slot", it) } }
            .toString().toByteArray(Charsets.UTF_8)
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(telo.size)
            conn.setRequestProperty("User-Agent", "kelevra")
            conn.setRequestProperty("Content-Type", "application/json")
            Kelevra.deviceHeaders().forEach { (n, v) -> conn.setRequestProperty(n, v) }
            if (l.kind == 'u') conn.setRequestProperty("X-Device-Key", l.code)
            conn.outputStream.use { it.write(telo) }
            val kod = conn.responseCode
            val otvet = (if (kod in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (kod in 200..299) {
                val o = runCatching { JSONObject(otvet) }.getOrNull()
                val sost = o?.optString("sostoyanie").orEmpty()
                p().edit().putString("sostoyanie", sost).apply()
                KLog.i(
                    TAG,
                    "мозг принял ключ входа домой: $sost, «Дом» ${if (o?.optBoolean("dom") == true) "включён" else "не включён"}, " +
                        "отпечаток ${otpechatok(pub)}",
                )
            } else {
                KLog.w(TAG, "ключ входа домой не принят: ответ $kod")
            }
        } catch (e: Exception) {
            KLog.w(TAG, "ключ входа домой не отправлен: ${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}
