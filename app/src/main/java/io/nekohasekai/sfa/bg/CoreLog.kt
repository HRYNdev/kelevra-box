package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Запись журнала ЯДРА в файл, рядом с журналом приложения.
 *
 * Зачем. Ядро не пишет в logcat вообще: свои строки оно отдаёт по отдельному каналу,
 * который до сих пор читал только экран «Журнал», пока он открыт. Из-за этого в суточном
 * архиве были решения авто-режима и старты сервиса, но не было ни строки о том, что
 * ядро сделало с конкретным соединением. 05.09.2026 жалобу «два сайта не открываются»
 * удалось закрыть только потому, что экран журнала открыли руками и увидели там
 * `dial rmnet_data0: connect: network is unreachable` — ответ, которого в архиве не было.
 *
 * Свой файл, а не общий с [AppLog]. Ядро на нынешней подробности пишет строку на каждое
 * соединение, и в общем файле оно за сутки вытеснило бы решения авто-режима — а они
 * ровно в том же разборе доказали половину. Отдельная ротация оставляет и то, и другое.
 *
 * Отправку менять не пришлось: [LogUploadWork] забирает все файлы каталога.
 */
object CoreLog {
    private const val TAG = "KelevraCoreLog"
    const val BASE_NAME = "kelevra-core.log"

    /** Потолок: 3 × 2 МБ. Журналу приложения оставлено его прежние 14 МБ. */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 3

    /** Цвета в строках ядра нужны экрану, файлу они мешают читаться. */
    private val ansi = Regex("\\[[0-9;]*m")

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var rotator: LogRotator? = null
    private var client: CommandClient? = null
    private var scope: CoroutineScope? = null

    /**
     * Сводка за окно: сколько соединений, сколько отказов и каких.
     *
     * Зачем. Разбор 10.09.2026: журнал ядра при работе заполняется за шесть минут и
     * вытесняет собой всю историю, а жалоба приходит через часы. Строка-сводка на
     * каждые десять минут весит копейки и переживает любую ротацию, поэтому «в тот
     * час у человека падало каждое пятое соединение» остаётся видно даже когда самих
     * строк уже нет.
     *
     * И она же служит поводом отправки: перевалило за порог — журнал уезжает сразу, не
     * дожидаясь ночи, и к моменту жалобы данные уже у меня.
     */
    private const val OKNO_SVODKI_MS = 10 * 60 * 1000L

    /** С какой доли отказов окно считается бедой и просит отправку. */
    private const val DOLYA_TREVOGI = 15

    /**
     * Короткое окно для шторма, рядом с десятиминутным.
     *
     * Десятиминутное окно ловит медленную деградацию, но шторм в него не помещается.
     * 12.09.2026 весь шторм уложился в два всплеска по десять секунд, до 1240 отказов
     * в секунду, а тревога поднялась через пять минут — уже после того, как автомат
     * сам переключился и всё починилось. Короткое окно ловит такое на первых секундах.
     */
    private const val BYSTRO_OKNO_MS = 30 * 1000L

    /** Сколько отказов в коротком окне считать штормом. Штатно их там единицы. */
    internal const val BYSTRO_POROG = 200

    /** И какая при этом доля: сотня отказов на десятках тысяч соединений — не шторм. */
    private const val BYSTRO_DOLYA = 50

    /** Реже одного раза в час по тревоге не шлём: journal и так уедет ночью. */
    private const val TREVOGA_NE_CHASHCHE_MS = 60 * 60 * 1000L

    @Volatile
    private var oknoNachalo = 0L

    @Volatile
    private var oknoSoedineniy = 0

    @Volatile
    private var oknoOtkazov = 0

    /** Из [oknoOtkazov] — отказы самого туннеля (см. [kodOtkazaTunnelya]). */
    @Volatile
    private var oknoTunnel = 0

    /** Из [oknoOtkazov] — отказы прямых соединений на голый адрес (без имени), отдельным счётом. */
    @Volatile
    private var oknoPryamoIp = 0

    private val oknoKody = LinkedHashMap<String, Int>()

    /** Имена сайтов, по которым отказывали в этом окне: ось «сайт» для разбора. */
    private val oknoImena = LinkedHashMap<String, Int>()

