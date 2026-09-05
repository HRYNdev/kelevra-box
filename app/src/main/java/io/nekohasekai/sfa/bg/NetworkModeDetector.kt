package io.nekohasekai.sfa.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.path.ProbeSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Определитель режима сети: орган, который отвечает не «жив ли эндпоинт», а
 * **как именно сеть вокруг нас ограничена**. Модель вердикта и само решение — в
 * [NetworkMode]/[NetworkModeDecision], здесь только добыча признаков.
 *
 * ## Чистый API
 * Одна функция — [detect]. Она возвращает [NetworkModeReport]: режим, признаки,
 * на которых он стоит, и время замера. Ничего не пишет в настройки, не трогает UI,
 * не дёргает [AutoMode] и не меняет состояние канала. Единственное состояние внутри —
 * кэш последнего замера и выдержка между замерами, и оба существуют ради безопасности,
 * а не ради удобства (см. ниже).
 *
 * ## Порядок проб: от дешёвого к дорогому, с ранним выходом
 *  1. **Физическая сеть.** Не нашли — [NetworkMode.NoNetwork], ни одного пакета не послано.
 *  2. **TCP к заведомо неразрешённому адресу** (наш VPS, литералом, без DNS).
 *     Тишина → одна контрольная проба к разрешённому адресу, и вердикт
 *     [NetworkMode.Whitelist] готов. На этом пути ни одного TLS-соединения не бывает
 *     вовсе: белый список стоит два дешёвых обмена.
 *  3. **TLS-рукопожатие к канарейке** — настоящему имени, по которому и работает DPI.
 *     Рвут или подвешивают → [NetworkMode.DpiBlacklist].
 *  4. **Короткая передача по этой же сессии** (десятки килобайт) — ловит подвисание,
 *     которое DPI устраивает не сразу, а после первых килобайт.
 *
 * Шаги 3 и 4 идут по одному соединению: рукопожатие и передача переиспользуют тот же
 * сокет. Всего за замер — **не больше двух TCP-соединений**, и они последовательные.
 *
 * ## Почему пробы такие скупые
 * Два ограничения, оба из практики, оба обязательные:
 *
 *  - **Никаких параллельных TLS к одному имени.** Пачка одновременных рукопожатий
 *    к одному хосту воспроизводит триггер заморозки канала на 120 секунд. Поэтому
 *    пробы строго последовательные, соединение переиспользуется, а [lock] не даёт
 *    двум вызывающим замерять одновременно.
 *  - **Сам паттерн автопереключения различим снаружи.** Профильные разработчики
 *    возражают справедливо: прибор, который каждые N секунд ровно долбит один и тот
 *    же адрес, рисует узнаваемый след и выдаёт, что на этом конце не браузер. Отсюда
 *    выдержка [COOLDOWN_MILLIS] со случайной добавкой [COOLDOWN_JITTER_MILLIS] —
 *    интервал не должен быть ровным, — обычный User-Agent и обычный GET вместо
 *    самодельного трафика. По той же причине DNS-проба выключена по умолчанию:
 *    признак необязательный, а пакет лишний.
 *
 * ## Подключение
 * [AutoMode] спрашивает вердикт **подсказкой**, а не командой, и делает это редко:
 * только когда что-то уже не сходится (дома по DNS, а трафик не идёт; основной канал
 * провалился), и не чаще одного замера на сеть за время жизни подсказки. Вердикт
 * кэшируется вместе с сетью, на которой снят ([reportFor]): «белый список» на мобильной
 * сети ничего не говорит про домашний вайфай.
 *
 * Что подсказка меняет у автомата: [NetworkMode.Whitelist] отменяет вердикт «дома»
 * при любых признаках DNS и помечает основной канал мёртвым без траты честной пробы —
 * под белым списком узел недостижим на уровне маршрутизации, и мерить там нечего.
 * Выбор пути при этом по-прежнему делает автомат: подсказка даёт факт, а не решение.
 */
object NetworkModeDetector {

    private const val TAG = "NetworkMode"

