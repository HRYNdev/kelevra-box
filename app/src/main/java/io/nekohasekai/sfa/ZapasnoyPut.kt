package io.nekohasekai.sfa

import io.nekohasekai.sfa.bg.KLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Запасной путь к своим серверам: профиль и сводка, когда прямой путь рвётся.
 *
 * Повод. С мобильной сети за NAT оператора прямой запрос к одной двери раз за разом кончался
 * `connection reset` / `EOF` (22.10.2026: 17 неудачных обновлений профиля за двое суток у одного
 * телефона), хотя туннель мог быть поднят, а вторая дверь — доступна. Порядок, как у Desktop
 * (там запасной путь через туннель при отказе прямого): сначала прямо, при сбое сети — через
 * локальный вход туннеля, затем то же по остальным дверям из справочника.
 *
 * Сбой сети — только обрыв, EOF, таймаут, отказ соединения. Ответ сервера (4xx/5xx), мусор в теле
 * и прочее запасных путей не ищут: другая дверь скажет то же самое.
 */
object ZapasnoyPut {
    private const val TAG = "KelevraZapasnoy"

    private fun zhurnal(tekst: String) {
        runCatching { KLog.i(TAG, tekst) }
    }

    /** Локальный вход туннеля (`platform.http_proxy` из боевого конфига). */
    const val LOKALNYJ_PORT = 2412

    /** Двери на крайний случай, если ни справочник, ни прошлые /info ещё не приезжали. */
    internal val VSTROENNYE = listOf(SubscriptionLink.DEFAULT_HOST, "node2kv.chickenkiller.com")

    private val SBOY_TEKSTY = listOf(
        "connection reset", "reset by peer", "eof", "unexpected end of stream", "timeout", "timed out",
        "connection refused", "broken pipe", "network is unreachable", "no route to host", "connection closed",
        "deadline exceeded", "tls: ", "handshake",
    )

    /** Обрыв сети, при котором стоит пробовать другой путь. Классификация по тексту: ядро Go отдаёт строку. */
    internal fun sbojSeti(e: Throwable): Boolean {
        var t: Throwable? = e
        var hop = 0
        while (t != null && hop < 6) {
            if (t is java.net.SocketTimeoutException || t is java.io.EOFException || t is java.net.ConnectException ||
                t is java.net.SocketException || t is java.net.UnknownHostException
            ) {
                return true
            }
            val m = t.message?.lowercase().orEmpty()
            if (SBOY_TEKSTY.any { m.contains(it) }) return true
            t = t.cause
            hop++
        }
        return false
    }

