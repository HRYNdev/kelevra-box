package io.nekohasekai.sfa.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.net.Inet4Address
import java.util.UUID

/**
 * Откуда берутся параметры комнаты.
 *
 * Основной источник — сервер: он отдаёт блок `olcrtc` в /k/<код>/info рядом с
 * `bypass_packages`, и человеку ничего вбивать не надо. Служебный экран остаётся
 * переопределением: заполненное поле перебивает серверное значение, пустое — нет.
 *
 * Токен WB ходит по тому же правилу, что и остальные поля: сервер отдаёт его в том же
 * блоке (только владельцу кода), ручное значение перебивает серверное. Единственная
 * разница — в лог он не попадает даже частями.
 */
object OlcRtcParams {
    private const val TAG = "OlcRtcParams"

    private const val DEFAULT_CARRIER = "wbstream"
    private const val DEFAULT_TRANSPORT = "vp8channel"
    private const val DEFAULT_SOCKS_PORT = 8808
    private const val DEFAULT_VP8_FPS = 30
    private const val DEFAULT_VP8_BATCH = 64

    enum class Source {
        /** Ничего нет: ни сервер не дал, ни человек не вбил. */
        None,

        Server,
        Manual,

        /** Часть с сервера, часть перебита руками. */
        Mixed,
    }

    /** Значение поля: что победило и чем. */
    private fun pick(manual: String, server: String, fallback: String): Pair<String, Source> = when {
        manual.isNotBlank() -> manual to Source.Manual
        server.isNotBlank() -> server to Source.Server
        else -> fallback to Source.None
    }

    private fun pick(manual: Int, server: Int, fallback: Int): Pair<Int, Source> = when {
        manual > 0 -> manual to Source.Manual
        server > 0 -> server to Source.Server
        else -> fallback to Source.None
    }

    /**
     * Имя устройства в комнате. Сервер его не раздаёт (в комнату заходят разные
     * телефоны одного человека), а совпадать они не должны — заводим своё и храним.
     */
    private fun deviceId(): String {
        Settings.olcrtcDeviceId.takeIf { it.isNotBlank() }?.let { return it }
        val generated = "kelevra-" + UUID.randomUUID().toString().take(8)
        Settings.olcrtcDeviceId = generated
        return generated
    }

    /** Есть ли вообще с чем стартовать: комната и ключ. */
    val hasRoom: Boolean
        get() = (Settings.olcrtcRoomId.ifBlank { Settings.olcrtcSrvRoomId }).isNotBlank() &&
            (Settings.olcrtcKeyHex.ifBlank { Settings.olcrtcSrvKeyHex }).isNotBlank()

    /** Отдал ли сервер блок olcrtc в последний раз, когда мы его читали. */
    val serverOffers: Boolean get() = Settings.olcrtcSrvAvailable

    /**
     * Можно ли поднимать комнату: параметры есть и аварийный выключатель не нажат.
     *
     * Одно место на всех — иначе подъём упирается в ту точку, которую забыли согласовать.
     */
    val roomAllowed: Boolean get() = Settings.olcrtcEnabled && hasRoom

    /** Откуда приехали параметры — для честной подписи на экране. */
    val source: Source
        get() {
            val sources = listOf(
                pick(Settings.olcrtcCarrier, Settings.olcrtcSrvCarrier, DEFAULT_CARRIER).second,
                pick(Settings.olcrtcRoomId, Settings.olcrtcSrvRoomId, "").second,
                pick(Settings.olcrtcKeyHex, Settings.olcrtcSrvKeyHex, "").second,
                pick(Settings.olcrtcTransport, Settings.olcrtcSrvTransport, DEFAULT_TRANSPORT).second,
                pick(Settings.olcrtcSocksPort, Settings.olcrtcSrvSocksPort, DEFAULT_SOCKS_PORT).second,
            )
            val manual = sources.count { it == Source.Manual }
            val server = sources.count { it == Source.Server }
            return when {
                manual > 0 && server > 0 -> Source.Mixed
                manual > 0 -> Source.Manual
                server > 0 -> Source.Server
                else -> Source.None
            }
        }

    /**
     * Откуда взялся токен WB — для подписи на служебном экране. Наружу отдаём только
     * происхождение и длину: сам токен не показываем и не логируем.
     */
    val wbTokenSource: Source
        get() = pick(Settings.olcrtcWbToken, Settings.olcrtcSrvWbToken, "").second

    val wbTokenLength: Int
        get() = pick(Settings.olcrtcWbToken, Settings.olcrtcSrvWbToken, "").first.length

    /** Порт SOCKS, на котором ядро поднимет выход: нужен и конфигу, и проверке связи. */
    val socksPort: Int
        get() = pick(Settings.olcrtcSocksPort, Settings.olcrtcSrvSocksPort, DEFAULT_SOCKS_PORT).first

    fun resolve(): OlcRtcCore.Params = OlcRtcCore.Params(
        carrier = pick(Settings.olcrtcCarrier, Settings.olcrtcSrvCarrier, DEFAULT_CARRIER).first,
        roomId = pick(Settings.olcrtcRoomId, Settings.olcrtcSrvRoomId, "").first,
        clientId = pick(Settings.olcrtcClientId, Settings.olcrtcSrvClientId, "").first
            .ifBlank { deviceId() },
        keyHex = pick(Settings.olcrtcKeyHex, Settings.olcrtcSrvKeyHex, "").first,
        transport = pick(Settings.olcrtcTransport, Settings.olcrtcSrvTransport, DEFAULT_TRANSPORT).first,
        socksPort = socksPort,
        wbToken = pick(Settings.olcrtcWbToken, Settings.olcrtcSrvWbToken, "").first,
        vp8Fps = pick(Settings.olcrtcVp8Fps, Settings.olcrtcSrvVp8Fps, DEFAULT_VP8_FPS).first,
        vp8BatchSize = pick(Settings.olcrtcVp8BatchSize, Settings.olcrtcSrvVp8BatchSize, DEFAULT_VP8_BATCH).first,
        dnsServer = physicalDns(),
    )

