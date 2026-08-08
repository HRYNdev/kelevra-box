package io.nekohasekai.sfa.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.path.Evidence
import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathSnapshot
import io.nekohasekai.sfa.bg.path.PathStatus
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Определитель обстановки: сам решает, каким выходом идти, и сам его включает.
 *
 * Отвечает ровно на один вопрос — **что сейчас реально поднимается** — и по ответу
 * делает одно действие. Классификатора «режимов сети» тут нет специально: диагноз
 * может быть неточным (VPS мог просто упасть), но действие от этого не меняется.
 *
 * Четыре обстановки:
 *
 * Комната не висит постоянно. Её несущая — чужой видеозвонок, и вся защита схемы держится
 * на том, что звонок живёт ровно столько, сколько нужен. Поэтому ядро olcRTC поднимается
 * только когда комната действительно понадобилась, и гасится, как только ушли на основной
 * канал. Отличить «белый список» от «сети нет вообще» иначе нельзя: чтобы узнать, встаёт ли
 * комната, её надо попробовать поднять. Подъём поэтому **пробный** — см. [trialRaiseRoom].
 *
 *  1. [Situation.Home] — обход уже делает роутер. Признак однозначный и проверен живьём:
 *     домашний DNS отдаёт для проксируемых доменов подменные адреса из 198.18.0.0/15
 *     (`youtube.com → 198.18.3.9`), а для российских — настоящие (`gosuslugi.ru → 213.59.254.7`).
 *     Спрашиваем СИСТЕМНЫМ резолвером физической сети, не через свой туннель, и **мимо
 *     кеша** ([HomeProbe]): кеш помнит ответы прошлой сети и первые секунды после вайфая
 *     врёт «не дома».
 *     Действие: **туннель гасим** — вторая обёртка поверх роутера только грузит телефон.
 *  2. [Situation.Main] — основной канал поднимается. Действие: работаем им.
 *  3. [Situation.Room] — основной канал не поднимается вообще (при белом списке адрес
 *     VPS недостижим на уровне маршрутизации), а комната стоит: её несущая — видеозвонок,
 *     он в разрешённых. Действие: уходим в комнату.
 *  4. [Situation.NoNetwork] — сети нет. Действие: **не долбиться**, ждать смены сети.
 *
 * Почему не дёргается. Обстановка меняется только после [CONFIRMATIONS] одинаковых
 * наблюдений подряд: одиночный отказ — флуктуация, тот же принцип, что в [OlcRtcWatchdog].
 * Исключение одно и оно осознанное: **смена сети** (вайфай↔мобильный, другая точка) —
 * это доказанное изменение обстановки, после неё первое же наблюдение принимается сразу.
 *
 * Почему смена сети не заканчивается одним наблюдением. Система объявляет новую сеть
 * раньше, чем на ней заработает всё остальное, поэтому за первым наблюдением идёт
 * серия перепроверок ([AutoModeBurst]: 1, 3, 8, 20 секунд), которая обрывается, как
 * только обстановка устоялась.
 *
 * Почему не жжёт батарею. Ритм зависит от того, где мы стоим: пока ничего не работает —
 * часто, но с растущей паузой; когда встали на рабочий выход — редко; из комнаты назад
 * смотрим совсем лениво (раз в 15 минут), потому что нормальный повод вернуться — это
 * смена сети, а не таймер. Когда сети нет, таймера нет вообще: ждём события.
 *
 * Слежение при погашенном туннеле. Гасится ядро и tun, а сам сервис остаётся жить
 * передним планом — вместе с ним живёт и подписка на смену сети ([DefaultNetworkListener],
 * тот же колбэк, что уже слушает sing-box). Поэтому уход из дома виден бесплатно, и
 * разрешение на VPN второй раз не спрашивается: сервис не умирал.
 */
object AutoMode {
    private const val TAG = "AutoMode"

    enum class Situation {
        /** Ещё не смотрели. */
        Unknown,

        /** Дома: обход делает роутер, туннель не нужен. */
        Home,

        /** Обычная сеть: работает основной канал. */
        Main,

        /** Основной канал не поднимается, идём комнатой. */
        Room,

        /** Сеть есть, но не поднимается ничего. */
        Searching,

        /** Сети нет. */
        NoNetwork,
    }

    /**
     * Чем кончилась последняя проба. Обстановка отвечает на вопрос «куда идём», а это —
     * на вопрос «дошли ли»: без него экран писал «Подключено» по факту живого сервиса,
     * в том числе пока ничего ещё не мерили и когда все пробы уже провалились.
     */
    enum class Link {
        /** Ещё не мерили. */
        Unknown,

        /** Меряем прямо сейчас. */
        Checking,

        /** Через выбранный путь данные ходят (или мы дома, где путь не нужен). */
        Alive,

        /** Ни основной канал, ни комната не отвечают. */
        Dead,
    }

    data class State(
        val auto: Boolean = true,
        val situation: Situation = Situation.Unknown,
        val link: Link = Link.Unknown,
    )

    private val _state = MutableStateFlow(State(auto = true, situation = Situation.Unknown))
    val state: StateFlow<State> = _state

    /** Итог последней пробы — держим отдельно, чтобы не терять его при смене обстановки. */
    @Volatile
    private var link: Link = Link.Unknown

    /**
     * Пересчитывает итог проб по общей памяти о путях.
     *
     * [Link] остался ради тех, кто на него смотрит, но своей правды у него больше нет:
     * он целиком выводится из снимка [PathRegistry]. Раньше это было отдельное поле,
     * которое ставили руками в шести местах, — и оно жило своей жизнью.
     */
    private fun refreshLink() {
        val value = linkFrom(PathRegistry.snapshot.value)
        if (link == value) return
        link = value
        _state.value = _state.value.copy(link = value)
    }

    /**
     * Итог проб по снимку. Порядок веток — это и есть смысл: пока хоть что-то меряется,
     * говорить «связи нет» рано, а один живой путь важнее двух отказавших.
     */
    internal fun linkFrom(snapshot: PathSnapshot): Link = when {
        snapshot.anyIs(PathStatus.Probing) -> Link.Checking
        snapshot.anyIs(PathStatus.Alive) -> Link.Alive
        snapshot.any { it.refused } -> Link.Dead
        else -> Link.Unknown
    }