    @Volatile
    private var trevogaBylaV = 0L

    /** Отказы в свой же сокс на петле: см. [SocksBreaker]. */
    private val predohranitel = SocksBreaker()

    @Volatile
    private var bystroNachalo = 0L

    @Volatile
    private var bystroSoed = 0

    @Volatile
    private var bystroOtkazov = 0

    /** Коды причин отказа, по которым потом ставится диагноз на сервере. */
    private val PRICHINY = listOf(
        "set_nedostupna" to "network is unreachable",
        "marshruta_net" to "no route to host",
        "taymaut" to "i/o timeout",
        "imya_ne_reshilos" to "no such host",
        "otkaz_soedineniya" to "connection refused",
        "sbros" to "connection reset",
    )

    /** Адрес назначения из строки отказа: «open connection to [2a02::1]:443» или «1.2.3.4:443». */
    private val RX_ADRES_OTKAZA =
        Regex("""open connection to \[?([0-9a-fA-F:.]+)]?:\d+""")

    /** Порт назначения из той же строки. */
    private val RX_PORT = Regex("""open connection to \[?[0-9a-fA-F:.]+]?:(\d+)""")

    /** Каким выходом шли: «using outbound/direct[direct]». */
    private val RX_VYHOD = Regex("""using outbound/([a-zA-Z0-9_.\-]+)""")

    /** Выход-узел в строке отказа: «using outbound/vless[Нидерланды · XHTTP]» → (vless, Нидерланды · XHTTP). */
    private val RX_VYHOD_TEG = Regex("""using outbound/([A-Za-z0-9_-]+)\[([^\]]+)]""")

    /** Типы выходов, которые ведут на узел (не прямой выход, не комната на петле, не блок). */
    private val TIPY_UZLA = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls")

    /** Сколько отказов через узлы за [UZEL_OKNO_MS] будит автомат проверить узел. */
    internal const val UZEL_POROG = 3
    private const val UZEL_OKNO_MS = 30 * 1000L

    @Volatile
    private var uzelOknoNachalo = 0L

    @Volatile
    private var uzelOtkazov = 0

    /**
     * Отказы соединений через узел — повод проверить его сейчас, а не на плановом заходе.
     *
     * Шторм (см. [BYSTRO_POROG]) ловит сотни отказов, а телефон с лёгким трафиком при смерти узла
     * набирает их единицы: 17.10.2026 узел остановили, а автомат заметил это только на плановом заходе
     * через 4 минуты. Здесь хватает [UZEL_POROG] отказов за полминуты; решение по-прежнему за заходом
     * (молчание узла дважды или провал пробы), побудка стоит одну пробу.
     */
    /** Выход-узел, через который отказало соединение; null — отказ не через узел (прямой, комната). */
    internal fun vyhodUzla(line: String): String? {
        val m = RX_VYHOD_TEG.find(line) ?: return null
        return m.groupValues[2].takeIf { m.groupValues[1].lowercase(Locale.US) in TIPY_UZLA }
    }

    internal fun otkazUzla(line: String, teper: Long) {
        val teg = vyhodUzla(line) ?: return
        if (teper - uzelOknoNachalo >= UZEL_OKNO_MS) {
            uzelOknoNachalo = teper
            uzelOtkazov = 0
        }
        uzelOtkazov++
        if (uzelOtkazov == UZEL_POROG) {
            thread(name = "otkazy-uzla", isDaemon = true) { AutoMode.otkazyUzla(teg) }
        }
    }

    /**
     * Отказ самого туннеля — код причины; null, если строка не про это.
     *
     * Повод. До 20.10.2026 отказом считалась только строка `open connection to`, то есть
     * отказ ПРЯМОГО соединения: туннель так не ломается. У телефона с мёртвым туннелем
     * сводка писала «соединений 475, отказов 0», а журнал ядра тем временем был забит
     * `connection upload handshake: io: read/write on closed pipe` — так выглядит гибель
     * выхода-узла изнутри ядра. Считаем по выходам туннеля:
     * - `upload handshake` — соединение через узел не прошло рукопожатие;
     * - `report handshake success` — то же на стороне входа;
     * - `listen packet connection` через узел (не через прямой выход) — UDP через узел;
     * - `urltest[…] … unavailable` — проверка выходов в ядре сама назвала узел недоступным.
     *
     * `download closed: http2: response body closed` — штатное закрытие потока, не отказ.
     */
    internal fun kodOtkazaTunnelya(line: String): String? {
        if (line.contains("download closed")) return null
        val error = line.contains("ERROR")
        return when {
            error && line.contains("upload handshake") -> "tunnel_upload"
            error && line.contains("report handshake success") -> "tunnel_rukopozhatie"
            error && line.contains("listen packet connection") && !line.contains("outbound/direct[") -> "tunnel_pakety"
            line.contains("outbound/urltest[") && line.contains(" unavailable") -> "urltest_nedostupen"
            else -> null
        }
    }

