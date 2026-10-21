package io.nekohasekai.sfa

import android.content.Context
import io.nekohasekai.sfa.bg.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сетевая часть входа по коду: справочник дверей и активация устройства.
 * Выбор двери по ответам вынесен в [Priglashenie.vybratDver] (проверяется тестами).
 */
object Aktivatsiya {
    private const val TAG = "KelevraAktivatsiya"

    /** Код из ссылки `kelevra://import?kod=`: подставляется в поле ввода экрана подключения. */
    @Volatile
    var ozhidayushchijKod: String? = null

    private fun prefs(): android.content.SharedPreferences =
        Application.application.getSharedPreferences("kelevra", Context.MODE_PRIVATE)

    /** Двери из прошлых /info, которые отдал сервер. */
    fun zapomnitDveri(spisok: List<String>) {
        val chistye = spisok.map { it.trim().lowercase() }.filter { Priglashenie.dverGodna(it) }.toSet()
        if (chistye.isNotEmpty()) prefs().edit().putStringSet("dveri_info", chistye).apply()
    }

    private fun zapomnennye(): List<String> = prefs().getStringSet("dveri_info", emptySet()).orEmpty().sorted()

    private fun keshSpravochnika(): List<String> =
        Priglashenie.razobratSpravochnik(prefs().getString("dveri_json", "").orEmpty())

    /** Список дверей. Справочник обновляется из релиза; ошибка загрузки не мешает: берётся кэш. */
    suspend fun spisokDverej(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(Priglashenie.SPRAVOCHNIK_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "kelevra")
                if (conn.responseCode == 200) {
                    val tekst = conn.inputStream.bufferedReader().readText()
                    if (Priglashenie.razobratSpravochnik(tekst).isNotEmpty()) {
                        prefs().edit().putString("dveri_json", tekst).apply()
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { KLog.i(TAG, "справочник дверей не загружен: ${it.javaClass.simpleName}") }
        Priglashenie.spisokDverej(SubscriptionLink.DEFAULT_HOST, keshSpravochnika(), zapomnennye())
    }

    /** `POST /k/<код>/ustroystvo`; null, если дверь не ответила. */
    private fun aktivirovat(dver: String, kod: String): Priglashenie.Otvet? = try {
        val conn = URL(Kelevra.adresSeti("https://$dver/k/$kod/ustroystvo")).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("User-Agent", "kelevra")
            Kelevra.deviceHeaders().forEach { (n, v) -> conn.setRequestProperty(n, v) }
            conn.outputStream.close()
            val status = conn.responseCode
            val telo = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            Priglashenie.razobratOtvet(status, telo)
        } finally {
            conn.disconnect()
        }
    } catch (e: java.io.IOException) {
        KLog.i(TAG, "дверь $dver не ответила: ${Kelevra.maskCode(e.javaClass.simpleName)}")
        null
    }

    /** Старый путь: дверь знает код, если `GET /k/<код>/info` вернул 200. */
    private fun znaetKod(dver: String, kod: String): Boolean = try {
        val conn = URL(Kelevra.adresSeti("https://$dver/k/$kod/info")).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "kelevra")
            Kelevra.deviceHeaders().forEach { (n, v) -> conn.setRequestProperty(n, v) }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    } catch (e: java.io.IOException) {
        false
    }

    /**
     * Ищет дверь, знающую код, и активирует устройство. Перебирает варианты записи кода
     * ([Priglashenie.varianty]); возвращает итог и тот вариант, на котором он получен.
     */
    suspend fun voyti(vvod: String): Pair<Priglashenie.Itog, String> = withContext(Dispatchers.IO) {
        val dveri = spisokDverej()
        var itog: Priglashenie.Itog = Priglashenie.Itog.NetSvyazi
        var kod = vvod.trim()
        for (variant in Priglashenie.varianty(vvod)) {
            kod = variant
            itog = Priglashenie.vybratDver(
                dveri,
                aktivaciya = { aktivirovat(it, variant) },
                znaetKod = { znaetKod(it, variant) },
            )
            if (itog !is Priglashenie.Itog.NeNajden) break
        }
        itog to kod
    }
}