    /**
     * Путь, на котором стоим. Решает по-прежнему автомат — реестр помнит состояние путей,
     * но не то, каким из них мы идём.
     */
    fun standingOn(): PathId? = when {
        !Settings.autoModeEnabled && Settings.autoModeManualRoom -> PathId.ROOM
        else -> when (_state.value.situation) {
            Situation.Home -> PathId.HOME
            Situation.Main -> PathId.MAIN
            Situation.Room -> PathId.ROOM
            else -> null
        }
    }

    private fun publish(auto: Boolean, situation: Situation) {
        _state.value = State(auto = auto, situation = situation, link = link)
    }

    /**
     * Что автомат умеет сделать с сервисом. Реализует [BoxService]: только у него есть
     * и ядро, и tun, и protect(fd).
     */
    interface Host {
        /**
         * Гасит ядро и tun. Сервис остаётся жив — иначе некому будет заметить смену сети.
         * @return true, если туннель действительно погасили этим вызовом.
         */
        fun suspendTunnel(reason: String): Boolean

        /**
         * Поднимает обратно то, что погасили.
         * @return true, если туннель действительно подняли этим вызовом.
         */
        fun resumeTunnel(reason: String): Boolean

        /** Живо ли сейчас ядро sing-box. Пока туннель погашен, его локальным входом не спросить. */
        fun tunnelLive(): Boolean

        /** Конфиг, с которым работает ядро. */
        fun profileConfig(): String?

        /** Переключает выход внутри группы. */
        fun selectExit(group: String, tag: String)

        /**
         * Поднимает или гасит ядро комнаты. Вызов идемпотентный: если комната уже
         * в нужном состоянии, ничего не делается.
         *
         * @return true, если состояние действительно поменяли — тогда ядро sing-box
         *   пересобрано, и выбор в селекторе надо выставить заново.
         */
        fun setRoomWanted(wanted: Boolean, reason: String): Boolean
    }

    // 198.18.0.0 .. 198.19.255.255 — диапазон подменных адресов из конфига роутера.
    private const val FAKE_IP_FIRST = 0xC612_0000L
    private const val FAKE_IP_LAST = 0xC613_FFFFL

    /**
     * Домены, которые роутер точно заворачивает. Требуем совпадения минимум по
     * [HOME_HITS], чтобы одиночная случайность (чужой CDN, перехват провайдера)
     * не сработала как «дома».
     */
    private val HOME_DOMAINS = listOf("youtube.com", "discord.com", "rutracker.org")
    private const val HOME_HITS = 2

    /**
     * Контроль: российский домен обязан резолвиться в настоящий адрес. Если подменными
     * стали и он тоже — это не наш роутер, а что-то, что подменяет всё подряд.
     */
    private const val HOME_CONTROL = "gosuslugi.ru"

    private const val DNS_BUDGET_MILLIS = 2_500L
    private const val TCP_TIMEOUT_MILLIS = 4_000

    /**
     * Куда ходит честная проба основного канала.
     *
     * Имя выбрано не наугад: в конфиге от сервера трафик уводит в селектор набор правил,
     * и `youtube` в нём есть всегда — это и есть смысл всей затеи. Значит запрос гарантированно
     * пойдёт выбранным выходом, а не мимо него по `final: direct`, и проба меряет канал,
     * а не то, что и без канала работает. Порт 80: ответ короткий (редирект), TLS не нужен.
     */
    private const val PROBE_HOST = "www.youtube.com"
    private const val PROBE_PORT = 80

    /**
     * Потолок ожидания честной пробы. Канал под ТСПУ обычно не отвечает вовсе, а не отвечает
     * медленно; шести секунд хватает и живому каналу через комнату (замеры 05.08: 0.6-2 с).
     */
    private const val PROBE_TIMEOUT_MILLIS = 6_000

    /** Столько одинаковых наблюдений подряд нужно, чтобы поменять обстановку. */
    const val CONFIRMATIONS = 3

    /**
     * После стольких провалов основного канала подряд поднимаем комнату пробно.
     *
     * Ровно на один заход раньше, чем задвижка успевает подтвердить смену обстановки.
     * Меньше нельзя: одиночная просадка будила бы видеозвонок, и вышли бы качели
     * «поднял — погасил — поднял». Больше нельзя: комната не успела бы встать к моменту,
     * когда решение уже принято, и автомат ушёл бы в «ничего не поднимается» на пустом месте.
     */
    const val ROOM_TRIAL_AFTER = CONFIRMATIONS - 1

    /** Ритм проверок для каждой обстановки. */
    private const val ROUND_SEARCHING_MILLIS = 12_000L
    private const val ROUND_SEARCHING_CAP_MILLIS = 120_000L
    private const val ROUND_HOME_MILLIS = 5 * 60_000L
    private const val ROUND_MAIN_MILLIS = 5 * 60_000L

    /** Ленивый возврат: из комнаты на быстрый канал смотрим редко. */
    private const val ROUND_ROOM_MILLIS = 15 * 60_000L