    /** Голый адрес, а не имя: IPv4 или IPv6. */
    internal fun golyyAdres(adres: String): Boolean =
        adres.isNotEmpty() && (adres.contains(':') || RX_IPV4.matches(adres))

    private val RX_IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Уровень строки ядра по её началу («DEBUG[1067] …»): 0 паника … 6 трассировка; null — не узнан. */
    internal fun urovenStroki(line: String): Int? {
        val skobka = line.indexOf('[')
        if (skobka <= 0 || skobka > 6) return null
        return when (line.substring(0, skobka)) {
            "PANIC" -> 0
            "FATAL" -> 1
            "ERROR" -> 2
            "WARN" -> 3
            "INFO" -> 4
            "DEBUG" -> 5
            "TRACE" -> 6
            else -> null
        }
    }

    private const val UROVEN_INFO = 4
    private const val UROVEN_DEBUG = 5

    /** DEBUG-строки, которые разбирает сервер (netmon `razbor_zhurnalov.py`): решение маршрута. */
    private val RX_DEBUG_MARSHRUT = Regex("""router: match\[\d+].*?=> (?:route\(|direct)""")

    /** Запрос имени: номер запроса и сама строка нужны, только если по нему придёт отказ набором. */
    private val RX_DNS_ZAPROS = Regex("""\[(\d+) [\d.]+m?s] dns: exchange [a-zA-Z0-9_.\-]+\. IN (?:A|AAAA|HTTPS)""")

    /** Отказ имени набором правил (реклама): «[id …] dns: match[N] rule_set=ads => reject». */
    private val RX_DNS_OTKAZ = Regex("""\[(\d+) [\d.]+m?s] dns: match\[\d+] rule_set=[a-z0-9_\-]+ => reject""")

    /** Сколько запросов имени помним в ожидании отказа. Отказ идёт следом за запросом. */
    private const val DNS_ZAPROSY_MAX = 256

    private val dnsZaprosy = object : LinkedHashMap<String, String>(DNS_ZAPROSY_MAX, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > DNS_ZAPROSY_MAX
    }

    /**
     * Что из строки ядра ложится в файл: список строк (пусто — ничего).
     *
     * Повод. 20.10.2026: ядро на подробности DEBUG/TRACE заполняло свои 3 × 2 МБ за 2–18 минут
     * и вытесняло само себя — к жалобе от беды не оставалось ни строки. В файл идёт всё
     * от INFO и выше (в том числе `inbound/outbound connection`, по которым сервер считает
     * соединения, и все ERROR), а из DEBUG — только то, что сервер разбирает: решение маршрута
     * (`router: match … => route(…)/direct`), отказ имени набором с его запросом (`dns: exchange`
     * пишется лишь когда следом пришёл `=> reject` того же номера) и `urltest … unavailable`.
     * TRACE не пишется. Счёт ([uchest]) и окно сырья ([zapomnit]) видят все строки, как раньше.
     */
    internal fun vFayl(level: Int, line: String): List<String> {
        val uroven = urovenStroki(line) ?: level
        if (uroven <= UROVEN_INFO) return listOf(line)
        if (uroven > UROVEN_DEBUG) return emptyList()
        if (RX_DEBUG_MARSHRUT.containsMatchIn(line)) return listOf(line)
        if (line.contains("outbound/urltest[") && line.contains(" unavailable")) return listOf(line)
        RX_DNS_ZAPROS.find(line)?.let { m ->
            synchronized(dnsZaprosy) { dnsZaprosy[m.groupValues[1]] = line }
            return emptyList()
        }
        RX_DNS_OTKAZ.find(line)?.let { m ->
            val zapros = synchronized(dnsZaprosy) { dnsZaprosy.remove(m.groupValues[1]) }
            return listOfNotNull(zapros, line)
        }
        return emptyList()
    }