    /** Куда стучимся. Меняется целиком, чтобы стенд мог подсунуть свои адреса. */
    data class Endpoint(
        /** Имя. Пустое — значит ходим только по литералам. */
        val host: String = "",
        val port: Int = 443,
        /**
         * Готовые адреса. Идут первыми и в обход резолвера: под белым списком DNS
         * может не работать, и провалить контрольную пробу из-за DNS значило бы
         * прозевать сам белый список.
         */
        val literals: List<String> = emptyList(),

        /**
         * Что запрашиваем в пробе передачи.
         *
         * Умолчание `/` меряет мало: живьём `www.youtube.com/` отдаёт 302 — короткий
         * редирект, честно дочитанный до конца. Для вердикта «норма» этого хватает
         * (поток закончился по-честному), а вот подвисание, которое DPI устраивает
         * «после десятков килобайт», на таком объёме не поймать никогда: проба
         * возвращает Ok, не дойдя до объёма, ради которого написана, и ветка
         * `Stalled/Reset → DpiBlacklist` по этому признаку недостижима.
         *
         * Путь подобран замером на живой мобильной сети (тот же User-Agent
         * и те же сроки): `/` — 302; `/watch`, `/results`, `/feed/trending` — те же
         * редиректы; `/robots.txt` — 1.4 КБ; `/sw.js`, `/iframe_api` — ~5 КБ;
         * `/howyoutubeworks/` — 45 КБ (всё ещё мало); **`/embed/` — 200 OK и ~128 КБ**.
         * Идентификатор ролика не нужен: голый `/embed/`, `/embed/dQw4w9WgXcQ` и
         * заведомо несуществующий `/embed/00000000000` дали 127.6-127.9 КБ, то есть
         * проба не зависит от чужого видео и не протухнет, когда ролик снесут.
         * [BULK_BYTES] набираются за 0.25-0.53 с из отпущенных [BULK_BUDGET_MILLIS],
         * и замер стал втрое короче: соединение рвётся по набранному объёму, а не
         * ждёт конца ответа.
         */
        val path: String = "/",
    )

    data class Targets(
        /**
         * Заведомо неразрешённый адрес. Наш VPS: он точно не в чьём-либо белом списке,
         * адрес постоянный, литералом — значит проба не зависит от резолвера вовсе.
         */
        val unlisted: Endpoint,

        /**
         * Заведомо разрешённый адрес — контроль. Нужен ровно для одного: отличить
         * «неразрешённое молчит» от «молчит вообще всё».
         *
         * Адреса заданы числами намеренно: если оператор режет ещё и DNS, имя не
         * отрезолвится и вместо вердикта «белый список» получится «не знаю».
         * Числа сняты со стенда белого списка (`tools/android/whitelist-on.sh`) —
         * до всех трёх соединение реально доходит при включённом фильтре.
         *
         * Осторожно с госуслугами: в белом списке лежит только `213.59.253.7`,
         * второй адрес того же имени (`213.59.254.7`) в списке отсутствует.
         * Поэтому здесь именно числа, а не «что вернул резолвер».
         */
        val allowed: Endpoint,

        /**
         * Канарейка: имя, по которому и работает DPI. Сюда идут проба рукопожатия и
         * проба передачи.
         *
         * Имя настоящее и адрес настоящий — то есть рукопожатие обязано вставать.
         * Это выяснилось живьём и стоило конструкции переделки: сначала ClientHello
         * с чужим именем слался на адрес нашего VPS, и тот честно отвечал
         * `TLSV1_ALERT_UNRECOGNIZED_NAME` — «не знаю такого имени». Признак получался
         * годный (ответ дошёл), но сессии не возникало **никогда**, а значит проба
         * передачи не запускалась ни разу и подвисание «после десятков килобайт»
         * было бы невидимо. Поэтому канарейка отдельная и настоящая.
         *
         * Само имя должно быть таким, по которому DPI работает — иначе канарейка
         * не поёт. `www.youtube.com` этому отвечает и уже используется в проекте
         * с той же целью ([ProxyProbe]).
         */
        val canary: Endpoint,

        /** Внешний резолвер для необязательного признака. */
        val externalResolver: String = "1.1.1.1",
    ) {
        companion object {
            val DEFAULT = Targets(
                // Три независимые точки, а не только свой VPS: вердикт «белый список»
                // выносится лишь когда молчат все. Проверено по списку оператора
                // (`tools/android/whitelist-data/ipwhitelist-*.txt`, снят 08.08.2026):
                // 8.8.8.8 и 9.9.9.9 в белом списке ОТСУТСТВУЮТ, то есть под фильтром
                // молчат, а в обычной сети принимают 443 (замер с телефона 12.08.2026:
                // 302 и 505 соответственно). А вот 1.1.1.1 в список оператора ВХОДИТ,
                // поэтому в качестве неразрешённой точки он не годится.
                unlisted =
                    Endpoint(
                        literals = listOf("77.239.102.44", "8.8.8.8", "9.9.9.9"),
                        port = 443,
                    ),
                allowed =
                    Endpoint(
                        host = "ya.ru",
                        literals = listOf("5.255.255.242", "87.240.132.72", "213.59.253.7"),
                        port = 443,
                    ),
                canary = Endpoint(host = "www.youtube.com", port = 443, path = "/embed/"),
            )
        }
    }

