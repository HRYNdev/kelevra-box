package io.nekohasekai.sfa.bg

import org.json.JSONArray
import org.json.JSONObject

/**
 * Вход домой в конфиге ядра — то же, что Desktop `internal/konfig/dom.go`.
 *
 * Дома стоит хаб WireGuard (узел «Дом», LXC 227). Телефон поднимает к нему эндпоинт «Дом» (sing-box
 * 1.14, `endpoints`), и его UDP уходит не напрямую, а через выход профиля на вход VPS с порталом
 * (`detour`): там UDP на мост уходит в обратный канал домой, VPS видит только шифр WireGuard.
 * В конфиг идут три вещи:
 *  1. эндпоинт wireguard «Дом» — закрытый ключ телефона, пир — хаб дома, endpoint — мост;
 *  2. правило «сети дома → Дом» первым после ведущих sniff (раньше hijack-dns и `ip_is_private`) и сразу за
 *     ним «своя подсеть → reject» для всего, что пришло не из своего tun;
 *  3. DNS: зона (`dom`) — серверу дома через «Дом», первым правилом.
 *
 * Дома ядра нет вовсе (туннель погашен, [BoxService.suspendTunnel]) — дом виден по LAN напрямую.
 * Только в памяти, как остальные правки ([BoxService.effectiveConfig]); блок не годен — конфиг как был.
 */
object VhodDomoyPatch {
    const val TEG = "Дом"
    const val TEG_MOSTA = "Дом · мост"
    const val TEG_DNS = "dns-dom"
    const val KEEPALIVE = 25

    data class Vhod(val zakrytyy: String, val blok: VhodDomoyBlok)

    data class Result(val content: String, val note: String, val patched: Boolean)

    fun apply(content: String, vhod: Vhod): Result = runCatching { patch(content, vhod) }.getOrElse {
        Result(content, "вход домой: правка не легла (${it.javaClass.simpleName}), конфиг без «Дома»", false)
    }

    private fun patch(content: String, v: Vhod): Result {
        val b = v.blok
        if (b.otozvan || v.zakrytyy.isBlank()) return Result(content, "вход домой: блока нет", false)
        val root = JSONObject(content)
        if (tegZanyat(root, TEG) || tegZanyat(root, TEG_MOSTA)) {
            return Result(content, "вход домой: тег «$TEG» в профиле уже занят — без «Дома»", false)
        }
        val route = root.optJSONObject("route") ?: return Result(content, "вход домой: в профиле нет route", false)
        val tun = tegTuna(root)
        val (detour, most) = vyhodMosta(root, b.portaly)
            ?: return Result(content, "вход домой: в профиле нет выхода для моста — без «Дома»", false)

        val peer = JSONObject()
            .put("address", b.mostHost)
            .put("port", b.mostPort)
            .put("public_key", b.uzelPub)
            .put("allowed_ips", JSONArray(b.seti))
            .put("persistent_keepalive_interval", KEEPALIVE)
        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", TEG)
            .put("mtu", b.mtu)
            .put("address", JSONArray(listOf(b.adres)))
            .put("private_key", v.zakrytyy)
            .put("peers", JSONArray().put(peer))
            .put("detour", detour)
        val endpoints = root.optJSONArray("endpoints") ?: JSONArray().also { root.put("endpoints", it) }
        endpoints.put(endpoint)

        val rules = route.optJSONArray("rules") ?: JSONArray()
        var vstavka = 0
        while (vstavka < rules.length()) {
            val r = rules.optJSONObject(vstavka) ?: break
            if (r.optString("action") == "sniff" && r.length() == 1) vstavka++ else break
        }
        val svoe = JSONObject().put("ip_cidr", JSONArray(b.seti)).put("outbound", TEG)
        if (tun != null) svoe.put("inbound", JSONArray(listOf(tun)))
        val chuzhoe = JSONObject().put("ip_cidr", JSONArray(listOf(VhodDomoyBlok.SVOYA_SET))).put("action", "reject")
        val novye = JSONArray()
        for (i in 0 until vstavka) novye.put(rules.get(i))
        novye.put(svoe).put(chuzhoe)
        for (i in vstavka until rules.length()) novye.put(rules.get(i))
        route.put("rules", novye)

        val dns = root.optJSONObject("dns")
        val servery = dns?.optJSONArray("servers")
        var dnsNote = "DNS зоны не вписан (в профиле нет dns.servers)"
        if (dns != null && servery != null && servery.length() > 0) {
            servery.put(
                JSONObject().put("type", "udp").put("tag", TEG_DNS).put("server", b.dns)
                    .put("server_port", 53).put("detour", TEG),
            )
            val dr = dns.optJSONArray("rules") ?: JSONArray()
            val pr = JSONObject().put("domain_suffix", JSONArray(listOf(b.zona))).put("server", TEG_DNS)
            if (tun != null) pr.put("inbound", JSONArray(listOf(tun)))
            val nov = JSONArray().put(pr)
            for (i in 0 until dr.length()) nov.put(dr.get(i))
            dns.put("rules", nov)
            dnsNote = "зона .${b.zona} → ${b.dns}"
        }
        return Result(root.toString(), "вход домой: «$TEG» ${b.adres}, $most; $dnsNote", true)
    }