    /** Один отказ (прямой или туннеля) — в оба окна и в проверку шторма. */
    private fun schestOtkaz(teper: Long) {
        oknoOtkazov++
        bystroOtkazov++
        if (shtorm(bystroOtkazov, bystroSoed)) {
            // Шторм в коротком окне — и повод спросить режим сети. Поток журнала не держим.
            thread(name = "massovye-otkazy", isDaemon = true) { AutoMode.massovyeOtkazy("шторм за ${BYSTRO_OKNO_MS / 1000} с") }
            podnyatTrevogu(teper, dolyaOtkazov(bystroOtkazov, bystroSoed), bystroOtkazov, "за ${BYSTRO_OKNO_MS / 1000} с")
        }
    }

    private fun uchest(line: String) {
        val teper = System.currentTimeMillis()
        if (oknoNachalo == 0L) oknoNachalo = teper
        if (teper - bystroNachalo >= BYSTRO_OKNO_MS) {
            bystroNachalo = teper
            bystroSoed = 0
            bystroOtkazov = 0
        }
        if (line.contains("inbound connection to")) {
            oknoSoedineniy++
            bystroSoed++
        }
        kodOtkazaTunnelya(line)?.let { kod ->
            oknoTunnel++
            oknoKody[kod] = (oknoKody[kod] ?: 0) + 1
            schestOtkaz(teper)
        }
        if (line.contains("ERROR") && line.contains("open connection to")) {
            otkazUzla(line, teper)
            // Настройки на каждой строке не читаем: порт — из уже работающих параметров ядра, и
            // только пока оно поднимается.
            val wbStarting = WbChatCore.state is WbChatCore.State.Starting
            val wbPodnimaetsya = wbStarting && SocksBreaker.oknoPodemaWb(
                line,
                WbChatCore.runningParams?.socksPort ?: 0,
                wbStarting,
                AutoMode.selectorOnWb(),
            )
            if (!wbPodnimaetsya && predohranitel.offer(line, teper)) {
                KLog.w(TAG, "шторм отказов в локальный сокс — увожу выход с мёртвой комнаты")
                runCatching { Zapisi.perehod("predohranitel", "отказы в локальный сокс") }
                // Переключение выхода — вызов в командный сервер; поток журнала им не держим.
                thread(name = "socks-breaker", isDaemon = true) { AutoMode.roomLost("предохранитель") }
            }
            schestOtkaz(teper)
            val nizhnyaya = line.lowercase(Locale.US)
            val kod = PRICHINY.firstOrNull { nizhnyaya.contains(it.second) }?.first ?: "prochee"
            oknoKody[kod] = (oknoKody[kod] ?: 0) + 1
            // Имя сайта у ядра есть, а в строку отказа оно его не кладёт: там только
            // адрес. Без имени ось «сайт» в журнале телефона отсутствует вовсе, и
            // диагноз 10.09.2026 приходилось ставить обратным запросом руками.
            val adres = RX_ADRES_OTKAZA.find(line)?.groupValues?.getOrNull(1).orEmpty()
            if (golyyAdres(adres) && RX_VYHOD.find(line)?.groupValues?.getOrNull(1) == "direct") oknoPryamoIp++
            if (adres.isNotEmpty()) {
                val imya = runCatching { ImenaSaytov.imya(adres) }.getOrDefault("")
                if (imya.isNotEmpty()) {
                    oknoImena[imya] = (oknoImena[imya] ?: 0) + 1
                    runCatching {
                        rotator?.append(
                            "${stampFormat.format(Date(teper))} -- отказ: $imya → $adres ($kod)\n",
                        )
                    }
                }
                // Тот же отказ — записью с полями. Разбор по ней не зависит от того,
                // какими словами ядро описало беду (см. Zapisi).
                val vyhod = RX_VYHOD.find(line)?.groupValues?.getOrNull(1).orEmpty()
                val port = RX_PORT.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                runCatching { Zapisi.otkaz(imya, adres, port, kod, vyhod, 0) }
            }
        }
        if (teper - oknoNachalo < OKNO_SVODKI_MS) return
        zakrytOkno(teper)
    }

