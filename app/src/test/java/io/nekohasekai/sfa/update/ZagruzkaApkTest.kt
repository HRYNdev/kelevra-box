package io.nekohasekai.sfa.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Загрузка файла обновления: готовый не качается заново, оборванное докачивается с места
 * обрыва, повтор идёт после паузы новым соединением, загрузка одна за раз.
 */
class ZagruzkaApkTest {
    private val versiya = 711164
    private val fayl = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private lateinit var papka: File

    @Before
    fun setUp() {
        papka = Files.createTempDirectory("zagruzka-apk-test").toFile()
    }

    @After
    fun tearDown() {
        papka.deleteRecursively()
    }

    private val gotovyy get() = File(papka, IshodUstanovki.imyaFayla(versiya))
    private val chast get() = File(papka, IshodUstanovki.imyaChastichnogoFayla(versiya))

    /** Проверка как у настоящей: годен только файл, байт в байт равный ожидаемому. */
    private val proverit: (File) -> String? = { f ->
        if (f.readBytes().contentEquals(fayl)) null else "хеш не совпал со списком релизов"
    }

    /** Поток, который отдаёт [bayt] байт и рвётся, как мобильная сеть посреди загрузки. */
    private fun rvushchiysya(dannye: ByteArray, bayt: Int): InputStream = object : InputStream() {
        private var i = 0
        override fun read(): Int {
            if (i >= bayt) throw IOException("Connection reset")
            return dannye[i++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= bayt) throw IOException("Connection reset")
            val n = minOf(len, bayt - i)
            System.arraycopy(dannye, i, b, off, n)
            i += n
            return n
        }
    }

    /**
     * Сервер, который честно отдаёт куски по Range. [obryvy] — сколько байт отдать в
     * очередном соединении перед обрывом (пусто — до конца).
     */
    private inner class Server(
        private val obryvy: ArrayDeque<Int> = ArrayDeque(),
        private val umeetKuski: Boolean = true,
    ) : IstochnikZagruzki {
        val zaprosy = mutableListOf<Long>()

        override fun otkryt(url: String, sBayta: Long): OtvetZagruzki {
            zaprosy += sBayta
            val s = if (umeetKuski) sBayta.toInt() else 0
            val telo = fayl.copyOfRange(s, fayl.size)
            val potok = obryvy.removeFirstOrNull()?.let { rvushchiysya(telo, it) } ?: ByteArrayInputStream(telo)
            return if (umeetKuski && sBayta > 0) {
                OtvetZagruzki(206, telo.size.toLong(), sBayta, potok)
            } else {
                OtvetZagruzki(200, telo.size.toLong(), null, potok)
            }
        }
    }

    private fun skachat(
        server: IstochnikZagruzki,
        pauzy: MutableList<Long> = mutableListOf(),
        dokachka: Boolean = true,
        razmer: Long = fayl.size.toLong(),
    ): File = runBlocking {
        ZagruzkaApk.poluchit(
            papka = papka,
            versionCode = versiya,
            ozhidaemyyRazmer = razmer,
            dokachka = dokachka,
            url = "https://example.invalid/update.apk",
            istochnik = server,
            proverit = proverit,
            pauza = { pauzy += it },
        )
    }

    @Test
    fun `готовый сверенный файл берётся без сети`() {
        gotovyy.writeBytes(fayl)
        val server = Server()

        val itog = skachat(server)

        assertEquals(gotovyy, itog)
        assertEquals("ни одного запроса в сеть", emptyList<Long>(), server.zaprosy)
    }

    @Test
    fun `готовый файл, не прошедший проверку, качается заново`() {
        gotovyy.writeBytes(fayl.copyOf(1000))
        val server = Server()

        val itog = skachat(server)

        assertArrayEquals(fayl, itog.readBytes())
        assertEquals(listOf(0L), server.zaprosy)
    }

    @Test
    fun `недокачанное с прошлого раза докачивается с места обрыва`() {
        chast.writeBytes(fayl.copyOf(120_000))
        val server = Server()

        val itog = skachat(server)

        assertEquals("запрос с байта, где оборвалось", listOf(120_000L), server.zaprosy)
        assertArrayEquals(fayl, itog.readBytes())
        assertFalse("недокачанный файл после успеха не остаётся", chast.exists())
    }

    @Test
    fun `обрыв посреди загрузки - пауза и докачка новым соединением`() {
        val server = Server(ArrayDeque(listOf(100_000, 50_000)))
        val pauzy = mutableListOf<Long>()

        val itog = skachat(server, pauzy)

        assertArrayEquals(fayl, itog.readBytes())
        assertEquals("каждая попытка — новый запрос с места обрыва", listOf(0L, 100_000L, 150_000L), server.zaprosy)
        assertEquals("перед повтором пауза, растущая от попытки к попытке", ZagruzkaApk.PAUZY_MS.take(2), pauzy)
    }

