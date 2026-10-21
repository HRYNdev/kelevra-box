package io.nekohasekai.sfa.bg.pult

/**
 * Разбор base64 (обычный и url-safe, с дополнением и без). `java.util.Base64` есть только
 * с API 26, `android.util.Base64` нет в JVM-тестах — поэтому своё, на десяток строк.
 */
internal object PultBase64 {
    private val TABLICA = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62
        t['_'.code] = 63
    }

    /** null — строка не base64. */
    fun razobrat(tekst: String): ByteArray? {
        val chistyy = tekst.filter { !it.isWhitespace() }.trimEnd('=')
        if (chistyy.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(chistyy.length * 3 / 4)
        var bufer = 0
        var bit = 0
        for (c in chistyy) {
            val v = if (c.code < 128) TABLICA[c.code] else -1
            if (v < 0) return null
            bufer = (bufer shl 6) or v
            bit += 6
            if (bit >= 8) {
                bit -= 8
                out.write((bufer shr bit) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private const val ALFAVIT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Обычный base64 с дополнением — нужен тестам и сборке пробных пультов. */
    fun zakodirovat(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            append(ALFAVIT[b0 shr 2])
            append(ALFAVIT[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            append(if (b1 >= 0) ALFAVIT[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            append(if (b2 >= 0) ALFAVIT[b2 and 63] else '=')
            i += 3
        }
    }
}