    private fun zakrytOkno(teper: Long) {
        val soed = oknoSoedineniy
        val otk = oknoOtkazov
        val tunnel = oknoTunnel
        val pryamoIp = oknoPryamoIp
        val kody = oknoKody.entries.joinToString(",") { "${it.key}=${it.value}" }
        // Топ имён: пяти хватает, чтобы увидеть «ломается именно Яндекс», а не гадать.
        val topImen = oknoImena.entries.sortedByDescending { it.value }.take(5)
        val imena = topImen.joinToString(",") { "${it.key}=${it.value}" }
        // Копии для записи с полями: сами карты ниже очищаются под новое окно.
        val kodyDlyaZapisi = LinkedHashMap(oknoKody)
        val imenaDlyaZapisi = LinkedHashMap<String, Int>().apply {
            topImen.forEach { put(it.key, it.value) }
        }
        oknoNachalo = teper
        oknoSoedineniy = 0
        oknoOtkazov = 0
        oknoTunnel = 0
        oknoPryamoIp = 0
        oknoKody.clear()
        oknoImena.clear()
        if (soed == 0 && otk == 0) return
        val dolya = if (soed > 0) otk * 100 / soed else 100
        runCatching {
            rotator?.append(
                "${stampFormat.format(Date(teper))} -- сводка за 10 мин: соединений $soed, " +
                    "отказов $otk ($dolya%)${if (kody.isEmpty()) "" else ", причины $kody"}" +
                    "${if (imena.isEmpty()) "" else ", имена $imena"}" +
                    ", из них туннеля $tunnel, прямых на голый адрес $pryamoIp\n",
            )
        }
        // Та же сводка записью с полями: по ней сервер считает норму, и разбирать
        // текстовую строку регулярным выражением ему больше не нужно.
        runCatching {
            Zapisi.svodka(
                okno = (OKNO_SVODKI_MS / 1000L).toInt(),
                soed = soed,
                otkazy = otk,
                kody = kodyDlyaZapisi,
                imena = imenaDlyaZapisi,
                tunnel = tunnel,
                pryamoIp = pryamoIp,
            )
        }
        if (povodSprositRezhim(dolya, kodyDlyaZapisi)) {
            thread(name = "massovye-otkazy", isDaemon = true) { AutoMode.massovyeOtkazy("за окно $dolya%, причины $kody") }
        }
        if (dolya >= DOLYA_TREVOGI && otk >= 10) podnyatTrevogu(teper, dolya, otk, "за окно")
    }

    /**
     * Повод спросить у автомата режим сети по итогам окна.
     *
     * Отказы в свой же сокс на петле (`connection refused`) и нерешённые имена не в счёт:
     * первыми занимается предохранитель, вторые — про DNS, а не про то, что сеть рвёт
     * соединения. Считаются таймауты, сбросы и прочее — так выглядит сеть, которая TCP
     * пропускает, а дальше не пускает.
     */
    internal fun povodSprositRezhim(dolya: Int, kody: Map<String, Int>): Boolean {
        if (dolya < DOLYA_TREVOGI) return false
        val schitaem = kody.filterKeys { it != "otkaz_soedineniya" && it != "imya_ne_reshilos" }.values.sum()
        return schitaem >= POVOD_MIN_OTKAZOV
    }

    /** Сколько отказов в окне нужно, чтобы стоило спрашивать режим сети. */
    internal const val POVOD_MIN_OTKAZOV = 10

    /** Доля отказов в процентах; без соединений считаем, что отказывает всё. */
    internal fun dolyaOtkazov(otkazov: Int, soed: Int): Int = if (soed > 0) otkazov * 100 / soed else 100

    /**
     * Шторм ли это в коротком окне.
     *
     * Срабатывает ровно на пороге, а не на каждом отказе сверх него: одна тревога на окно.
     * Доля нужна, чтобы сотня отказов на десятках тысяч живых соединений тревогой не была.
     */
    internal fun shtorm(otkazov: Int, soed: Int): Boolean =
        otkazov == BYSTRO_POROG && dolyaOtkazov(otkazov, soed) >= BYSTRO_DOLYA