    // --------------------------------------------------------------------- сроки
    // Все короткие: замер не должен становиться заметной активностью. Таймаут сокета
    // всегда меньше общего бюджета пробы, поэтому обычно срабатывает именно он.

    /** Ждём ответа на SYN. Короткий: тишину от медленной сети отделяет именно он. */
    const val TCP_TIMEOUT_MILLIS = 2_500

    /** Ждём рукопожатия. */
    const val TLS_TIMEOUT_MILLIS = 4_000

    /** Ждём очередной порции данных. Пауза дольше этой — подвисание. */
    const val READ_TIMEOUT_MILLIS = 4_000

    /** Сколько всего смотрим на поток, даже если он исправно течёт. */
    const val BULK_BUDGET_MILLIS = 6_000L

    /**
     * Сколько байт хотим прокачать. Свидетельство про обрыв говорит о ~16 КБ,
     * поэтому берём заведомо больше — иначе подвисание случится уже после того,
     * как мы отвернулись.
     */
    const val BULK_BYTES = 64 * 1024

    /** Минимум между замерами. */
    const val COOLDOWN_MILLIS = 60_000L

    /** Случайная добавка к выдержке — чтобы интервал не был ровным. */
    const val COOLDOWN_JITTER_MILLIS = 30_000L

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // -------------------------------------------------------------------- состояние

    /** Не даёт двум вызывающим мерить одновременно: параллельные пробы под запретом. */
    private val lock = Mutex()

    @Volatile
    private var last: NetworkModeReport? = null

    /**
     * Сеть, на которой снят [last].
     *
     * Вердикт про режим — это утверждение про конкретное окружение, а не про телефон
     * вообще: «белый список» на мобильной сети ничего не говорит про домашний вайфай.
     * Поэтому вердикт хранится вместе с сетью, а спросить его можно только про неё
     * ([reportFor]).
     */
    @Volatile
    private var lastNetwork: Network? = null

    private var nextRunAt = 0L

    /** Последний известный вердикт, если он был. Без сети и без побочных действий. */
    fun lastReport(): NetworkModeReport? = last

    /**
     * Готовый вердикт про **эту** сеть, если он есть. Ничего не меряет и ничего не шлёт.
     *
     * Свежесть решает тот, кто спрашивает: у отчёта есть [NetworkModeReport.atMillis],
     * и что считать протухшим — вопрос вызывающего, а не наш.
     */
    fun reportFor(network: Network?): NetworkModeReport? {
        if (network == null) return null
        return last?.takeIf { lastNetwork == network }
    }

    /**
     * Забыть вердикт: обстановка могла смениться, и старый ответ про неё больше не говорит.
     *
     * Кэш существует против лишних проб, а не как источник истины. Пока его единственным
     * сроком годности был возраст, вердикт «белый список» переживал само ограничение:
     * снимают его внезапно, а мы продолжали считать, что вокруг запрет, и не тратили
     * даже дешёвой пробы, чтобы это заметить (замер 08.08.2026 — возврат из комнаты
     * 5 мин 18 с при живом основном канале).
     *
     * Зовётся из двух родов событий, и оба — про факты, а не про время:
     *  - **обстановка сменилась** — другая физическая сеть, система переподтвердила
     *    интернет, человек дёрнул подключение;
     *  - **вердикт опровергнут делом** — узел принял соединение, через канал прошёл
     *    запрос, наружу ушёл прямой трафик. Ни одного из этих исходов под белым списком
     *    не бывает, значит вердикт уже неверен, и ждать его старости нечего.
     *
     * Выдержка сбрасывается вместе с вердиктом: она берегла от ровного следа в **той же**
     * обстановке, а обстановки той больше нет.
     */
    fun forget(reason: String) {
        if (last == null && lastNetwork == null) return
        Log.i(TAG, "подсказку о сети забыл: $reason")
        last = null
        lastNetwork = null
        nextRunAt = 0L
    }