    @Test
    fun `сеть не дала докачать - недокачанное остаётся для следующего раза`() {
        val server = Server(ArrayDeque(List(ZagruzkaApk.POPYTOK) { 10_000 }))
        val pauzy = mutableListOf<Long>()

        try {
            skachat(server, pauzy)
            fail("без сети файл не может быть получен")
        } catch (e: IOException) {
            // ожидаемо: сетевой сбой выходит наружу как есть
        }

        assertEquals(ZagruzkaApk.POPYTOK, server.zaprosy.size)
        assertEquals(ZagruzkaApk.PAUZY_MS, pauzy)
        assertEquals("скачанное за все попытки не потеряно", 10_000L * ZagruzkaApk.POPYTOK, chast.length())
        assertFalse(gotovyy.exists())

        // Следующая работа продолжает с того же места и доводит до конца.
        val dalshe = Server()
        val itog = skachat(dalshe)
        assertEquals(listOf(10_000L * ZagruzkaApk.POPYTOK), dalshe.zaprosy)
        assertArrayEquals(fayl, itog.readBytes())
    }

    @Test
    fun `сервер без кусков отдаёт файл целиком - пишем с нуля, а не дописываем`() {
        chast.writeBytes(fayl.copyOf(50_000))
        val server = Server(umeetKuski = false)

        val itog = skachat(server)

        assertArrayEquals(fayl, itog.readBytes())
    }

    @Test
    fun `без хеша недокачанное не продолжается`() {
        chast.writeBytes(fayl.copyOf(50_000))
        val server = Server()

        skachat(server, dokachka = false)

        assertEquals(listOf(0L), server.zaprosy)
    }

    @Test
    fun `недокачанное не того содержимого сбрасывается и файл качается с нуля`() {
        // Склейка старого куска с новым не сходится с хешем: кусок стирается, повтор с нуля.
        chast.writeBytes(ByteArray(120_000) { 1 })
        val server = Server()
        val pauzy = mutableListOf<Long>()

        val itog = skachat(server, pauzy)

        assertEquals(listOf(120_000L, 0L), server.zaprosy)
        assertArrayEquals(fayl, itog.readBytes())
    }

    @Test
    fun `файл, ни разу не прошедший проверку - FaylNeGoden, мусор не остаётся`() {
        val server = IstochnikZagruzki { _, _ -> OtvetZagruzki(200, 10, null, ByteArrayInputStream(ByteArray(10))) }

        try {
            skachat(server, razmer = 0L)
            fail("негодный файл не может быть отдан")
        } catch (e: FaylNeGoden) {
            assertEquals("хеш не совпал со списком релизов", e.prichina)
        }
        assertFalse(chast.exists())
        assertFalse(gotovyy.exists())
    }

    @Test
    fun `две загрузки сразу - в сеть идёт одна, вторая берёт её файл`() {
        val zaprosov = AtomicInteger()
        val server = IstochnikZagruzki { _, _ ->
            zaprosov.incrementAndGet()
            Thread.sleep(200)
            OtvetZagruzki(200, fayl.size.toLong(), null, ByteArrayInputStream(fayl))
        }

        val itogi = runBlocking(Dispatchers.Default) {
            List(2) {
                async {
                    ZagruzkaApk.poluchit(
                        papka = papka,
                        versionCode = versiya,
                        ozhidaemyyRazmer = fayl.size.toLong(),
                        dokachka = true,
                        url = "https://example.invalid/update.apk",
                        istochnik = server,
                        proverit = proverit,
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, zaprosov.get())
        itogi.forEach { assertArrayEquals(fayl, it.readBytes()) }
    }

    @Test
    fun `как писать ответ сервера`() {
        assertEquals(ZagruzkaApk.Zapis.SNACHALA, ZagruzkaApk.kakPisat(0, 200, null))
        assertEquals(ZagruzkaApk.Zapis.SNACHALA, ZagruzkaApk.kakPisat(500, 200, null))
        assertEquals(ZagruzkaApk.Zapis.DOPISAT, ZagruzkaApk.kakPisat(500, 206, 500))
        assertEquals(ZagruzkaApk.Zapis.SBROS, ZagruzkaApk.kakPisat(500, 206, 0))
        assertEquals(ZagruzkaApk.Zapis.VSYO_EST, ZagruzkaApk.kakPisat(500, 416, null))
        for ((s, kod) in listOf(0L to 206, 0L to 416, 0L to 404, 500L to 503)) {
            try {
                ZagruzkaApk.kakPisat(s, kod, null)
                fail("ответ $kod при s=$s — сбой")
            } catch (e: IOException) {
                // ожидаемо
            }
        }
    }

    @Test
    fun `начало куска из Content-Range`() {
        assertEquals(100L, ZagruzkaApk.nachaloIzContentRange("bytes 100-999/1000"))
        assertEquals(0L, ZagruzkaApk.nachaloIzContentRange("bytes 0-9/*"))
        assertNull(ZagruzkaApk.nachaloIzContentRange(null))
        assertNull(ZagruzkaApk.nachaloIzContentRange("bytes */1000"))
    }

    @Test
    fun `пауза перед попыткой растёт и упирается в последнюю`() {
        assertEquals(ZagruzkaApk.PAUZY_MS[0], ZagruzkaApk.pauzaPered(2))
        assertEquals(ZagruzkaApk.PAUZY_MS[1], ZagruzkaApk.pauzaPered(3))
        assertEquals(ZagruzkaApk.PAUZY_MS.last(), ZagruzkaApk.pauzaPered(10))
        assertTrue(ZagruzkaApk.PAUZY_MS.zipWithNext().all { (a, b) -> a < b })
    }
}
