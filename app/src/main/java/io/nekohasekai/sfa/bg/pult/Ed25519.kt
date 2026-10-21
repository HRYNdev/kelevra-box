package io.nekohasekai.sfa.bg.pult

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Проверка подписи ed25519 (только проверка, без подписи) — перенос эталонной реализации
 * из RFC 8032, раздел 6, на BigInteger.
 *
 * Почему своя, а не из системы. Ed25519 в `java.security` на Android есть только с API 33,
 * а minSdk у нас 24 (и 21 у legacy-сборки); ни Tink, ни BouncyCastle в зависимостях нет,
 * а тянуть мегабайты ради одной проверки раз в час незачем. Эталонный код короткий и
 * проверяется тестами на векторах RFC 8032 и перекрёстно с Ed25519 из JDK.
 *
 * Строгость: S < L (иначе подпись подделывается сдвигом на L), точки R и A обязаны
 * разбираться как точки кривой, y < p. Скорость не важна: пульт приходит раз в опрос.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val TWO = BigInteger.valueOf(2)
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val MASK255: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)

    private class Tochka(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun inv(a: BigInteger): BigInteger = a.modPow(P.subtract(TWO), P)

    private val NOL = Tochka(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val B: Tochka by lazy {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0)!!
        Tochka(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun slozhit(a: Tochka, b: Tochka): Tochka {
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val cc = a.t.multiply(TWO).multiply(D).multiply(b.t).mod(P)
        val dd = a.z.multiply(TWO).multiply(b.z).mod(P)
        val e = bb.subtract(aa)
        val f = dd.subtract(cc)
        val g = dd.add(cc)
        val h = bb.add(aa)
        return Tochka(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun umnozhit(s: BigInteger, p: Tochka): Tochka {
        var q = NOL
        var r = p
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = slozhit(q, r)
            r = slozhit(r, r)
            k = k.shiftRight(1)
        }
        return q
    }

    private fun ravny(a: Tochka, b: Tochka): Boolean =
        a.x.multiply(b.z).subtract(b.x.multiply(a.z)).mod(P).signum() == 0 &&
            a.y.multiply(b.z).subtract(b.y.multiply(a.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(inv(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    /** Число из байт little-endian. */
    private fun le(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun razobratTochku(s: ByteArray): Tochka? {
        if (s.size != 32) return null
        val chislo = le(s)
        val sign = if (chislo.testBit(255)) 1 else 0
        val y = chislo.and(MASK255)
        val x = recoverX(y, sign) ?: return null
        return Tochka(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** true — [podpis] (64 байта) под [soobshchenie] сделана ключом [klyuch] (сырые 32 байта). */
    fun proverit(klyuch: ByteArray, soobshchenie: ByteArray, podpis: ByteArray): Boolean = runCatching {
        if (klyuch.size != 32 || podpis.size != 64) return false
        val a = razobratTochku(klyuch) ?: return false
        val rBytes = podpis.copyOfRange(0, 32)
        val r = razobratTochku(rBytes) ?: return false
        val s = le(podpis.copyOfRange(32, 64))
        if (s >= L) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(rBytes)
        sha.update(klyuch)
        sha.update(soobshchenie)
        val h = le(sha.digest()).mod(L)
        ravny(umnozhit(s, B), slozhit(r, umnozhit(h, a)))
    }.getOrDefault(false)

    fun izHex(hex: String): ByteArray {
        val chistyy = hex.trim()
        require(chistyy.length % 2 == 0) { "нечётная длина hex" }
        return ByteArray(chistyy.length / 2) { i -> chistyy.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
