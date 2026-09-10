package io.nekohasekai.sfa.bg

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Соответствие «адрес → имя сайта», взятое у самого ядра.
 *
 * Зачем. В строке отказа ядро печатает АДРЕС: «open connection to [2a02:6b8:a::a]:443
 * … network is unreachable». Имя оно знает (определение домена включено первым правилом
 * профиля), но в эту строку не кладёт. Из-за этого ось «сайт» в журнале телефона
 * отсутствует, и диагноз 10.09.2026 приходилось ставить обратным запросом руками: сначала
 * выяснил, что 2a02:6b8:a::a — это yandex.ru, и только потом стало ясно, что
 * именно ломается. Разбор стоил половины сессии.
 *
 * Откуда берём. У ядра есть служебный порт (`experimental.clash_api` в профиле, по
 * умолчанию 127.0.0.1:9090), и там список живых соединений с именем в metadata. На
 * компьютере мы его читаем с самого начала, а на телефоне — нет, хотя ядро одно и то же.
 *
 * Почему кэшем, а не запросом на каждый отказ. Отказов бывает по три сотни за семь
 * минут (настоящий случай), и триста запросов к ядру ради строчки в журнале — цена
 * несоразмерная. Соединение живёт секунды, но отказ случается сразу после его
 * появления, поэтому короткого кэша хватает.
 */
object ImenaSaytov {
    private const val TAG = "KelevraImena"

    /** Служебный порт ядра. Тот же, что стоит в профиле по умолчанию. */
    private const val ADRES = "http://127.0.0.1:9090/connections"

    /** Сколько имён держим. Больше не нужно: спрашивают про свежие отказы. */
    private const val POTOLOK = 256

    /** Реже раза в пять секунд ядро не тревожим. */
    private const val NE_CHASHCHE_MS = 5_000L

    private val kesh = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > POTOLOK
    }

    @Volatile
    private var sprashivali = 0L

    /**
     * Имя сайта по адресу, если ядро его называло. Пусто — значит не знаем, и врать
     * не будем: в журнал уйдёт один адрес, как раньше.
     */
    @Synchronized
    fun imya(adres: String): String {
        if (adres.isEmpty()) return ""
        kesh[adres]?.let { return it }
        obnovit()
        return kesh[adres] ?: ""
    }

    /** Забрать у ядра список соединений и разложить по адресам. */
    @Synchronized
    private fun obnovit() {
        val teper = System.currentTimeMillis()
        if (teper - sprashivali < NE_CHASHCHE_MS) return
        sprashivali = teper
        val telo = runCatching { prochitat() }.getOrNull() ?: return
        runCatching { razobrat(telo) }.onFailure {
            Log.w(TAG, "список соединений ядра не разобрался: ${it.message}")
        }
    }

    private fun prochitat(): String {
        val conn = URL(ADRES).openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode !in 200..299) return ""
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Разбор ответа ядра. Держим оба вида адреса: тот, куда пошли на самом деле
     * (`destinationIP`), и подменный (`destinationIP` при fakeip совпадает с ним же), —
     * отказ печатается по первому.
     */
    internal fun razobrat(telo: String) {
        if (telo.isBlank()) return
        val svyazi = JSONObject(telo).optJSONArray("connections") ?: return
        for (i in 0 until svyazi.length()) {
            val meta = svyazi.optJSONObject(i)?.optJSONObject("metadata") ?: continue
            val imya = meta.optString("host").trim()
            if (imya.isEmpty() || imya == "null") continue
            for (klyuch in listOf("destinationIP", "remoteDestination")) {
                val adres = meta.optString(klyuch).trim()
                if (adres.isNotEmpty() && adres != "null") kesh[adres] = imya
            }
        }
    }

    /** Для проверок: сколько имён сейчас помним. */
    internal fun skolkoPomnim(): Int = kesh.size

    @Synchronized
    internal fun zabyt() {
        kesh.clear()
        sprashivali = 0L
    }
}