    /**
     * Определить режим сети.
     *
     * @param targets куда стучаться.
     * @param force мерить, не дожидаясь выдержки. Нужен для стенда и для честного
     *   перезамера после доказанной смены сети; в обычной работе — не нужен.
     * @param probeExternalDns добавить необязательный признак «отвечает ли внешний
     *   резолвер». По умолчанию выключен: в решении он не участвует (данные по
     *   операторам расходятся), а лишний пакет рисует лишний след.
     * @param network по какой сети мерить. `null` — выберем сами. Передаётся тем, кто
     *   уже нашёл физическую сеть: иначе замер и вызывающий могут говорить о разных
     *   сетях, а вердикт кэшируется именно по сети.
     */
    suspend fun detect(
        targets: Targets = Targets.DEFAULT,
        force: Boolean = false,
        probeExternalDns: Boolean = false,
        network: Network? = null,
    ): NetworkModeReport = lock.withLock {
        val chosen = network?.takeIf(::physical) ?: physicalNetwork()
        val cached = last
        // Выдержка бережёт от ровного следа в одной и той же обстановке. Другая сеть —
        // другая обстановка, и старый вердикт про неё не говорит ничего.
        if (!force && cached != null && chosen == lastNetwork && SystemClock.elapsedRealtime() < nextRunAt) {
            return@withLock cached
        }
        val report = withContext(Dispatchers.IO) { measure(targets, probeExternalDns, chosen) }
        last = report
        lastNetwork = chosen
        nextRunAt = SystemClock.elapsedRealtime() +
            COOLDOWN_MILLIS +
            Random.nextLong(COOLDOWN_JITTER_MILLIS)
        Log.i(TAG, "режим: ${report.mode} — ${report.note} (${report.tookMillis} мс)")
        report
    }

    // ----------------------------------------------------------------------- замер

    private fun measure(
        targets: Targets,
        probeExternalDns: Boolean,
        chosen: Network?,
    ): NetworkModeReport {
        val wall = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        var signals = NetworkSignals()
        var owned: Socket? = null
        try {
            val network = chosen ?: return report(signals, wall, started)
            signals = signals.copy(physicalNetwork = true)

            // Проба 1: TCP к неразрешённому адресу. Самая дешёвая и самая решающая.
            // Литералом, без резолвера — DNS не должен влиять на вердикт о белом списке.
            val tcp = openTcp(network, targets.unlisted, requireAllSilent = true)
            owned = tcp.socket
            signals = signals.copy(tcpUnlisted = tcp.outcome)

            if (tcp.outcome == ProbeOutcome.Silence) {
                // Тишина. Одна контрольная проба — и белый список либо доказан, либо нет.
                // Дальше не идём: TLS тут нечего проверять, а лишних пакетов не шлём.
                // На этом пути ни одного рукопожатия не бывает вовсе.
                signals = signals.copy(tcpAllowed = control(network, targets.allowed))
                // Признак про внешний резолвер снимаем и здесь. Раньше ранний выход стоял
                // выше пробы DNS, и параметр [probeExternalDns] на этом пути молча ничего
                // не делал — а путь этот ровно тот, где признак единственно и интересен:
                // именно белый список бывает у одних операторов без фильтрации DNS, а у
                // других с ней, и различить два профиля больше нечем. В решении он
                // по-прежнему не участвует.
                if (probeExternalDns) {
                    signals = signals.copy(externalDns = probeDns(network, targets.externalResolver))
                }
                return report(signals, wall, started)
            }
            // Неразрешённый адрес нам ответил — белого списка нет, и держать соединение
            // с ним больше незачем: дальше работает канарейка.
            owned.closeQuietly()
            owned = null
            if (tcp.outcome != ProbeOutcome.Ok) return report(signals, wall, started)

            // Проба 2: TCP к канарейке, и сразу TLS поверх ТОГО ЖЕ сокета —
            // второго соединения к тому же имени не открываем.
            val canary = openTcp(network, targets.canary)
            owned = canary.socket
            val plain = canary.socket ?: return report(signals, wall, started)

            val tls = startTls(plain, targets.canary.host, targets.canary.port)
            tls.socket?.let { owned = it }
            signals = signals.copy(tlsCanary = tls.outcome)

            // Проба 3: короткая передача по этой же сессии.
            val session = tls.socket
            if (tls.outcome == ProbeOutcome.Ok && session != null) {
                val bulk = readBulk(session, targets.canary.host, targets.canary.path)
                signals = signals.copy(bulkCanary = bulk.outcome, bulkBytes = bulk.bytes)
            }

            if (probeExternalDns) {
                signals = signals.copy(externalDns = probeDns(network, targets.externalResolver))
            }
            return report(signals, wall, started)
        } catch (error: Throwable) {
            // Сорвавшийся замер — это «не знаю», а не повод уронить вызывающего.
            Log.w(TAG, "замер сорвался: ${error.javaClass.simpleName}: ${error.message}")
            return report(signals, wall, started)
        } finally {
            owned.closeQuietly()
        }
    }

