package io.nekohasekai.sfa.bg

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) — ключ WireGuard входа домой ([VhodDomoy]).
 *
 * Своё, как [io.nekohasekai.sfa.bg.pult.Ed25519]: «XDH» в системе есть только с Android 13, а Box
 * ставится с Android 7. Считается один раз на рождение ключа, скорость не важна; ядру sing-box
 * нужен только закрытый ключ, открытый уходит в мозг и сверяется с блоком `dom`.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BAZA = ByteArray(32).also { it[0] = 9 }

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun vBayty(x: BigInteger): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val j = be.size - 1 - i
            if (i < 32) out[i] = be[j]
        }
        return out
    }

    /** Закрытый ключ в форме WireGuard: младшие три бита сняты, старший снят, предпоследний поставлен. */
    fun prizhat(k: ByteArray): ByteArray = k.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    /** Скаляр [k] (32 байта, прижимается здесь) на точку [u] (32 байта, little-endian). */
    fun umnozhit(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32) { "X25519: 32 байта" }
        val s = le(prizhat(k))
        val uu = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = le(uu).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (s.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return vBayty(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    /** Открытый ключ к закрытому. */
    fun otkrytyy(zakrytyy: ByteArray): ByteArray = umnozhit(zakrytyy, BAZA)

    /** Новый закрытый ключ (уже прижатый, как его пишет `wg genkey`). */
    fun novyy(random: SecureRandom = SecureRandom()): ByteArray = prizhat(ByteArray(32).also { random.nextBytes(it) })
}