    /**
     * Через что пустить UDP моста: выходы VLESS профиля ровно на входы с порталом (порядок блока, без
     * повторов). Один — прямо в него, несколько — группа [TEG_MOSTA] (urltest, сама по себе, с выбором
     * человека для интернета не связана). Ни одного — «Соединение», как Desktop до поля `portaly`.
     */
    internal fun vyhodMosta(root: JSONObject, portaly: List<VhodDomoyBlok.Portal>): Pair<String, String>? {
        val vyhody = root.optJSONArray("outbounds") ?: return null
        val tegi = mutableListOf<String>()
        for (p in portaly) {
            for (i in 0 until vyhody.length()) {
                val o = vyhody.optJSONObject(i) ?: continue
                val teg = o.optString("tag")
                val server = o.optString("server").trim().trimEnd('.')
                if (o.optString("type") != "vless" || teg.isBlank() || teg in tegi) continue
                if (o.optInt("server_port", -1) != p.port || !server.equals(p.server.trimEnd('.'), ignoreCase = true)) continue
                tegi += teg
            }
        }
        return when {
            tegi.size == 1 -> tegi[0] to "портал: ${tegi[0]}"
            tegi.size > 1 -> {
                vyhody.put(
                    JSONObject().put("type", "urltest").put("tag", TEG_MOSTA).put("outbounds", JSONArray(tegi))
                        .put("url", "https://www.gstatic.com/generate_204").put("interval", "1m").put("tolerance", 300)
                        // Сменился выход — сокет моста закрывается, WireGuard переоткрывает его через новый.
                        .put("interrupt_exist_connections", true),
                )
                TEG_MOSTA to "порталы: ${tegi.joinToString()}"
            }
            else -> {
                val soed = (0 until vyhody.length()).mapNotNull { vyhody.optJSONObject(it) }
                    .firstOrNull { it.optString("tag") == "Соединение" }?.optString("tag") ?: return null
                soed to (if (portaly.isEmpty()) "мост через $soed" else "входов с порталом в профиле нет, мост через $soed")
            }
        }
    }

    private fun tegTuna(root: JSONObject): String? {
        val vhody = root.optJSONArray("inbounds") ?: return null
        for (i in 0 until vhody.length()) {
            val v = vhody.optJSONObject(i) ?: continue
            if (v.optString("type") == "tun") return v.optString("tag").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun tegZanyat(root: JSONObject, teg: String): Boolean =
        listOf("outbounds", "endpoints").any { s ->
            val a = root.optJSONArray(s) ?: return@any false
            (0 until a.length()).any { a.optJSONObject(it)?.optString("tag") == teg }
        }
}
