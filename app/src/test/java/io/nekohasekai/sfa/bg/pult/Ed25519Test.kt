package io.nekohasekai.sfa.bg.pult

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

/** Проверка подписи ed25519: векторы RFC 8032 и перекрёстно с JDK. */
class Ed25519Test {
    private fun hex(s: String) = Ed25519.izHex(s.replace(" ", ""))

    @Test
    fun `вектор RFC 8032 TEST 1 пустое сообщение`() {
        val pub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertTrue(Ed25519.proverit(pub, ByteArray(0), sig))
        assertFalse(Ed25519.proverit(pub, byteArrayOf(0), sig))
    }

    @Test
    fun `вектор RFC 8032 TEST 2 и TEST 3`() {
        assertTrue(
            Ed25519.proverit(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                hex("72"),
                hex(
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
                ),
            ),
        )
        assertTrue(
            Ed25519.proverit(
                hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"),
                hex("af82"),
                hex(
                    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                        "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
                ),
            ),
        )
    }

    @Test
    fun `подпись JDK проверяется, чужой ключ и испорченные байты нет`() {
        val k = PultTestKlyuch()
        val chuzhoy = PultTestKlyuch()
        repeat(5) {
            val msg = Random.nextBytes(Random.nextInt(0, 300))
            val sig = k.podpisat(msg)
            assertTrue(Ed25519.proverit(k.otkrytyy, msg, sig))
            assertFalse(Ed25519.proverit(chuzhoy.otkrytyy, msg, sig))
            val isporchen = sig.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
            assertFalse(Ed25519.proverit(k.otkrytyy, msg, isporchen))
            if (msg.isNotEmpty()) {
                val drugoe = msg.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
                assertFalse(Ed25519.proverit(k.otkrytyy, drugoe, sig))
            }
        }
    }

    @Test
    fun `S не меньше L отвергается (податливость подписи)`() {
        val k = PultTestKlyuch()
        val msg = "kelevra".toByteArray()
        val sig = k.podpisat(msg)
        val l = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray()).add(l)
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        val sdvinutaya = sig.copyOfRange(0, 32) + sBytes
        assertFalse(Ed25519.proverit(k.otkrytyy, msg, sdvinutaya))
    }

    @Test
    fun `негодные размеры не роняют проверку`() {
        assertFalse(Ed25519.proverit(ByteArray(31), ByteArray(0), ByteArray(64)))
        assertFalse(Ed25519.proverit(ByteArray(32), ByteArray(0), ByteArray(63)))
        assertFalse(Ed25519.proverit(ByteArray(32) { 0xff.toByte() }, ByteArray(0), ByteArray(64)))
    }

    @Test
    fun `вшитых ключей пульта два, по 32 байта`() {
        assertTrue(PultYadro.KLYUCHI_BOEVYE.size == 2)
        PultYadro.KLYUCHI.forEach { assertTrue(it.size == 32) }
    }

    @Test
    fun `проверочный ключ стенда — только в debug и третьим`() {
        // Юнит-тесты гоняются на debug-варианте: здесь он обязан быть, в релизе — нет (src/release, grep по dex).
        assertTrue(io.nekohasekai.sfa.BuildConfig.DEBUG)
        val proba = PultProbaKlyuch.HEX
        assertTrue(proba != null && proba.length == 64)
        assertTrue(PultYadro.KLYUCHI.size == 3)
        assertTrue(PultYadro.KLYUCHI.last().contentEquals(Ed25519.izHex(proba!!)))
        assertTrue(PultYadro.KLYUCHI_BOEVYE.none { it.contentEquals(Ed25519.izHex(proba)) })
    }

    @Test
    fun `base64 туда и обратно`() {
        repeat(20) {
            val b = Random.nextBytes(it * 7)
            assertTrue(b.contentEquals(PultBase64.razobrat(PultBase64.zakodirovat(b))))
        }
        assertTrue(PultBase64.razobrat("не base64") == null)
    }
}