    /** Адрес `url` на другой двери; не наш адрес или та же дверь — null. */
    internal fun nadDver(url: String, dver: String): String? {
        if (!SubscriptionLink.isOwn(url)) return null
        val tekushchaya = Regex("^https://([^/]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        if (tekushchaya == dver.lowercase()) return null
        return SubscriptionLink.withBase(url, "https://$dver")
    }

    /** Двери без повторов, кроме той, что в адресе: она уже пробована. */
    internal fun drugieDveri(url: String, dveri: List<String>): List<String> =
        dveri.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().filter { nadDver(url, it) != null }

    /** Слушает ли локальный вход туннеля прямо сейчас. */
    fun tunnelPodnyat(port: Int = LOKALNYJ_PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Выполнить запрос с запасными путями.
     *
     * @param pryamoj запрос напрямую; бросает исключение при неудаче
     * @param cherezTunnel тот же запрос через локальный вход туннеля; null — туннеля нет
     * @param dveri другие двери сети
     * Несетевое исключение (ответ сервера и т.п.) пробрасывается сразу. Если не получилось нигде,
     * бросается ПЕРВАЯ ошибка: она про настоящий адрес профиля.
     */
    fun <T> poluchit(
        url: String,
        dveri: List<String>,
        cherezTunnel: ((String) -> T)?,
        pryamoj: (String) -> T,
    ): T {
        val pervaya: Throwable = try {
            return pryamoj(url)
        } catch (e: Exception) {
            if (!sbojSeti(e)) throw e
            e
        }
        zhurnal("прямой путь не прошёл (${pervaya.javaClass.simpleName}) — пробую запасные")
        if (cherezTunnel != null) {
            try {
                return cherezTunnel(url).also { zhurnal("получено через туннель") }
            } catch (e: Exception) {
                if (!sbojSeti(e)) throw e
            }
        }
        for (dver in drugieDveri(url, dveri)) {
            val novyj = nadDver(url, dver) ?: continue
            try {
                return pryamoj(novyj).also { zhurnal("получено через дверь $dver") }
            } catch (e: Exception) {
                if (!sbojSeti(e)) throw e
            }
            if (cherezTunnel != null) {
                try {
                    return cherezTunnel(novyj).also { zhurnal("получено через туннель, дверь $dver") }
                } catch (e: Exception) {
                    if (!sbojSeti(e)) throw e
                }
            }
        }
        throw pervaya
    }

    /** Заголовки устройства для своего адреса. */
    internal fun zagolovki(url: String): List<Pair<String, String>> {
        if (!Kelevra.isOwnUrl(url)) return emptyList()
        return listOf(
            "x-hwid" to Kelevra.deviceId,
            "x-device-os" to "Android",
            "x-ver-os" to (android.os.Build.VERSION.RELEASE ?: ""),
            "x-device-model" to (android.os.Build.MODEL ?: ""),
        ) + Kelevra.deviceHeaders()
    }

    /** Ответ через локальный вход туннеля: код, тело, ETag. Сбой сети — исключение. */
    fun otvetCherezTunnel(url: String, dop: List<Pair<String, String>> = emptyList(), port: Int = LOKALNYJ_PORT): Triple<Int, String, String?> {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "kelevra")
            zagolovki(url).forEach { (n, v) -> conn.setRequestProperty(n, v) }
            dop.forEach { (n, v) -> conn.setRequestProperty(n, v) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            return Triple(code, text, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }

    /** Тело ответа 2xx через туннель; иначе исключение (не 2xx — не сбой сети). */
    fun tekstCherezTunnel(url: String): String {
        val (code, text, _) = otvetCherezTunnel(url)
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return text
    }

    /** Ответ служебного запроса через туннель: код, тело, Retry-After. */
    class OtvetTunnela(val kod: Int, val telo: String, val retryAfter: String?)

    /** Наш ли это сервер по хосту в адресе (двери из справочника и встроенные). Чужой адрес в туннель не шлём. */
    internal fun svoyHost(url: String, dveri: List<String> = dveriDlyaPerebora()): Boolean {
        val host = Regex("^https?://([^/:]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return dveri.any { it.trim().lowercase() == host }
    }

    /**
     * Служебный запрос приложения (журнал, пульт) с запасным путём через туннель.
     *
     * Повод. В комнате Телемоста/olcRTC приложение выведено из tun ([io.nekohasekai.sfa.bg.TunnelFacts.selfOutsideTun])
     * и ходит по физической сети; под белым списком иностранные двери там закрыты, и запрос не доходил.
     * Порядок тот же, что у профиля: сначала прямо (вне комнаты это обычный путь), при сбое сети —
     * через локальный вход туннеля. Флаг комнаты не спрашиваем: пока прямой путь отвечает, туннель
     * не нужен, а состояние комнаты можно прочесть с запозданием.
     *
     * Через туннель идёт только трафик к НАШИМ серверам ([svoyHost]). Ядра комнаты (wb.ru, TURN,
     * Телемост) сидят в ядре, а не в этом приложении, и сюда не попадают: петли нет.
     */
    fun <T> sluzhebnyj(url: String, cherezTunnel: (String) -> T, pryamoj: (String) -> T): T =
        poluchit(url, emptyList(), if (svoyHost(url)) tunnelIliNull(cherezTunnel) else null, pryamoj)

    /** Запрос через локальный вход туннеля с телом и заголовками. Сбой сети — исключение. */
    fun zaprosCherezTunnel(
        url: String,
        metod: String = "GET",
        telo: ByteArray? = null,
        zagolovkiDop: List<Pair<String, String>> = emptyList(),
        connectMs: Int = 8000,
        readMs: Int = 15000,
        port: Int = LOKALNYJ_PORT,
    ): OtvetTunnela {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            conn.requestMethod = metod
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("User-Agent", "kelevra")
            zagolovkiDop.forEach { (n, v) -> conn.setRequestProperty(n, v) }
            if (telo != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(telo.size)
                conn.outputStream.use { it.write(telo) }
            }
            val kod = conn.responseCode
            val text = (if (kod in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            return OtvetTunnela(kod, text, conn.getHeaderField("Retry-After"))
        } finally {
            conn.disconnect()
        }
    }

    /** Двери для перебора: справочник и прошлые /info из кэша, затем встроенные. Сеть не трогает. */
    fun dveriDlyaPerebora(): List<String> =
        (runCatching { Aktivatsiya.dveriIzKesha() }.getOrDefault(emptyList()) + VSTROENNYE).distinct()

    /** Туннельный путь, если вход туннеля слушает; иначе null. */
    fun <T> tunnelIliNull(f: (String) -> T): ((String) -> T)? = if (tunnelPodnyat()) f else null

    /** Текст по адресу с запасными путями; вместо прямого `HTTPClient().getString`. */
    fun tekst(url: String): String = poluchit(
        url,
        dveriDlyaPerebora(),
        tunnelIliNull { u -> tekstCherezTunnel(u) },
    ) { u -> io.nekohasekai.sfa.utils.HTTPClient().use { it.getString(u) } }
}