    /** Одна тревога на оба окна: короткое и десятиминутное делят общий предел частоты. */
    private fun podnyatTrevogu(teper: Long, dolya: Int, otk: Int, gde: String) {
        if (teper - trevogaBylaV <= TREVOGA_NE_CHASHCHE_MS) return
        trevogaBylaV = teper
        KLog.w(TAG, "отказов $dolya% $gde — отправляю журнал, не дожидаясь ночи")
        runCatching { Zapisi.perehod("trevoga", "отказов $dolya% $gde, всего $otk") }
        sohranitOknoSyrya(teper, dolya, otk)
        LogUploadWork.otpravitSeychas("по тревоге")
    }

    /**
     * Последние строки ядра, чтобы при тревоге сохранить ОКНО вокруг события.
     *
     * Зачем. Поток ядра заполняет свои шесть мегабайт за считанные минуты и вытесняет
     * сам себя: к ночной отправке от беды не остаётся ни строки. Сводка говорит, что
     * беда была, а вот КАКАЯ — видно только в сырье, и новую болезнь, которой в каталоге
     * нет, распознать без него нельзя вовсе. Держим кольцом в памяти, на диск кладём
     * только когда действительно тревога.
     */
    private const val OKNO_SYRYA_STROK = 2000

    private val kolco = ArrayDeque<String>(OKNO_SYRYA_STROK)

    private fun zapomnit(line: String) {
        synchronized(kolco) {
            if (kolco.size >= OKNO_SYRYA_STROK) kolco.removeFirst()
            kolco.addLast(line)
        }
    }

    /** Сколько окон сырья держим железно, даже если ни одно ещё не уехало. */
    private const val MAX_OKNA_SYRYA = 2

    /** И сколько их лежит на диске самое большее — потолок поверх всего. */
    private const val MAX_VSEGO_OKON = 8

    /**
     * Какие файлы окна сырья удалить.
     *
     * Чистая функция — без [File] и [Application], чтобы её можно было пощупать
     * юнит-тестом без Robolectric. Разбор 10.09.2026 нашёл в прежней версии (просто
     * `dropLast(2)` после сортировки по имени) дыру: удаление считало только возраст
     * файла и не смотрело, уехал ли он на сервер. При устойчивом «ядро валит
     * соединения» (тревога каждый час) и одновременно «отправка недоступна много
     * часов» (сценарий VPN без сети) третий файл вытеснял ПЕРВЫЙ ДО отправки — терялось
     * ровно то окно, ради которого механизм и написан.
     *
     * [imena] — имена файлов `kelevra-syrye-*` (в имени таймстамп, сортировка по имени
     * = по времени). [otpravleny] — те из них, про которые точно известно, что они уже
     * уехали. Кандидаты на удаление — все, кроме [ostavit] самых свежих; из кандидатов
     * удаляются только отправленные — неотправленный файл переживёт любое число
     * последующих тревог, пока не уедет сам.
     *
     * Но не бесконечно: сверх [potolok] файлов уходят самые старые, даже неотправленные.
     * Иначе прежний страх («череда тревог забьёт хранилище телефона») возвращается уже
     * в другом виде: тревога не чаще раза в час, но окно — 2000 строк ядра (~240 КБ),
     * то есть сутки без связи дают почти 6 МБ, неделя — под 40. Из двух потерь выбираем
     * старейшую: свежее окно ближе к беде, которую сервер ещё не видел.
     */
    internal fun staryeFajlyKUdaleniyu(
        imena: List<String>,
        otpravleny: Set<String>,
        ostavit: Int = MAX_OKNA_SYRYA,
        potolok: Int = MAX_VSEGO_OKON,
    ): List<String> {
        val poVremeni = imena.sorted()
        val kandidaty = poVremeni.dropLast(ostavit)
        val udalit = kandidaty.filter { it in otpravleny }.toMutableSet()
        var ostanetsya = poVremeni.size - udalit.size
        for (imya in kandidaty) {
            if (ostanetsya <= potolok) break
            if (udalit.add(imya)) ostanetsya--
        }
        return poVremeni.filter { it in udalit }
    }

