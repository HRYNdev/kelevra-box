package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject

/**
 * Параметры второго носителя комнаты — WB Chat.
 *
 * Это отдельный выход рядом с Телемостом, а не его замена: сервер отдаёт блок `wbchat`
 * верхнего уровня в /info (не внутри `olcrtc`) только тем устройствам, кому он включён, и
 * сам кладёт в профиль sing-box socks-выход на 127.0.0.1:<socks_port> внутрь ручного
 * селектора. Ручных переопределений нет — всё приходит с сервера.
 *
 * Нет блока — всё про WB Chat стирается, и ядро не запускается: держать протухший звонок
 * вреднее, чем не иметь никакого. Сеть не ответила вовсе — параметры остаются как были
 * (то же правило, что у [OlcRtcParams.applyServer]).
 *
 * Токен, куки и ключ — секреты: в журнал не попадают ни целиком, ни частями.
 */
object WbChatParams {
    private const val TAG = "WbChatParams"

    data class Params(
        val socksPort: Int,
        val peer: String,
        val token: String,
        val cookies: String,
        val key: String,
        val bond: Int,
        val tag: String,
    ) {
        /** Без секретов — для журнала и сравнения «параметры сменились». */
        override fun toString(): String =
            "WbChatParams(port=$socksPort, peer=$peer, bond=$bond, tag=$tag, " +
                "token=${token.length} симв., cookies=${cookies.length} симв., key=${if (key.isBlank()) "нет" else "есть"})"
    }

    /** Есть ли с чем стартовать: сервер дал блок и в нём всё, без чего ядро не войдёт в звонок. */
    val available: Boolean
        get() = Settings.wbchatAvailable &&
            Settings.wbchatSocksPort in 1..65535 &&
            Settings.wbchatPeer.isNotBlank() &&
            Settings.wbchatToken.isNotBlank() &&
            Settings.wbchatCookies.isNotBlank()

    /** Порт SOCKS выхода WB Chat; 0 — выхода нет. Не совпадает с портом комнаты olcRTC/Телемоста. */
    val socksPort: Int get() = if (available) Settings.wbchatSocksPort else 0

    /** Тег выхода в профиле sing-box (с сервера, например «Комната WB»). */
    val tag: String get() = if (available) Settings.wbchatTag else ""

    /**
     * Выход [name] — это выход WB Chat. По тегу с сервера: имя «Комната WB» иначе ловится
     * угадыванием комнаты по корню слова, а комнатой для автомата этот выход не является.
     */
    fun isWbExit(name: String?): Boolean {
        val wanted = Settings.wbchatTag.takeIf { Settings.wbchatAvailable && it.isNotBlank() } ?: return false
        return name != null && name == wanted
    }

    fun resolve(): Params? {
        if (!available) return null
        return Params(
            socksPort = Settings.wbchatSocksPort,
            peer = Settings.wbchatPeer,
            token = Settings.wbchatToken,
            cookies = Settings.wbchatCookies,
            key = Settings.wbchatKey,
            bond = Settings.wbchatBond,
            tag = Settings.wbchatTag,
        )
    }

    /**
     * Кладёт в настройки блок `wbchat` из /info. `null` — блока нет: стираем всё.
     * Звать только когда ответ сервера пришёл (сеть не ответила — не звать).
     */
    fun applyServer(json: JSONObject?) {
        val port = json?.optInt("socks_port") ?: 0
        val peer = json?.optString("peer").orEmpty()
        val token = json?.optString("token").orEmpty()
        val cookies = json?.optString("cookies").orEmpty()
        if (json == null || port !in 1..65535 || peer.isBlank() || token.isBlank() || cookies.isBlank()) {
            if (Settings.wbchatAvailable || Settings.wbchatToken.isNotEmpty()) {
                Log.i(TAG, if (json == null) "сервер не отдал блок wbchat — параметры WB Chat стёрты" else "блок wbchat неполный — параметры WB Chat стёрты")
            }
            Settings.wbchatAvailable = false
            Settings.wbchatSocksPort = 0
            Settings.wbchatPeer = ""
            Settings.wbchatToken = ""
            Settings.wbchatCookies = ""
            Settings.wbchatKey = ""
            Settings.wbchatBond = 0
            Settings.wbchatTag = ""
            return
        }
        val was = resolve()
        Settings.wbchatSocksPort = port
        Settings.wbchatPeer = peer
        Settings.wbchatToken = token
        Settings.wbchatCookies = cookies
        Settings.wbchatKey = json.optString("key")
        Settings.wbchatBond = json.optInt("bond")
        Settings.wbchatTag = json.optString("tag")
        Settings.wbchatAvailable = true
        val now = resolve()
        if (was != now) Log.i(TAG, "параметры WB Chat с сервера: $now")
    }
}