    private fun report(signals: NetworkSignals, wall: Long, started: Long): NetworkModeReport {
        val mode = NetworkModeDecision.decide(signals)
        return NetworkModeReport(
            mode = mode,
            signals = signals,
            atMillis = wall,
            tookMillis = SystemClock.elapsedRealtime() - started,
            note = NetworkModeDecision.explain(mode, signals),
        )
    }

    /** Контрольная проба: открыли, посмотрели, сразу закрыли. */
    private fun control(network: Network, endpoint: Endpoint): ProbeOutcome {
        val opened = openTcp(network, endpoint)
        opened.socket.closeQuietly()
        return opened.outcome
    }

    // ------------------------------------------------------------------- физическая сеть

    /**
     * Сеть, через которую мерить.
     *
     * Здесь решается главный вопрос честности замера: **мерить надо не себя**. Если
     * пустить пробы обычным сокетом, пока поднят наш собственный туннель, они уйдут
     * в него, и прибор покажет состояние туннеля, а не обстановки вокруг. Поэтому:
     *
     *  - берём конкретный [Network], у которого нет транспорта `TRANSPORT_VPN`;
     *  - каждый сокет защищаем от нашего же tun и только потом привязываем к этой сети
     *    ([ProbeSocket]). Одной привязки мало, и это стоило боевого бага: правило per-uid
     *    для VPN стоит выше неё, поэтому сокет, созданный через `network.socketFactory`,
     *    всё равно уходил в наш туннель — прибор мерил туннель и мог объявить белый
     *    список там, где его нет. А вердикт «белый список» отменяет дом при любых
     *    признаках, то есть телефон запирался в туннеле по второму кругу;
     *  - имена резолвим через `network.getAllByName`: системный резолвер сети ходит
     *    мимо туннеля и без защиты;
     *  - физической сети не нашлось — возвращаем [NetworkMode.NoNetwork] и **не шлём
     *    ничего**. Отката на обычный сокет нет намеренно: лучше не измерить, чем
     *    измерить свой же туннель и выдать это за обстановку.
     */
    private fun physicalNetwork(): Network? {
        DefaultNetworkMonitor.defaultNetwork?.takeIf(::physical)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { Application.connectivity.activeNetwork }.getOrNull()
                ?.takeIf(::physical)
                ?.let { return it }
        }
        return anyPhysical()
    }

    @Suppress("DEPRECATION")
    private fun anyPhysical(): Network? {
        val manager = Application.connectivity
        val networks = runCatching { manager.allNetworks }.getOrElse { return null }
        var fallback: Network? = null
        for (network in networks) {
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network
            if (fallback == null) fallback = network
        }
        return fallback
    }

    /** Годится ли сеть: не наш туннель и вообще про интернет. */
    private fun physical(network: Network): Boolean {
        val caps = runCatching { Application.connectivity.getNetworkCapabilities(network) }.getOrNull()
            ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------------- пробы

    private class Opened(val outcome: ProbeOutcome, val socket: Socket?)

    private fun openTcp(
        network: Network,
        endpoint: Endpoint,
        /**
         * Требовать, чтобы молчали ВСЕ адреса, прежде чем считать тишину признаком.
         *
         * Для контроля и канарейки достаточно первого молчания: там тишина и есть
         * искомое. Для неразрешённого адреса это неверно: одна молчащая точка не
         * отличает «в сети фильтр по адресам» от «именно эта точка недоступна». Пока
         * точка была одна, и та наш собственный VPS, любое точечное придушивание
         * нашего узла читалось как белый список, и телефон уходил в комнату, погасив
         * рабочий канал.
         */
        requireAllSilent: Boolean = false,
    ): Opened {
        val addresses = resolve(network, endpoint, if (requireAllSilent) MAX_UNLISTED_ADDRESSES else MAX_ADDRESSES)
        if (addresses.isEmpty()) return Opened(ProbeOutcome.Failed, null)
        var outcome = ProbeOutcome.Failed
        var silent = 0
        for (address in addresses) {
            // Защита от своего tun ставится внутри и до привязки к сети: соединять
            // защищённый сокет можно, защищать соединённый — уже нет.
            val socket = runCatching { ProbeSocket.open { network.bindSocket(it) } }
                .getOrElse { return Opened(ProbeOutcome.Failed, null) }
            try {
                socket.connect(InetSocketAddress(address, endpoint.port), TCP_TIMEOUT_MILLIS)
                socket.soTimeout = READ_TIMEOUT_MILLIS
                return Opened(ProbeOutcome.Ok, socket)
            } catch (error: Throwable) {
                socket.closeQuietly()
                outcome = ProbeFailure.onConnect(error)
                // Тишина — уже готовый признак. Перебирать остальные адреса значит
                // слать лишние SYN туда, где нам молчат.
                if (outcome == ProbeOutcome.Silence) {
                    if (!requireAllSilent) return Opened(outcome, null)
                    silent++
                }
            }
        }
        // Молчали не все — значит это не сеть с фильтром по адресам, а отдельная точка.
        // Отдаём наверх последний не-тихий исход, чтобы вердикт не встал на тишине.
        if (requireAllSilent && silent in 1 until addresses.size) {
            Log.i(TAG, "неразрешённые адреса: молчат $silent из ${addresses.size} — это не белый список")
            return Opened(ProbeOutcome.Unreachable, null)
        }
        return Opened(outcome, null)
    }

    /** Литералы — первыми и без резолвера. Имя — только если литералов нет. */
    private fun resolve(network: Network, endpoint: Endpoint, limit: Int = MAX_ADDRESSES): List<InetAddress> {
        val literals = endpoint.literals.mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        if (literals.isNotEmpty()) return literals.take(limit)
        if (endpoint.host.isBlank()) return emptyList()
        return runCatching { network.getAllByName(endpoint.host).toList() }
            .getOrElse { emptyList() }
            .take(limit)
    }

    private const val MAX_ADDRESSES = 2

    /** Неразрешённых точек три: вердикт о белом списке не должен зависеть от одной. */
    private const val MAX_UNLISTED_ADDRESSES = 3

    private class Handshake(val outcome: ProbeOutcome, val socket: SSLSocket?)

    /**
     * TLS поверх уже открытого сокета. Имя передаётся третьим аргументом — Android
     * кладёт его в SNI, а адрес при этом остаётся тем, к которому мы уже подключились.
     * Ровно это нам и нужно: разрешённый адрес с неразрешённым именем.
     *
     * Сокет возвращается и при неудаче: им владеет вызывающий, он же закрывает.
     *
     * Своей защиты от нашего tun здесь не нужно: TLS садится поверх уже открытого
     * сокета, то есть на тот же дескриптор, а он защищён и привязан к сети ещё в
     * [openTcp].
     */
    private fun startTls(plain: Socket, sni: String, port: Int): Handshake {
        val ssl = runCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(plain, sni, port, true) as SSLSocket
        }.getOrElse { return Handshake(ProbeOutcome.Failed, null) }
        return try {
            ssl.soTimeout = TLS_TIMEOUT_MILLIS
            ssl.startHandshake()
            ssl.soTimeout = READ_TIMEOUT_MILLIS
            Handshake(ProbeOutcome.Ok, ssl)
        } catch (error: Throwable) {
            Handshake(ProbeFailure.onHandshake(error).also { trace("рукопожатие", it, error) }, ssl)
        }
    }

    private class Bulk(val outcome: ProbeOutcome, val bytes: Int)

    /**
     * Короткая передача. Обычный GET обычным User-Agent — не потому что нам нужен
     * ответ, а потому что самодельный трафик виден лучше обычного.
     */
    private fun readBulk(ssl: SSLSocket, host: String, path: String): Bulk {
        var read = 0
        var finished = false
        return try {
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: $USER_AGENT\r\n" +
                "Accept: */*\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n\r\n"
            ssl.outputStream.apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val stream: InputStream = ssl.inputStream
            val buffer = ByteArray(16 * 1024)
            val deadline = SystemClock.elapsedRealtime() + BULK_BUDGET_MILLIS
            while (read < BULK_BYTES) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    // Бюджет вышел, но данные шли — это не подвисание, а просто конец
                    // наблюдения. Подвисание приходит таймаутом чтения, не отсюда.
                    finished = true
                    break
                }
                val count = stream.read(buffer)
                if (count < 0) {
                    finished = true
                    break
                }
                read += count
            }
            Bulk(ProbeFailure.onTransfer(read, BULK_BYTES, finished, null), read)
        } catch (error: Throwable) {
            Bulk(ProbeFailure.onTransfer(read, BULK_BYTES, finished, error), read)
        }
    }

    // -------------------------------------------------------------------- DNS (признак)

    /**
     * Отвечает ли внешний резолвер. В решении НЕ участвует: по операторам данные
     * расходятся, и строить на DNS вердикт нельзя. Возвращается отдельным признаком.
     */
    private fun probeDns(network: Network, resolver: String): ProbeOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return ProbeOutcome.Skipped
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(resolver)
            val query = dnsQuery("example.com")
            // Датаграммам правило per-uid для VPN мешает так же, как и TCP: без защиты
            // запрос ушёл бы в наш туннель, и «отвечает ли внешний резолвер» было бы
            // ответом про туннель.
            socket = ProbeSocket.openDatagram { network.bindSocket(it) }
            socket.soTimeout = TCP_TIMEOUT_MILLIS
            socket.send(DatagramPacket(query, query.size, address, 53))
            val answer = ByteArray(512)
            socket.receive(DatagramPacket(answer, answer.size))
            ProbeOutcome.Ok
        } catch (error: Throwable) {
            ProbeFailure.onConnect(error)
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Минимальный DNS-запрос A-записи. */
    private fun dnsQuery(name: String): ByteArray {
        val body = ArrayList<Byte>(32)
        val id = Random.nextInt(0, 0xFFFF)
        body.add((id shr 8).toByte())
        body.add(id.toByte())
        // flags: recursion desired
        body.addAll(listOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })
        for (label in name.split('.')) {
            body.add(label.length.toByte())
            label.forEach { body.add(it.code.toByte()) }
        }
        body.add(0)
        // QTYPE=A, QCLASS=IN
        body.addAll(listOf(0x00, 0x01, 0x00, 0x01).map { it.toByte() })
        return body.toByteArray()
    }

    private fun Socket?.closeQuietly() {
        runCatching { this?.close() }
    }

    /**
     * Что именно прилетело и во что мы это засчитали.
     *
     * Не роскошь: признак [ProbeOutcome.Failed] означает «ошибка ничего не доказывает»,
     * и без этой строки нельзя понять, действительно ли она ничего не доказывает или
     * это разбор ошибок чего-то не знает. Уровень debug — в обычной работе молчит.
     */
    private fun trace(stage: String, outcome: ProbeOutcome, error: Throwable) {
        Log.d(
            TAG,
            "$stage → $outcome: ${error.javaClass.name}: ${error.message} " +
                "(причина: ${error.cause?.javaClass?.name}: ${error.cause?.message})",
        )
    }
}
