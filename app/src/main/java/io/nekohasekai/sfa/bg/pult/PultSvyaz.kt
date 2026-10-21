package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.bg.AutoMode
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.compose.screen.home.SubscriptionRefresh
import io.nekohasekai.sfa.compose.screen.home.SvodkaDisk
import io.nekohasekai.sfa.compose.screen.home.bazaPodpiski
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Связь с сервером, пока служба жива (PULT.md, «Связь с телефоном»):
 *  - отметки испытания пульта раз в [SHAG_MS];
 *  - опрос /info раз в `opros.info_min` прямо из службы, с `If-None-Match` (304 — дёшево);
 *  - долгий опрос `…/pult/zhdat?versiya=N` при `kanal.dolgiy_opros` (по умолчанию выключен).
 *
 * Без сети не ходит никуда; ошибки долгого опроса разводят попытки всё шире.
 */
object PultSvyaz {
    private const val TAG = "KelevraPult"
    private const val SHAG_MS = 30_000L

    /** Долгий опрос: сервер держит до 55 с, ждём с запасом. */
    private const val ZHDAT_TIMEOUT_MS = 70_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var dolgiy: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Pult.zagruzitEsliNado()
            petlya()
        }
        dolgiy = scope.launch { dolgiyOpros() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        dolgiy?.cancel()
        job = null
        dolgiy = null
    }

    private fun setEst(): Boolean = DefaultNetworkMonitor.defaultNetwork != null

    /** Пора ли опрашивать /info: с прошлого опроса прошло `opros.info_min`. */
    internal fun poraOprosa(nowMs: Long, proshlyyMs: Long, intervalMin: Int): Boolean =
        nowMs - proshlyyMs >= intervalMin * 60_000L

    private suspend fun petlya() {
        // Отсчёт — от последней сводки на диске: подъём службы не повод сразу дёргать сервер,
        // это и так делают экран и расписание профиля.
        var proshlyy = runCatching { SvodkaDisk.prochest()?.kogdaMs }.getOrNull() ?: 0L
        while (currentCoroutineContextActive()) {
            runCatching {
                Pult.otmetit(nablyudaem = setEst(), zhivoy = AutoMode.state.value.link == AutoMode.Link.Alive)
            }
            val now = System.currentTimeMillis()
            if (!Pult.vykl("opros_sluzhby") && setEst() && poraOprosa(now, proshlyy, Pult.int("opros.info_min"))) {
                proshlyy = now
                runCatching { SubscriptionRefresh.loadInfo(uslovno = true) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            delay(SHAG_MS)
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    /** Пауза после неудачи долгого опроса: 30 с, удваивается до 15 мин. */
    internal fun pauza(neudach: Int): Long = (30_000L shl (neudach - 1).coerceIn(0, 5)).coerceAtMost(15 * 60_000L)

    private suspend fun dolgiyOpros() {
        var neudach = 0
        while (currentCoroutineContextActive()) {
            if (!Pult.bool("kanal.dolgiy_opros") || !setEst()) {
                neudach = 0
                delay(60_000L)
                continue
            }
            val baza = bazaPodpiski()
            if (baza == null) {
                delay(5 * 60_000L)
                continue
            }
            val (kod, retryAfter) = runCatching { zhdat(baza, Pult.sostoyanie.versiya) }.getOrElse {
                if (it is CancellationException) throw it
                -1 to null
            }
            when (val shag = shagPoKodu(kod, retryAfter, neudach)) {
                is Shag.Info -> {
                    neudach = 0
                    KLog.i(TAG, "долгий опрос: у сервера новая версия пульта — читаю сводку")
                    runCatching { SubscriptionRefresh.loadInfo() }.onFailure { if (it is CancellationException) throw it }
                    delay(shag.pauzaMs)
                }
                is Shag.Snova -> {
                    neudach = 0
                    delay(shag.pauzaMs)
                }
                is Shag.Pauza -> {
                    if (shag.neudacha) neudach++ else neudach = 0
                    if (!shag.neudacha || neudach == 1 || neudach % 5 == 0) {
                        KLog.i(TAG, "долгий опрос: код $kod — пауза ${shag.pauzaMs / 1000} с")
                    }
                    delay(shag.pauzaMs)
                }
            }
        }
    }

    /** Что делать после захода долгого опроса. */
    internal sealed class Shag {
        abstract val pauzaMs: Long

        /** 200 — у сервера версия выше нашей: сразу /info. */
        data class Info(override val pauzaMs: Long = 1_000L) : Shag()

        /** 204 — подержал и отпустил без новостей: сразу следующий заход. */
        data class Snova(override val pauzaMs: Long = 1_000L) : Shag()

        /** Пауза; [neudacha] — растить счётчик неудач (сеть, 5xx). */
        data class Pauza(override val pauzaMs: Long, val neudacha: Boolean) : Shag()
    }

    /**
     * Коды выдачи (KelevraServer docs/PULT.md, «Провод»): 200 — есть новая версия; 204 — срок вышел;
     * 403 — мозг этому устройству долгий опрос не включил (наш пульт ещё не знает) — 10 мин;
     * 429 — занято, ждать Retry-After (нет — 60 с); 404/405 — выдача не знает устройство или опрос — 30 мин;
     * прочее и обрыв — растущая пауза [pauza].
     */
    internal fun shagPoKodu(kod: Int, retryAfterS: Long?, neudach: Int): Shag = when (kod) {
        200 -> Shag.Info()
        204, 304 -> Shag.Snova()
        403 -> Shag.Pauza(10 * 60_000L, neudacha = false)
        429 -> Shag.Pauza(((retryAfterS ?: 60L).coerceIn(1L, 3600L)) * 1000L, neudacha = false)
        404, 405 -> Shag.Pauza(30 * 60_000L, neudacha = false)
        else -> Shag.Pauza(pauza(neudach + 1), neudacha = true)
    }

    /** Один заход долгого опроса: код ответа (-1 — до сервера не дошли) и Retry-After в секундах. */
    private fun zhdat(baza: String, versiya: Long): Pair<Int, Long?> {
        val conn = URL(Kelevra.adresSeti("$baza/pult/zhdat?versiya=$versiya")).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = ZHDAT_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "kelevra")
            Kelevra.deviceHeaders().forEach { (n, v) -> conn.setRequestProperty(n, v) }
            val kod = conn.responseCode
            runCatching { (if (kod in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            kod to conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        } finally {
            conn.disconnect()
        }
    }
}