    /**
     * Паузы серии перепроверок после смены сети.
     *
     * Первая — почти сразу: вайфай к этому моменту обычно уже раздал адрес и DNS.
     * Дальше растут, потому что если за двадцать секунд обстановка не устоялась,
     * то дело не в том, что мы рано посмотрели. Серия обрывается сама, как только
     * заход перестал что-либо менять — см. [AutoModeBurst].
     */
    internal val BURST_STEPS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)

    private val lock = Object()

    @Volatile
    private var active = false

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var host: Host? = null

    @Volatile
    private var layout: AutoModeExits.Layout = AutoModeExits.Layout.EMPTY

    /** Сеть сменилась — следующее наблюдение принимаем без подтверждений. */
    @Volatile
    private var networkChanged = false

    /**
     * Сеть, про которую нам в последний раз сказали. Нужна, чтобы отличить настоящую
     * смену сети от мелочи: колбэк приходит и на изменение уровня сигнала, а гонять
     * на такое полный круг проб (да ещё и принимать его вердикт без подтверждений)
     * значит и жечь батарею, и обесценивать саму защиту от дёрганья.
     */
    @Volatile
    private var lastNetwork: Network? = null

    /** Была ли та сеть проверена системой: подтверждение интернета — это тоже событие. */
    @Volatile
    private var lastValidated = false

    /** Автомат выключен человеком: ничего не проверяем и таймер не заводим. */
    @Volatile
    private var idle = false

    /**
     * Видим не то, на чём стоим, но подтверждений ещё не набрали.
     *
     * Ритм при этом ускоряется до [ROUND_SEARCHING_MILLIS]: иначе «не дёргаться» вырождается
     * в «полчаса сидеть на мёртвом канале», потому что на рабочем выходе заходы редкие.
     */
    @Volatile
    private var pendingSwitch = false

    private val gate = AutoModeGate(CONFIRMATIONS)
    private val burst = AutoModeBurst(BURST_STEPS)
    private var searchingRounds = 0

    /** Итог последнего захода: ничего не поменялось и подтверждений никто не ждёт. */
    @Volatile
    private var settled = false

    /** Что выбрали последним: не дёргаем ядро одним и тем же выбором. */
    private var selected: String? = null

    /**
     * Выход, который человек выбрал руками. Нужен, чтобы вернуть его выбор после пересборки
     * ядра. Держится ещё и в настройках: выбор часто делают при выключенной сети, когда
     * отдать команду некому, а память процесса до включения не доживает.
     */
    @Volatile
    private var manualExit: String? = Settings.manualExitName.takeIf { it.isNotBlank() }

    /** Сколько заходов подряд основной канал не поднимается. Считает повод для пробного подъёма. */
    private var mainFailures = 0

    /** Чем кончилась честная проба этого захода. Нужна только для записи в реестр. */
    @Volatile
    private var lastMainProbe: ProxyProbe.Result? = null

    /**
     * Сеть, в которой человек выбрал выход руками. Выбор держится, пока мы в ней:
     * см. [chooseManually] и [onNetworkChanged].
     */
    @Volatile
    private var holdNetwork: Network? = null

    /** Последняя пробная попытка поднять комнату — чтобы не долбить её каждым заходом. */
    private var roomTriedAt = 0L
    private var roomTrialPause = ROOM_TRIAL_PAUSE_MILLIS
    private const val ROOM_TRIAL_PAUSE_MILLIS = 60_000L

    /** Комната не встаёт — паузу растим, чтобы не жечь батарею попытками в пустоту. */
    private const val ROOM_TRIAL_PAUSE_CAP_MILLIS = 10 * 60_000L

    /**
     * Включает автомат. Звать после того, как сервис поднялся.
     *
     * @param initial обстановка, которую уже определили на старте (чтобы не поднимать
     *   туннель дома и тут же его гасить).
     */
    fun start(host: Host, initial: Situation = Situation.Unknown) {
        synchronized(lock) {
            stopLocked()
            this.host = host
            layout = host.profileConfig()?.let { AutoModeExits.parse(it, OlcRtcParams.socksPort) }
                ?: AutoModeExits.Layout.EMPTY
            gate.reset(initial)
            searchingRounds = 0
            selected = null
            // Сервис только что поднялся — обстановка по определению «только что изменилась»,
            // и первое наблюдение можно принять сразу, не выжидая три захода.
            networkChanged = true
            lastNetwork = DefaultNetworkMonitor.defaultNetwork
            lastValidated = lastNetwork?.let(::validated) ?: false
            burst.cancel()
            settled = false
            idle = false
            pendingSwitch = false
            roomTriedAt = 0L
            roomTrialPause = ROOM_TRIAL_PAUSE_MILLIS
            mainFailures = 0
            // Прошлая сессия про эти пути больше ничего не знает: сеть под нами могла
            // смениться, пока сервиса не было.
            PathRegistry.reset()
            PathRegistry.bindExits(main = layout.main, room = layout.room)
            link = Link.Unknown
            holdNetwork = physicalNetwork()
            publish(Settings.autoModeEnabled, initial)
            active = true
            thread = Thread(::loop, "automode").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "автомат включён (${if (Settings.autoModeEnabled) "сам" else "выключен человеком"}): " +
                    "выбор ${layout.chooser ?: "нет"}, основной ${layout.main ?: "нет"}, " +
                    "комната ${layout.room ?: "нет"}, адресов ${layout.mainEndpoints.size}",
            )
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        if (!active && thread == null) return
        active = false
        val t = thread
        thread = null
        lock.notifyAll()
        t?.interrupt()
        t?.join(1_000)
        host = null
        PathRegistry.reset()
        link = Link.Unknown
        publish(Settings.autoModeEnabled, Situation.Unknown)
        Log.i(TAG, "автомат выключен")
    }

    /**
     * Системе есть что сказать про сеть по умолчанию. Колбэк приходит из системного
     * потока — только будим свой.
     *
     * Приходит он на три разных события: появилась новая сеть, основной стала другая,
     * система подтвердила на ней интернет. Плюс на всякую мелочь вроде уровня сигнала —
     * а вот на неё будить автомат нельзя: полный круг проб стоит батареи, и хуже того,
     * каждое такое «событие» принималось бы без подтверждений, то есть защита от
     * дёрганья работала бы только на бумаге. Поэтому реагируем на смену самой сети
     * и на смену её проверенности, а не на каждый чих.
     *
     * @param network сеть, которую система считает основной; null — её не стало.
     */
    fun onNetworkChanged(network: Network?) {
        if (!active) return
        val nowValidated = network?.let(::validated) ?: false
        if (network == lastNetwork && nowValidated == lastValidated) return
        lastNetwork = network
        lastValidated = nowValidated
        networkChanged = true
        Log.i(TAG, "сеть сменилась: ${describe(network)}")
        // Ручной выбор — это «стой здесь», а не «выключи автомат навсегда». Он держится,
        // пока мы в той же сети: под неё человек и выбирал. Сеть другая — обстановка
        // другая, и держаться за прошлый выбор значит увезти человека в мёртвый выход.
        // Сравниваем именно физические сети: наш собственный туннель тоже приходит сюда
        // сменой сети по умолчанию, и по ней выбор отпускался бы через секунду после того,
        // как его сделали.
        if (!Settings.autoModeEnabled) {
            val now = physicalNetwork()
            if (now != null && now != holdNetwork) releaseManualHold(now)
        }
        synchronized(lock) { lock.notifyAll() }
    }

    /** Сеть сменилась — ручной выбор больше ничего не значит, автомат снова сам. */
    private fun releaseManualHold(network: Network?) {
        Log.i(TAG, "сеть сменилась — ручной выбор «${manualExit ?: "нет"}» отпущен, автомат снова сам")
        holdNetwork = network
        manualExit = null
        Settings.manualExitName = ""
        Settings.autoModeManualRoom = false
        Settings.autoModeEnabled = true
        idle = false
        selected = null
        gate.reset(Situation.Unknown)
        mainFailures = 0
        PathRegistry.reset()
        link = Link.Unknown
        publish(true, Situation.Unknown)
    }

    /** Человек включил или выключил автомат. */
    fun setEnabled(enabled: Boolean) {
        Settings.autoModeEnabled = enabled
        if (enabled) {
            manualExit = null
            Settings.manualExitName = ""
            Settings.autoModeManualRoom = false
            holdNetwork = null
            PathRegistry.reset()
            link = Link.Unknown
        }
        _state.value = _state.value.copy(auto = enabled, link = link)
        gate.reset(Situation.Unknown)
        mainFailures = 0
        networkChanged = true
        synchronized(lock) { lock.notifyAll() }
    }

    /** Имя комнаты приходит с сервера, поэтому узнаём её по корню слова, как и экран. */
    private fun looksLikeRoom(tag: String): Boolean =
        tag.lowercase().let { it.contains("комнат") || it.contains("room") }

    /**
     * Человек выбрал выход руками. Автомат при этом отходит: иначе он через минуту
     * передумает, и выбор не удержится.
     *
     * Отходит **до смены сети**, а не навсегда. Выбор всегда сделан под то, что вокруг
     * прямо сейчас («здесь основной канал живой, хочу его»), и в другой сети он значит
     * ровно ничего — а раньше один случайный тык оставлял человека без автомата до тех
     * пор, пока он сам не вспомнит про пункт «Автоматически». Отпускает выбор
     * [onNetworkChanged], состояние «выбрано вручную» видно на главном экране.
     *
     * Комнату сюда тащим осознанно: ядро olcRTC поднимается не только по решению автомата,
     * но и когда человек выбрал комнату сам. Выбор запоминаем в настройках, потому что он
     * должен пережить перезапуск сервиса — иначе после перезагрузки телефона выбранная
     * комната оказалась бы выбранной, но не поднятой.
     */
    fun chooseManually(tag: String) {
        manualExit = tag
        Settings.manualExitName = tag
        // Экран сразу применяет выбор в ядре, поэтому запоминаем его и мы: иначе автомат
        // будет считать, что стоит на основном канале, и мерять пробой чужой выход.
        //
        // Но только когда ядро вообще есть. При выключенной сети команду отдавать некому,
        // и запись «уже выбрано» заставляла choose() промолчать после старта: ядро
        // оставалось на своём дефолте. Ровно так выбранная комната превращалась в
        // «Нидерланды, выбран вручную» (поймано в эмуляторе 07.08.2026).
        selected = if (host != null) tag else null
        // Комнату узнаём и по имени тоже. Раскладка приходит от работающего ядра, а выбор
        // делают чаще всего до включения сети: тогда layout.room пуст, сравнивать не с чем,
        // и выбранная комната записывалась как обычный выход. Ниже, в round(), выбор ещё
        // раз уточняется по раскладке, когда она появится.
        val room = layout.room?.let { tag == it } ?: looksLikeRoom(tag)
        Settings.autoModeManualRoom = room
        // Человек выбрал комнату — значит аварийный выключатель, если он был нажат,
        // он снимает этим же действием. Без этого выбор комнаты молча давал обычный
        // выход: круг писал «Комната», нога не видела участника вовсе (поймано в
        // эмуляторе 07.08.2026).
        if (room) Settings.olcrtcEnabled = true
        Settings.autoModeEnabled = false
        // Сеть, под которую сделан выбор. Сменится — автомат вернётся сам.
        holdNetwork = physicalNetwork()
        // Прошлые замеры делались под выбор автомата: держать их и показывать как правду
        // про выход, который человек выбрал сам, значит врать.
        PathRegistry.reset()
        link = Link.Unknown
        _state.value = _state.value.copy(auto = false, situation = Situation.Unknown, link = link)
        gate.reset(Situation.Unknown)
        mainFailures = 0
        idle = false
        Log.i(
            TAG,
            "выход выбран руками: «$tag» (комната: ${Settings.autoModeManualRoom}) — " +
                "держим до смены сети",
        )
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Проба «мы дома» одним вызовом, без запуска автомата. Нужна на старте сервиса:
     * дома туннель не надо поднимать вообще, а не поднимать и через секунду гасить.
     */
    fun homeRightNow(): Boolean {
        if (!Settings.autoModeEnabled) return false
        val network = physicalNetwork() ?: return false
        return homeBypass(network)
    }

    private fun loop() {
        while (active) {
            val situation = runCatching { round() }.getOrElse {
                Log.w(TAG, "заход сорвался: ${it.message}")
                _state.value.situation
            }
            if (!active) return
            // Серия перепроверок после смены сети идёт вперёд обычного ритма и обрывается
            // сама, как только обстановка устоялась.
            val hurry = burst.next(settled)
            if (hurry != null) Log.i(TAG, "серия после смены сети: следующая проверка через ${hurry / 1000.0} с")
            waitNext(
                when {
                    // Автомат выключен человеком — таймер не нужен, ждём его же переключателя.
                    idle -> Long.MAX_VALUE
                    hurry != null -> hurry
                    // Идёт набор подтверждений — досматриваем быстро, а не через пять минут.
                    pendingSwitch -> ROUND_SEARCHING_MILLIS
                    else -> delayFor(situation)
                },
            )
        }
    }

    /** @return обстановка, на которой стоим после этого захода. */
    private fun round(): Situation {
        val host = this.host ?: return Situation.Unknown

        if (!Settings.autoModeEnabled) {
            // Человек выбирает выход сам. Обязанностей у нас тут две: вернуть туннель,
            // если сами же его погасили (иначе выбор руками упрётся в выключенное ядро),
            // и привести комнату в то состояние, которое следует из его выбора.
            if (host.resumeTunnel("автомат выключен человеком")) selected = null
            // Выбрать выход можно раньше, чем автомат прочитает конфиг: список выходов
            // экран строит сам, а раскладка появляется только к первому заходу. Тогда
            // [chooseManually] не с чем было сравнить имя и записал «не комната».
            // Сверяем ещё раз, как только раскладка есть, иначе выбранная комната
            // молча оставалась бы непóднятой.
            manualExit?.let { tag ->
                val room = layout.room
                if (room != null && Settings.autoModeManualRoom != (tag == room)) {
                    Settings.autoModeManualRoom = tag == room
                    Log.i(TAG, "выбор «$tag» уточнён по раскладке: комната = ${Settings.autoModeManualRoom}")
                }
            }
            val wantRoom = Settings.autoModeManualRoom
            val changed = runCatching {
                host.setRoomWanted(wantRoom, if (wantRoom) "комнату выбрал человек" else "человек выбрал обычный выход")
            }.getOrElse {
                Log.w(TAG, "комнату переключить не вышло: ${it.message}")
                false
            }
            if (changed) {
                // Пересборка ядра сбрасывает выбор в селекторе на первый пункт конфига —
                // возвращаем то, что выбрал человек.
                selected = null
            }
            // Выбор возвращаем каждым заходом, а не только когда трогали комнату. На старте
            // сервиса комнату поднимает уже BoxService, setRoomWanted отвечает «ничего не
            // менялось», и выбор человека не применялся вовсе: ядро оставалось на первом
            // выходе конфига. Ровно так выбранная комната работала как «Нидерланды»
            // (поймано в эмуляторе 07.08.2026). Лишних команд нет: choose молчит, когда
            // выбранное уже стоит.
            manualExit?.let { choose(host, it) }
            // Пробы тут не идут, но про комнату её ядро говорит и без нас: без этой записи
            // выбранная руками комната выглядела бы «непроверенной» всё время выбора.
            // Итог проб ([refreshLink]) при этом не трогаем — мерять действительно некому.
            noteRoom()
            idle = true
            burst.cancel()
            settled = true
            publish(auto = false, situation = Situation.Unknown)
            return Situation.Unknown
        }
        idle = false

        val trustOnce = networkChanged
        if (trustOnce) {
            networkChanged = false
            searchingRounds = 0
            burst.restart()
            Log.i(TAG, "сеть сменилась — перепроверяю обстановку")
        }

        val was = gate.current
        val observed = observe(host)
        val changed = gate.offer(observed, trust = trustOnce, hurried = burst.active && !trustOnce)
        pendingSwitch = gate.pending
        settled = !changed && !gate.pending

        when {
            changed -> {
                Log.i(TAG, "обстановка сменилась: ${name(was)} → ${name(observed)}")
                publish(auto = true, situation = observed)
                apply(host, observed, repeat = false)
            }

            // Обстановка та же, но выбор мог сбиться (ядро перезапустилось,
            // человек ткнул руками до выключения автомата) — повторяем действие.
            observed == gate.current -> apply(host, observed, repeat = true)

            else -> Log.i(TAG, "вижу ${name(observed)}, подтверждения не набраны — пока не трогаю")
        }
        return gate.current
    }

    private fun observe(host: Host): Situation {
        val network = physicalNetwork() ?: run {
            PathRegistry.allUnavailable("сети нет")
            refreshLink()
            return decide(false, false, false, false)
        }
        // Пока меряем — так и говорим. Иначе экран весь этот десяток секунд утверждает,
        // что всё подключено, хотя ещё ничего не проверено.
        PathRegistry.probing(PathId.HOME)
        refreshLink()
        // Порядок проб важен и стоит денег: дома дальше смотреть незачем, а комнату
        // спрашиваем только когда основной канал уже не отвечает.
        val home = homeBypass(network)
        if (home) {
            PathRegistry.alive(PathId.HOME)
            // Основной канал этим заходом не мерили — так и записываем. Прошлый его
            // отказ был в другой сети и про эту не говорит ничего.
            PathRegistry.unchecked(PathId.MAIN, "дома обход делает роутер")
        } else {
            PathRegistry.dead(PathId.HOME, "подменных адресов нет")
            // Обе записи одним движением: между ними снимок читателю не показываем,
            // иначе экран мигнёт «связи нет» ровно посреди своей же проверки.
            PathRegistry.probing(PathId.MAIN)
        }
        refreshLink()
        val main = !home && mainWorks(network, host)
        if (home || main) {
            mainFailures = 0
        } else {
            mainFailures++
        }

        val room = if (home || main) {
            false
        } else {
            // Комната больше не висит круглосуточно, поэтому «стоит ли она» — это ответ
            // на вопрос, который мы сами и создаём. Пробуем поднять, но только когда
            // основной канал провалился уже [ROOM_TRIAL_AFTER] заходов подряд.
            if (mainFailures >= ROOM_TRIAL_AFTER) trialRaiseRoom(host)
            roomAlive()
        }
        noteRoom()
        refreshLink()
        return decide(hasNetwork = true, home = home, main = main, room = room)
    }

    /**
     * Теневая запись про комнату: что о ней говорит её собственное ядро.
     *
     * Своего мнения тут нет ни на грош — только перевод состояния [OlcRtcCore] в общую
     * память. Раньше этот перевод делали трое: экран, шторка и автомат, каждый по-своему,
     * и «Комната» в шторке могла стоять рядом с «Комната не отвечает» на круге.
     */
    private fun noteRoom() {
        when (val state = OlcRtcCore.state) {
            is OlcRtcCore.State.Starting -> PathRegistry.raising(PathId.ROOM)

            is OlcRtcCore.State.Ready -> when (val health = OlcRtcCore.health) {
                is OlcRtcCore.Health.Live -> PathRegistry.alive(PathId.ROOM, health.latencyMs)
                is OlcRtcCore.Health.Dead -> PathRegistry.dead(PathId.ROOM, health.reason)
                // Ядро встало, а присмотр ещё не мерил: это не отказ, комната поднимается.
                OlcRtcCore.Health.Unknown -> PathRegistry.raising(PathId.ROOM)
            }

            is OlcRtcCore.State.Failed -> PathRegistry.dead(PathId.ROOM, state.reason)
            OlcRtcCore.State.Unavailable -> PathRegistry.unavailable(PathId.ROOM, "ядра комнаты в сборке нет")
            OlcRtcCore.State.Idle -> PathRegistry.unchecked(PathId.ROOM, "комнату не поднимали")
        }
    }

    /**
     * Пробный подъём комнаты.
     *
     * Качелей тут нет по трём причинам, и все три нужны:
     *  1. поднимаем не по первому провалу основного канала, а по [ROOM_TRIAL_AFTER]-му подряд —
     *     значит одиночная просадка видеозвонок не будит;
     *  2. гасим не по наблюдению, а по решению задвижки ([apply] зовётся только на смене
     *     обстановки или на её подтверждении) — значит мигание основного канала не роняет
     *     уже поднятую комнату;
     *  3. неудачная попытка удваивает паузу до [ROOM_TRIAL_PAUSE_CAP_MILLIS] — значит
     *     комната, которая не встаёт вообще, не съедает батарею попытками.
     */
    private fun trialRaiseRoom(host: Host) {
        if (roomUp()) return
        if (layout.room == null || !OlcRtcParams.roomAllowed) return
        val now = SystemClock.elapsedRealtime()
        if (roomTriedAt != 0L && now - roomTriedAt < roomTrialPause) return
        roomTriedAt = now
        PathRegistry.raising(PathId.ROOM)
        Log.i(TAG, "основной канал не поднимается $mainFailures заход(а) подряд — пробую поднять комнату")
        val changed = runCatching { host.setRoomWanted(true, "проверяю, встаёт ли комната") }
            .getOrElse {
                Log.w(TAG, "комнату поднять не вышло: ${it.message}")
                false
            }
        if (changed) selected = null
        // Удалась попытка или нет — спрашиваем у самого ядра. Раньше спрашивали
        // [roomAlive], а он ждёт ещё и вердикта присмотра: тот меряет раз в пять секунд,
        // и под нагрузкой ответ приходит за 2-9 с (замер 07.08.2026). Поднявшаяся комната
        // успевала посчитаться невставшей, и пауза до следующей попытки удваивалась
        // вплоть до десяти минут — на пустом месте.
        if (roomUp()) {
            roomTrialPause = ROOM_TRIAL_PAUSE_MILLIS
            Log.i(TAG, "комната поднялась, жду вердикта присмотра")
        } else {
            roomTrialPause = (roomTrialPause * 2).coerceAtMost(ROOM_TRIAL_PAUSE_CAP_MILLIS)
            // Остывание помнит реестр, но распоряжается им по-прежнему [roomTrialPause]:
            // решения из общей памяти на этом шаге не принимает никто.
            PathRegistry.coolDown(PathId.ROOM, System.currentTimeMillis() + roomTrialPause)
            Log.i(TAG, "комната не встала, следующая попытка не раньше чем через ${roomTrialPause / 1000} с")
        }
    }

    /**
     * Собственно решение. Вынесено отдельно и без единого обращения к Android, чтобы
     * таблицу «что видим → куда идём» можно было проверить, а не пересказать.
     */
    internal fun decide(hasNetwork: Boolean, home: Boolean, main: Boolean, room: Boolean): Situation = when {
        !hasNetwork -> Situation.NoNetwork
        home -> Situation.Home
        main -> Situation.Main
        room -> Situation.Room
        else -> Situation.Searching
    }

    private fun apply(host: Host, situation: Situation, repeat: Boolean) {
        when (situation) {
            Situation.Home -> {
                // Гашение туннеля снимает и ядро комнаты — отдельно просить не надо.
                if (!repeat && host.suspendTunnel("дома обход делает роутер")) selected = null
            }

            Situation.Main -> {
                // Ядро поднялось заново — выбор внутри селектора вернулся к тому,
                // что записано в конфиге, и его надо выставить снова.
                if (host.resumeTunnel("основной канал поднимается")) selected = null
                // Ушли на основной канал — видеозвонок больше не нужен. Держать его
                // «на всякий случай» нельзя: круглосуточная сессия и есть то, чего
                // вся схема избегает.
                if (setRoom(host, false, "идём основным каналом")) selected = null
                choose(host, layout.main)
            }

            Situation.Room -> {
                if (host.resumeTunnel("основной канал не поднимается")) selected = null
                // Обычно комната уже поднята пробно — вызов идемпотентный и ничего не делает.
                if (setRoom(host, true, "уходим в комнату")) selected = null
                choose(host, layout.room)
            }

            Situation.Searching -> {
                // Ничего не поднимается, но сеть есть: туннель оставляем поднятым —
                // ядро само продолжает пробовать, а мы просто честно это показываем.
                if (host.resumeTunnel("проверяю, что поднимется")) selected = null
                // Комнату при этом НЕ гасим, пока она встаёт. Пробный подъём занимает
                // секунды, а «ничего не поднимается» повторяется каждые 12 — раньше
                // повторный заход гасил комнату ровно в тот момент, когда она поднималась,
                // и автомат навсегда оставался в «ищу путь» (поймано 06.08.2026).
                // Гасим либо по решению задвижки, либо когда присмотр уже вынес приговор.
                val judged = OlcRtcCore.health is OlcRtcCore.Health.Dead
                if (!roomSettling() && (!repeat || judged) &&
                    setRoom(host, false, "комната канала не дала")
                ) {
                    selected = null
                }
            }

            // Сети нет и обстановка неизвестна: не поднимаем ничего. Комнату при этом
            // гасим — без сети она всё равно мертва, а присмотр за ней будет впустую
            // долбиться в подъём. Поднимется сеть — придёт событие, и заход случится сам.
            Situation.NoNetwork -> if (setRoom(host, false, "сети нет")) selected = null

            Situation.Unknown -> Unit
        }
    }

    /** @return true, если ядро комнаты действительно переключили (и sing-box пересобран). */
    private fun setRoom(host: Host, wanted: Boolean, reason: String): Boolean =
        runCatching { host.setRoomWanted(wanted, reason) }
            .getOrElse {
                Log.w(TAG, "комнату переключить не вышло ($reason): ${it.message}")
                false
            }

    /** Какой выход соответствует обстановке. Нужен, когда [selected] стёрт пересборкой ядра. */
    private fun exitFor(situation: Situation): String? = when (situation) {
        Situation.Room -> layout.room
        Situation.Main -> layout.main
        else -> null
    }

    private fun choose(host: Host, tag: String?) {
        val group = layout.chooser ?: return
        val target = tag ?: return
        if (selected == target) return
        runCatching { host.selectExit(group, target) }
            .onSuccess {
                selected = target
                Log.i(TAG, "выход переключён на «$target»")
            }
            .onFailure { Log.w(TAG, "не удалось переключить выход на «$target»: ${it.message}") }
    }

    private fun delayFor(situation: Situation): Long = when (situation) {
        Situation.Home -> ROUND_HOME_MILLIS
        Situation.Main -> ROUND_MAIN_MILLIS
        Situation.Room -> ROUND_ROOM_MILLIS
        // Сети нет — таймера нет вообще, ждём события от системы.
        Situation.NoNetwork -> Long.MAX_VALUE
        Situation.Searching, Situation.Unknown -> {
            searchingRounds++
            val grown = ROUND_SEARCHING_MILLIS shl (searchingRounds - 1).coerceIn(0, 5)
            grown.coerceAtMost(ROUND_SEARCHING_CAP_MILLIS)
        }
    }.also { if (situation != Situation.Searching && situation != Situation.Unknown) searchingRounds = 0 }

    private fun waitNext(millis: Long) {
        synchronized(lock) {
            if (!active) return
            try {
                // wait(0) — это «ждать вечно», ровно то, что нужно при отсутствии сети.
                lock.wait(if (millis == Long.MAX_VALUE) 0 else millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                active = false
            }
        }
    }

    // ------------------------------------------------------------------ пробы

    /**
     * Физическая сеть — та, что под нашим туннелем. Именно её резолвер и её маршруты
     * отвечают на вопрос «что вокруг», поэтому VPN-сети отбрасываем.
     *
     * Спрашиваем в первую очередь **систему**, какую сеть она сама считает основной.
     * Перебор всех сетей — только запасной путь, и не зря: когда телефон возвращается
     * домой, мобильная сеть не исчезает в ту же секунду, а висит рядом с вайфаем ещё
     * до полуминуты, тоже проверенная. Перебор в такой момент честно находит проверенную
     * сеть — только не ту: пробы уходили в мобильную, домашний резолвер никто не
     * спрашивал, и «дома» не наступало, пока мобильную не погасят. Система же знает,
     * какая сеть стала основной, сразу.
     */
    private fun physicalNetwork(): Network? = systemDefault() ?: anyPhysical()

    /** Сеть, которую основной считает система. null — она VPN или её нет. */
    private fun systemDefault(): Network? {
        DefaultNetworkMonitor.defaultNetwork?.takeIf(::physical)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { Application.connectivity.activeNetwork }.getOrNull()
                ?.takeIf(::physical)
                ?.let { return it }
        }
        return null
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
            // Проверенная системой сеть лучше просто присутствующей: вайфай без интернета
            // (портал в кафе) не должен выглядеть рабочей сетью.
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network
            if (fallback == null) fallback = network
        }
        return fallback
    }

    /** Годится ли сеть на роль «того, что вокруг»: не наш туннель и вообще про интернет. */
    private fun physical(network: Network): Boolean {
        val caps = runCatching { Application.connectivity.getNetworkCapabilities(network) }.getOrNull()
            ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun validated(network: Network): Boolean =
        runCatching { Application.connectivity.getNetworkCapabilities(network) }.getOrNull()
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    /** Как назвать сеть в логе: без этого «сеть сменилась» ничего не говорит. */
    private fun describe(network: Network?): String {
        if (network == null) return "сети нет"
        val caps = runCatching { Application.connectivity.getNetworkCapabilities(network) }.getOrNull()
            ?: return "$network (о ней ничего не известно)"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "вайфай"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "кабель"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "какая-то"
        }
        val checked = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "проверена"
        } else {
            "ещё не проверена"
        }
        return "$transport $network, $checked"
    }

    /**
     * Дома ли мы: спрашиваем системный резолвер физической сети про домены, которые
     * роутер точно заворачивает, и смотрим, попал ли ответ в подменный диапазон.
     */
    private fun homeBypass(network: Network): Boolean {
        val pool = Executors.newFixedThreadPool(HOME_DOMAINS.size + 1)
        try {
            val probes: List<Pair<String, Future<Boolean>>> =
                (HOME_DOMAINS + HOME_CONTROL).map { host ->
                    host to pool.submit<Boolean> { resolvesToFakeIp(network, host) }
                }
            val deadline = SystemClock.elapsedRealtime() + DNS_BUDGET_MILLIS
            var hits = 0
            var controlFake = false
            for ((host, future) in probes) {
                val left = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val fake = runCatching { future.get(left, TimeUnit.MILLISECONDS) }.getOrElse { false }
                if (host == HOME_CONTROL) controlFake = fake else if (fake) hits++
            }
            val home = hits >= HOME_HITS && !controlFake
            Log.i(
                TAG,
                "проба дома по сети «${describe(network)}»: подменных $hits из ${HOME_DOMAINS.size}, " +
                    "контрольный $HOME_CONTROL ${if (controlFake) "тоже подменён" else "настоящий"} → " +
                    if (home) "дома" else "не дома",
            )
            return home
        } finally {
            pool.shutdownNow()
        }
    }

    private fun resolvesToFakeIp(network: Network, host: String): Boolean =
        HomeProbe.addresses(network, host, DNS_BUDGET_MILLIS).any { address ->
            val bytes = address.address
            if (bytes.size != 4) return@any false
            var value = 0L
            for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
            value in FAKE_IP_FIRST..FAKE_IP_LAST
        }

    /**
     * Работает ли основной канал. Две пробы, и вторая — главная.
     *
     *  1. **Порт.** TCP-соединение до адреса узла. Дешёвое, одно RTT, и закрывает самый
     *     частый случай: при белом списке адрес недостижим на уровне маршрутизации,
     *     соединение не встаёт вообще. Порт молчит — дальше смотреть незачем.
     *  2. **Трафик.** Запрос через локальный вход самого sing-box ([ProxyProbe]).
     *     Нужна потому, что ТСПУ душит не установку соединения, а транспорт: порт
     *     ответит, а данные не пойдут — и одной первой пробы автомат бы не заметил.
     *
     * Проба идёт общими маршрутами, то есть через тот выход, который выбран прямо сейчас.
     * Значит, спрашивать «жив ли основной» ею можно только когда выбран основной — этим
     * и занимается [probeThroughMain].
     */
    private fun mainWorks(network: Network, host: Host): Boolean {
        lastMainProbe = null
        val endpoints = layout.mainEndpoints
        val portOpen = if (endpoints.isEmpty()) {
            // Раскладку не поняли — не выдумываем отказ: гнать всех в комнату из-за
            // непрочитанного конфига хуже, чем не заметить белый список.
            null
        } else {
            endpoints.any { connects(network, it) }.also { open ->
                Log.i(
                    TAG,
                    if (open) {
                        "основной канал: адрес узла принимает соединение"
                    } else {
                        "основной канал: ни один из ${endpoints.size} адресов не отвечает"
                    },
                )
            }
        }
        if (portOpen == false) return noteMain(mainVerdict(portOpen = false, trafficFlows = null), portOpen = false)

        val proxy = layout.localProxy
        val trafficFlows = when {
            proxy == null -> null
            // Туннель погашен (мы дома или только что оттуда ушли) — локального входа
            // просто нет, спрашивать некого. Следующий заход спросит уже по-честному.
            !host.tunnelLive() -> null
            else -> probeThroughMain(host, proxy)
        }
        return noteMain(mainVerdict(portOpen, trafficFlows), portOpen)
    }

    /**
     * Теневая запись про основной канал. Вердикт [works] уже вынесен и не меняется —
     * здесь только записывается, откуда он взялся.
     *
     * Разница между «мерили» и «догадались» тут не косметическая: честная проба видит
     * весь путь, а открытый порт — одно рукопожатие. Человеку показываем то, что есть,
     * не выдавая догадку за замер.
     */
    private fun noteMain(works: Boolean, portOpen: Boolean?): Boolean {
        when (val probe = lastMainProbe) {
            is ProxyProbe.Result.Live -> PathRegistry.alive(PathId.MAIN, probe.latencyMs)
            is ProxyProbe.Result.Dead -> PathRegistry.dead(PathId.MAIN, probe.reason)
            null -> when (portOpen) {
                false -> PathRegistry.dead(PathId.MAIN, "адрес узла не отвечает", Evidence.Hint)
                true -> PathRegistry.alive(PathId.MAIN, evidence = Evidence.Hint)
                // Раскладку не поняли: отказ не выдумываем, но и замером это не назовёшь.
                null -> PathRegistry.alive(
                    PathId.MAIN,
                    evidence = Evidence.Never,
                    reason = "про канал не известно ничего",
                )
            }
        }
        return works
    }

    /**
     * Итог по двум пробам. Вынесено отдельно и без обращений к Android, чтобы главное
     * утверждение — «порт отвечает, а трафика нет, значит канал мёртв» — можно было
     * проверить, а не пересказать.
     *
     * @param portOpen хоть один адрес узла принял соединение; `null` — адресов в конфиге нет.
     * @param trafficFlows через канал прошёл запрос и вернулся ответ; `null` — честной пробы
     *   не было (нет локального входа в конфиге или туннель сейчас погашен).
     */
    internal fun mainVerdict(portOpen: Boolean?, trafficFlows: Boolean?): Boolean = when {
        // Порт молчит — до трафика дело не дойдёт, и честную пробу мы даже не звали.
        portOpen == false -> false
        // Честная проба главнее: она видит весь путь, а не только рукопожатие.
        trafficFlows != null -> trafficFlows
        // Честной пробы нет — остаётся верить порту, как до правки.
        portOpen != null -> portOpen
        // Про канал не известно ничего. Отказ не выдумываем.
        else -> true
    }

    /**
     * Честная проба именно основного канала.
     *
     * Локальный вход sing-box уводит запрос в тот выход, который выбран сейчас. Пока мы
     * стоим на основном канале, это ровно то, что нужно. Пока стоим в комнате — надо
     * на время пробы переставить селектор на основной и вернуть обратно, если он не ожил.
     *
     * Цена перестановки маленькая и платится редко: до неё дело доходит только когда порт
     * узла уже отвечает (при белом списке не отвечает никогда), а существующие соединения
     * селектор не рвёт — на другой выход уходят только новые.
     */
    private fun probeThroughMain(host: Host, proxy: AutoModeExits.Endpoint): Boolean {
        val group = layout.chooser
        val main = layout.main

        // Куда вернуть выбор, если основной так и не ожил. null — возвращать некуда:
        // либо переключать нечем, либо мы и так стоим на основном.
        var restore: String? = null
        if (group != null && main != null && selected != main) {
            // Пересборка ядра (её делает подъём комнаты) стирает [selected], и тогда
            // возвращать было некуда: селектор оставался на мёртвом основном канале,
            // хотя стояли мы в комнате. Знать, где стоим, можно и без [selected] —
            // это выход текущей обстановки.
            restore = (selected ?: exitFor(gate.current))?.takeIf { it != main }
            runCatching { host.selectExit(group, main) }
                .onSuccess {
                    selected = main
                    Log.i(TAG, "на время пробы переставил выход на «$main»")
                }
                .onFailure { Log.w(TAG, "выход на «$main» переставить не вышло: ${it.message}") }
        }

        val result = ProxyProbe.through(proxy, PROBE_HOST, PROBE_PORT, PROBE_TIMEOUT_MILLIS)
        ProxyProbe.log("основной канал", result)
        // Итог пробы забирает [noteMain]: задержку и причину знает только она, а вердикт
        // выносится выше и от записи не зависит.
        lastMainProbe = result
        val alive = result is ProxyProbe.Result.Live

        if (!alive && restore != null && group != null) {
            runCatching { host.selectExit(group, restore) }
                .onSuccess {
                    selected = restore
                    Log.i(TAG, "основной канал не ожил — выход вернул на «$restore»")
                }
                .onFailure { Log.w(TAG, "выход вернуть на «$restore» не вышло: ${it.message}") }
        }
        return alive
    }

    private fun connects(network: Network, endpoint: AutoModeExits.Endpoint): Boolean = runCatching {
        val address = network.getAllByName(endpoint.host).firstOrNull() ?: return false
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, endpoint.port), TCP_TIMEOUT_MILLIS)
            true
        }
    }.getOrElse { false }

    /**
     * Идёт ли через комнату трафик. Своей пробы не заводим: её владелец один —
     * [OlcRtcWatchdog]. Тумблер тут не спрашиваем: живое ядро живо независимо от него,
     * а сам тумблер — аварийный выключатель и работает там, где комнату поднимают.
     */
    private fun roomAlive(): Boolean = OlcRtcCore.state is OlcRtcCore.State.Ready &&
        OlcRtcCore.health is OlcRtcCore.Health.Live

    /** Поднято ли ядро комнаты. Ответ самого ядра, без ожидания вердикта присмотра. */
    private fun roomUp(): Boolean = OlcRtcCore.state is OlcRtcCore.State.Ready

    /**
     * Комната ещё не сказала своего слова: либо поднимается, либо поднялась, но присмотр
     * её пока не мерил. Гасить в этот момент нельзя — это и есть убийство собственной пробы.
     */
    private fun roomSettling(): Boolean = OlcRtcCore.state is OlcRtcCore.State.Starting ||
        (OlcRtcCore.state is OlcRtcCore.State.Ready && OlcRtcCore.health is OlcRtcCore.Health.Unknown)

    private fun name(situation: Situation): String = when (situation) {
        Situation.Home -> "дома"
        Situation.Main -> "основной канал"
        Situation.Room -> "комната"
        Situation.Searching -> "ничего не поднимается"
        Situation.NoNetwork -> "сети нет"
        Situation.Unknown -> "неизвестно"
    }
}
