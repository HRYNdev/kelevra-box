package io.nekohasekai.sfa

import android.content.Context
import io.nekohasekai.sfa.bg.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * `POST /k/<код>/ustroystvo`; null, если дверь не ответила. Сроки — [Priglashenie.SROK_SOEDINENIYA_MS]
     * на соединение (TCP и TLS) и [Priglashenie.SROK_OTVETA_MS] на весь запрос, не больше [srokMs]:
     * сторож обрывает соединение, если дверь не соединилась или не ответила вовремя. Отмена корутины
     * тоже обрывает соединение.
     */
    private suspend fun aktivirovat(dver: String, kod: String, srokMs: Long): Priglashenie.Otvet? = try {
        coroutineScope {
            val conn = URL(Kelevra.adresSeti("https://$dver/k/$kod/ustroystvo")).openConnection() as HttpURLConnection
            val srokVsego = minOf(Priglashenie.SROK_OTVETA_MS, srokMs)
            val srokSoedineniya = minOf(Priglashenie.SROK_SOEDINENIYA_MS, srokVsego)
            val soedineno = AtomicBoolean(false)
            val storozh = launch {
                delay(srokSoedineniya)
                if (soedineno.get()) delay(srokVsego - srokSoedineniya)
                conn.disconnect()
            }
            val zapros = async(Dispatchers.IO) {
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = srokSoedineniya.toInt()
                    conn.readTimeout = srokVsego.toInt()
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.setRequestProperty("User-Agent", "kelevra")
                    Kelevra.deviceHeaders().forEach { (n, v) -> conn.setRequestProperty(n, v) }
                    conn.connect()
                    soedineno.set(true)
                    conn.outputStream.close()
                    val status = conn.responseCode
                    val telo = (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    Priglashenie.razobratOtvet(status, telo)
                } finally {
                    conn.disconnect()
                }
            }
            try {
                zapros.await()
            } catch (e: CancellationException) {
                conn.disconnect()
                throw e
            } finally {
                storozh.cancel()
            }
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
                aktivaciya = { dver, srokMs -> aktivirovat(dver, variant, srokMs) },
                znaetKod = { znaetKod(it, variant) },
            )
            if (itog !is Priglashenie.Itog.NeNajden) break
        }
        itog to kod
    }
}
