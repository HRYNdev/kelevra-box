package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Ed25519
import io.nekohasekai.sfa.bg.pult.PultBase64
import io.nekohasekai.sfa.bg.pult.PultYadro
import org.json.JSONArray
import org.json.JSONObject

/**
 * Блок `dom` из сводки `/info` (KelevraServer docs/VHOD-DOMOY-API.md, тот же договор, что у Desktop
 * `internal/dom/blok.go`): что мозг разрешил этому устройству — адрес в своей сети, ключ хаба дома,
 * мост, сети, DNS, входы VPS с порталом. Подписан ключом пульта со своей меткой; блок без верной
 * подписи не применяется никогда — он решает, куда уходит трафик к дому.
 *
 * Плоская форма: поля блока и рядом `podpis`; подписано `kelevra-dom-v1\n` + канонический JSON блока без
 * `podpis` (ключи по алфавиту, без пробелов, UTF-8 как есть — `json.dumps(sort_keys=True,
 * separators=(",", ":"), ensure_ascii=False)` мозга).
 */
data class VhodDomoyBlok(
    val adres: String,
    val uzelPub: String,
    val mostHost: String,
    val mostPort: Int,
    val seti: List<String>,
    val dns: String,
    val zona: String,
    val mtu: Int,
    val versiya: Long,
    val komu: String,
    val pub: String,
    val otozvan: Boolean,
    val portaly: List<Portal>,
) {
    data class Portal(val uzel: String, val server: String, val port: Int)

    sealed class Razbor {
        data class Ok(val blok: VhodDomoyBlok) : Razbor()

        /** Одно слово для журнала: podpis, format, komu, pub, adres, seti… */
        data class Otkaz(val prichina: String) : Razbor()
    }

    override fun toString(): String =
        if (otozvan) "отзыв ключа, версия $versiya"
        else "$adres, мост $mostHost:$mostPort, версия $versiya" +
            if (portaly.isEmpty()) "" else ", порталы " + portaly.joinToString { "${it.uzel} ${it.server}:${it.port}" }

    companion object {
        const val METKA = "kelevra-dom-v1\n"
        const val SVOYA_SET = "100.77.0.0/16"
        const val MAKS_PORTALOV = 16
        private val ZONA = Regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$")
        private val SERVER_PORTALA = Regex("^[A-Za-z0-9.:\\[\\]-]{1,253}$")

        /**
         * Проверить подпись и содержимое. [komu] — свой слот: блок другого слота (или без него) не наш;
         * пустой [komu] — не принимается никакой.
         */
        fun razobrat(syroe: String, komu: String, klyuchi: List<ByteArray> = PultYadro.KLYUCHI): Razbor {
            val j = runCatching { JSONObject(syroe) }.getOrNull() ?: return Razbor.Otkaz("format")
            val podpis = j.optString("podpis").takeIf { it.isNotBlank() }?.let { PultBase64.razobrat(it) }
                ?: return Razbor.Otkaz("podpis")
            if (podpis.size != 64) return Razbor.Otkaz("podpis")
            val kanon = kanonicheskiy(j, bez = "podpis") ?: return Razbor.Otkaz("format")
            val soobshchenie = (METKA + kanon).toByteArray(Charsets.UTF_8)
            if (klyuchi.none { Ed25519.proverit(it, soobshchenie, podpis) }) return Razbor.Otkaz("podpis")
            if (komu.isBlank() || j.optString("komu").trim() != komu) return Razbor.Otkaz("komu")
            val pub = j.optString("pub")
            if (PultBase64.razobrat(pub)?.size != 32) return Razbor.Otkaz("pub")
            val versiya = j.optLong("versiya", -1L)
            if (versiya < 0) return Razbor.Otkaz("versiya")
            if (j.optBoolean("otozvan", false)) {
                return Razbor.Ok(VhodDomoyBlok("", "", "", 0, emptyList(), "", "", 0, versiya, komu, pub, true, emptyList()))
            }
            val adres = j.optString("adres").trim()
            val a = Cidr.razobrat(adres)
            if (a == null || a.bits != 32 || !Cidr.SVOYA.soderzhit(a.adres)) return Razbor.Otkaz("adres")
            val uzelPub = j.optString("uzel_pub")
            if (PultBase64.razobrat(uzelPub)?.size != 32) return Razbor.Otkaz("uzel_pub")
            val most = j.optString("most").trim()
            val mostHost = most.substringBeforeLast(':', "")
            val mostPort = most.substringAfterLast(':', "").toIntOrNull() ?: 0
            if (mostHost.isBlank() || mostPort !in 1..65535) return Razbor.Otkaz("most")
            val setiJ = j.optJSONArray("seti") ?: return Razbor.Otkaz("seti")
            if (setiJ.length() == 0 || setiJ.length() > 32) return Razbor.Otkaz("seti")
            val seti = mutableListOf<String>()
            for (i in 0 until setiJ.length()) {
                val c = Cidr.razobrat(setiJ.optString(i).trim()) ?: return Razbor.Otkaz("seti")
                if (c.bits < 16 || !Cidr.SVOYA.soderzhit(c.adres)) return Razbor.Otkaz("seti")
                seti += c.toString()
            }
            val dns = j.optString("dns").trim()
            val dnsA = Cidr.ipv4(dns)
            if (dnsA == null || seti.none { Cidr.razobrat(it)!!.soderzhit(dnsA) }) return Razbor.Otkaz("dns")
            val zona = j.optString("zona").trim().lowercase()
            if (!ZONA.matches(zona)) return Razbor.Otkaz("zona")
            val mtu = j.optInt("mtu", 0)
            if (mtu != 0 && mtu !in 576..1420) return Razbor.Otkaz("mtu")
            val portaly = mutableListOf<Portal>()
            val pj = j.optJSONArray("portaly") ?: JSONArray()
            if (pj.length() > MAKS_PORTALOV) return Razbor.Otkaz("portaly")
            for (i in 0 until pj.length()) {
                val p = pj.optJSONObject(i) ?: return Razbor.Otkaz("portaly")
                val server = p.optString("server").trim()
                val port = p.optInt("port", 0)
                val uzel = p.optString("uzel")
                if (!SERVER_PORTALA.matches(server) || port !in 1..65535 || uzel.length > 64) return Razbor.Otkaz("portaly")
                portaly += Portal(uzel, server, port)
            }
            return Razbor.Ok(
                VhodDomoyBlok(
                    adres = a.toString(), uzelPub = uzelPub, mostHost = mostHost, mostPort = mostPort, seti = seti,
                    dns = dns, zona = zona, mtu = if (mtu == 0) 1280 else mtu, versiya = versiya, komu = komu, pub = pub,
                    otozvan = false, portaly = portaly,
                ),
            )
        }

        /**
         * Канонический JSON объекта без поля [bez] — как его подписал мозг. Числа — только целые
         * (в блоке других нет): дробное число — null, и подпись не сойдётся честно, а не случайно.
         */
        fun kanonicheskiy(o: JSONObject, bez: String? = null): String? = runCatching {
            buildString { pisat(o, this, bez) }
        }.getOrNull()

        private fun pisat(v: Any?, sb: StringBuilder, bez: String? = null) {
            when (v) {
                null, JSONObject.NULL -> sb.append("null")
                is JSONObject -> {
                    sb.append('{')
                    val klyuchi = v.keys().asSequence().filter { it != bez }.sorted().toList()
                    klyuchi.forEachIndexed { i, k ->
                        if (i > 0) sb.append(',')
                        stroka(k, sb)
                        sb.append(':')
                        pisat(v.opt(k), sb)
                    }
                    sb.append('}')
                }
                is JSONArray -> {
                    sb.append('[')
                    for (i in 0 until v.length()) {
                        if (i > 0) sb.append(',')
                        pisat(v.opt(i), sb)
                    }
                    sb.append(']')
                }
                is String -> stroka(v, sb)
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long, is Short, is Byte -> sb.append(v.toString())
                is java.math.BigInteger -> sb.append(v.toString())
                is java.math.BigDecimal -> {
                    val c = runCatching { v.toBigIntegerExact() }.getOrNull() ?: error("дробное")
                    sb.append(c.toString())
                }
                else -> error("тип ${v.javaClass.simpleName}")
            }
        }

        /** Строка как у json.dumps(ensure_ascii=False): экранируются только кавычка, обратная черта и управляющие. */
        private fun stroka(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }

    /** IPv4-подсеть для проверок блока (без системных классов: JVM-тесты). */
    data class Cidr(val adres: Long, val bits: Int) {
        fun soderzhit(a: Long): Boolean {
            val maska = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            return (a and maska) == (adres and maska)
        }

        override fun toString(): String {
            val maska = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            val n = adres and maska
            return "${n shr 24 and 255}.${n shr 16 and 255}.${n shr 8 and 255}.${n and 255}/$bits"
        }

        companion object {
            val SVOYA: Cidr = razobrat(VhodDomoyBlok.SVOYA_SET)!!

            fun ipv4(s: String): Long? {
                val ch = s.split('.')
                if (ch.size != 4) return null
                var v = 0L
                for (x in ch) {
                    if (x.isEmpty() || x.length > 3 || !x.all { it.isDigit() }) return null
                    val n = x.toInt()
                    if (n > 255) return null
                    v = (v shl 8) or n.toLong()
                }
                return v
            }

            fun razobrat(s: String): Cidr? {
                val adres = ipv4(s.substringBefore('/')) ?: return null
                val bits = if ('/' in s) s.substringAfter('/').toIntOrNull() ?: return null else 32
                if (bits !in 0..32) return null
                return Cidr(adres, bits)
            }
        }
    }
}