    /**
     * Признак «отправлен» для реальных файлов: читает те же отметки, что копит
     * [LogUploadWork] (файл не трогаем — только читаем его общую отметку), и считает
     * файл уехавшим, если по нему записано `sent >= size` при совпавшем размере.
     */
    private fun otpravlennyeImena(fajly: List<File>): Set<String> {
        val raw = runCatching { Settings.logUploadMarks }.getOrDefault("")
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val json = JSONObject(raw)
            fajly.filter { f ->
                val mark = json.optJSONObject(f.name) ?: return@filter false
                val size = mark.optLong("size")
                size > 0 && size == f.length() && mark.optLong("sent") >= size
            }.map { it.name }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Сложить кольцо в отдельный файл: отправка забирает весь каталог, значит уедет само. */
    private fun sohranitOknoSyrya(teper: Long, dolya: Int, otkazov: Int) {
        val stroki = synchronized(kolco) { kolco.toList() }
        if (stroki.isEmpty()) return
        runCatching {
            val papka = AppLog.dir(Application.application)
            val imya = "kelevra-syrye-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date(teper)) + ".log"
            val fayl = File(papka, imya)
            fayl.writeText(
                "=== окно сырья: отказов $otkazov ($dolya%), строк ${stroki.size} ===\n" +
                    stroki.joinToString("\n") + "\n",
            )
            KLog.i(TAG, "окно сырья сохранено: $imya, строк ${stroki.size}")
            val vse = papka.listFiles { f -> f.name.startsWith("kelevra-syrye-") }?.toList()
                ?: return@runCatching
            staryeFajlyKUdaleniyu(vse.map { it.name }, otpravlennyeImena(vse))
                .forEach { name -> runCatching { File(papka, name).delete() } }
        }.onFailure { KLog.w(TAG, "окно сырья не сохранилось: ${it.message}") }
    }

    private val handler = object : CommandClient.Handler {
        override fun appendLogs(message: List<LogEntry>) {
            val target = rotator ?: return
            val stamp = stampFormat.format(Date())
            val text = buildString {
                for (entry in message) {
                    val line = ansi.replace(entry.message, "").trimEnd()
                    if (line.isEmpty()) continue
                    runCatching { zapomnit(line) }
                    runCatching { uchest(line) }
                    val stroki = runCatching { vFayl(entry.level, line) }.getOrDefault(listOf(line))
                    for (stroka in stroki) append(stamp).append(' ').append(stroka).append('\n')
                }
            }
            runCatching { target.append(text) }
        }

        override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
            runCatching { rotator?.append("-- связь с ядром потеряна: $kind $message\n") }
        }
    }

    /** Поднимается вместе с ядром. Повторный вызов живую запись не трогает. */
    @Synchronized
    fun start() {
        if (client != null) return
        val started = runCatching {
            val folder = AppLog.dir(Application.application)
            if (!folder.isDirectory && !folder.mkdirs()) error("каталог журнала не создан: $folder")
            rotator = LogRotator(folder, BASE_NAME, MAX_FILE_BYTES, MAX_FILES, header = AppLog::shapkaFayla)
            val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = own
            CommandClient(own, CommandClient.ConnectionType.Log, handler, localOnly = true)
                .also { it.connect() }
        }
        started.onSuccess {
            client = it
            // Пояс и состав — в сам файл: шапка ложится только в начало нового файла, а
            // после перезапуска запись продолжает старый.
            runCatching {
                rotator?.append(
                    "${stampFormat.format(Date())} === журнал ядра начат, пояс ${AppLog.poyas()}, " +
                        "пишу INFO и выше, из DEBUG — нужное разбору ===\n",
                )
            }
            KLog.i(TAG, "журнал ядра пишется в $BASE_NAME")
        }.onFailure {
            KLog.w(TAG, "журнал ядра не открылся: ${it.message}")
            rotator = null
            scope?.cancel()
            scope = null
        }
    }

    /** Гаснет вместе с ядром: без него канал всё равно молчит. */
    @Synchronized
    fun stop() {
        runCatching { client?.disconnect() }
        client = null
        runCatching { scope?.cancel() }
        scope = null
        runCatching { rotator?.close() }
        rotator = null
    }
}