    /**
     * Резолвер той сети, что вокруг нас, — для ядра комнаты.
     *
     * Без него ядро спрашивает имена у системы, а при поднятом туннеле система ведёт
     * запрос внутрь туннеля. Под белым списком это замкнутый круг: комната — единственный
     * выход наружу, но поднять её нельзя, потому что имя `stream.wb.ru` не разрешается
     * через мёртвый туннель. Ровно так оно и легло у Вовы на МТС 10.08.2026:
     * `join room: dial tcp: lookup stream.wb.ru: i/o timeout`, три попытки подряд.
     *
     * Берём резолвер физической сети (у оператора он в белом списке всегда — иначе не
     * работал бы и его собственный портал) и отдаём ядру: дальше оно спрашивает само,
     * своим сокетом, а тот защищён от нашего tun через ProbeSocket.Protector.
     *
     * Только IPv4: строка уходит в ядро как `host:port`, а адрес IPv6 туда без скобок
     * не положить. Пусто — прежнее поведение, ядро идёт к системе.
     */
    private fun physicalDns(): String {
        // Перебор ВСЕХ сетей обязателен, и это не перестраховка: комната нужна ровно
        // тогда, когда туннель уже поднят, а при поднятом туннеле и `defaultNetwork`,
        // и `activeNetwork` — это он сам. Спрашивать только их значит всегда получать
        // пусто именно в тот момент, ради которого всё написано (проверено боем 10.08).
        val candidates = buildList {
            runCatching { DefaultNetworkMonitor.defaultNetwork }.getOrNull()?.let(::add)
            runCatching { Application.connectivity.activeNetwork }.getOrNull()?.let(::add)
            @Suppress("DEPRECATION")
            runCatching { Application.connectivity.allNetworks.toList() }.getOrNull()?.let(::addAll)
        }
        val address = candidates.asSequence()
            .filter(::physical)
            .mapNotNull { network ->
                runCatching {
                    Application.connectivity.getLinkProperties(network)?.dnsServers
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull()
                        ?.hostAddress
                }.getOrNull()
            }
            .firstOrNull()
        if (address == null) {
            Log.w(TAG, "резолвер физической сети не найден — ядро комнаты пойдёт к системному")
            return ""
        }
        Log.i(TAG, "ядру комнаты отдаём резолвер физической сети $address")
        return "$address:53"
    }

    /** Физическая — значит не наш собственный туннель: у него свой резолвер. */
    private fun physical(network: Network): Boolean = runCatching {
        Application.connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
    }.getOrElse { false }

    /**
     * Кладёт в настройки то, что пришло с сервера.
     *
     * `null` (сервер блок не отдал или выключил комнату) стирает серверные значения:
     * держать протухшую комнату вреднее, чем не иметь никакой. Ручные значения
     * при этом не трогаются — они и есть запасной вариант.
     */
    fun applyServer(json: JSONObject?) {
        if (json == null) {
            if (Settings.olcrtcSrvAvailable) {
                Settings.olcrtcSrvAvailable = false
                Settings.olcrtcSrvCarrier = ""
                Settings.olcrtcSrvRoomId = ""
                Settings.olcrtcSrvClientId = ""
                Settings.olcrtcSrvKeyHex = ""
                Settings.olcrtcSrvTransport = ""
                Settings.olcrtcSrvWbToken = ""
                Settings.olcrtcSrvSocksPort = 0
                Settings.olcrtcSrvVp8Fps = 0
                Settings.olcrtcSrvVp8BatchSize = 0
            }
            return
        }
        // Сервер впервые (или снова) даёт комнату — снимаем аварийный выключатель.
        // Лечит уже установленные копии: там в настройках лежит записанное «запрещено»
        // со времён, когда тумблер был разрешением, и автомат из-за него не мог уйти
        // в комнату вообще. Человека это не обходит: выключенный посреди сессии тумблер
        // так и остаётся выключенным, пока сервер не перестанет давать комнату и не даст
        // её заново.
        if (!Settings.olcrtcSrvAvailable && !Settings.olcrtcEnabled) {
            Settings.olcrtcEnabled = true
        }
        val vp8 = json.optJSONObject("vp8")
        Settings.olcrtcSrvCarrier = json.optString("carrier")
        Settings.olcrtcSrvRoomId = json.optString("room_id")
        Settings.olcrtcSrvClientId = json.optString("client_id")
        Settings.olcrtcSrvKeyHex = json.optString("key_hex")
        Settings.olcrtcSrvTransport = json.optString("transport")
        // Токен приезжает только владельцу кода. Блок без него — не повод трогать
        // ручной токен: он лежит отдельным полем и остаётся запасным вариантом.
        Settings.olcrtcSrvWbToken = json.optString("token")
        Settings.olcrtcSrvSocksPort = json.optInt("socks_port")
        Settings.olcrtcSrvVp8Fps = vp8?.optInt("fps") ?: 0
        Settings.olcrtcSrvVp8BatchSize = vp8?.optInt("batch_size") ?: 0
        Settings.olcrtcSrvAvailable = true
    }
}
