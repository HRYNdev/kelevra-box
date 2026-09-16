package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.LogUploadWork.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * Ротация журнала между посылками не теряет недосланный хвост и не гонит отправленное повторно.
 *
 * Повод — посылка телефона 16.09.2026: журнал приложения за 15:50–16:05 не приехал.
 * Головной `kelevra-app.log` ротировался в `.1`, а смещение для `.1` бралось по имени —
 * от отметки прежнего `.1`, давно отправленного другого файла. Недосланный хвост
 * выпадал; тем же путём `kelevra-core.log.2` уехал второй раз целиком.
 */
class LogRotaciyaOtpravkaTest {

    // ------------------------------------------------ чистая функция uzheOtpravleno

    private fun crc(text: String): (Int) -> Long = { dlina ->
        CRC32().apply { update(text.toByteArray().copyOf(dlina)) }.value
    }

    private fun metka(text: String, sent: Long = text.toByteArray().size.toLong(), modified: Long = 1L): Mark {
        val dlina = minOf(sent, LogUploadWork.DLINA_OTPECHATKA.toLong()).toInt()
        return Mark(sent, modified, sent, dlina, crc(text)(dlina))
    }

    private val a = "04-16 15:00:00.001 начало файла A\n".repeat(20)
    private val z = "04-16 12:00:00.001 начало файла Z\n".repeat(20)

    @Test
    fun `обычная дописка — хвост от своей отметки`() {
        val marks = mapOf("app.log" to metka(a))
        val sejchas = a + "новое\n"
        val m = LogUploadWork.uzheOtpravleno("app.log", sejchas.toByteArray().size.toLong(), 2L, marks, crc(sejchas))
        assertEquals(a.toByteArray().size.toLong(), m?.sent)
    }

    @Test
    fun `ротация между посылками — хвост бывшего головного считается от ЕГО отметки, а не от чужой под именем 1`() {
        val otpravleno = a.toByteArray().size.toLong()
        // Под .1 помнится прежний файл Z, отправленный целиком и длиннее, чем A.
        val marks = mapOf(
            "app.log" to metka(a),
            "app.log.1" to metka(z + z + z),
        )
        val stalo = a + "дописано до ротации\n"
        val m = LogUploadWork.uzheOtpravleno("app.log.1", stalo.toByteArray().size.toLong(), 3L, marks, crc(stalo))
        assertEquals(otpravleno, m?.sent)
    }

    @Test
    fun `ротация дважды — бывший головной узнаётся и под именем 2`() {
        val marks = mapOf("app.log" to metka(a), "app.log.1" to metka(z))
        val stalo = a + "хвост\n"
        val m = LogUploadWork.uzheOtpravleno("app.log.2", stalo.toByteArray().size.toLong(), 3L, marks, crc(stalo))
        assertEquals(a.toByteArray().size.toLong(), m?.sent)
        // А прежний .1 (Z), уехавший целиком, под именем .3 не уходит повторно.
        val mz = LogUploadWork.uzheOtpravleno("app.log.3", z.toByteArray().size.toLong(), 1L, marks, crc(z))
        assertEquals(z.toByteArray().size.toLong(), mz?.sent)
    }

    @Test
    fun `файл пересоздан и меньше отметки — шлём с нуля`() {
        val marks = mapOf("app.log" to metka(a))
        val novyj = "04-16 16:05:31.514 запись журнала начата\n"
        assertNull(LogUploadWork.uzheOtpravleno("app.log", novyj.toByteArray().size.toLong(), 5L, marks, crc(novyj)))
        // То же начало, но файл короче отправленного — тоже другой файл.
        val usoh = a.substring(0, 100)
        assertNull(LogUploadWork.uzheOtpravleno("app.log", usoh.toByteArray().size.toLong(), 5L, marks, crc(usoh)))
    }

    @Test
    fun `отметка старого образца без отпечатка работает как раньше`() {
        val marks = mapOf("app.log" to Mark(size = 10, modified = 1L, sent = 10))
        assertEquals(10L, LogUploadWork.uzheOtpravleno("app.log", 15, 2L, marks, crc(a))?.sent)
        assertEquals(7L, LogUploadWork.uzheOtpravleno("x.1", 7, 1L, mapOf("x" to Mark(7, 1L, 7)), crc(a))?.sent)
    }

    // ------------------------------------------- сценарий на настоящих файлах и ротаторе

    private fun tempDir(name: String): File =
        Files.createTempDirectory("kelevra-$name").toFile().also { it.deleteOnExit() }

    private fun unpack(archive: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(archive)).use { String(it.readBytes()) }

    /** Одна «посылка»: собрать, упаковать, подвинуть отметки как после успеха. */
    private fun posylka(dir: File, marks: MutableMap<String, Mark>): String {
        val sbor = LogUploadWork.collectLogs(listOf(dir), marks)
        if (sbor.parts.isEmpty()) {
            LogUploadWork.zapomnit(marks, sbor.uchteno, emptyList())
            return ""
        }
        val (archive, taken) = LogUploadWork.buildArchive(sbor.parts)
        LogUploadWork.zapomnit(marks, sbor.uchteno, taken)
        return unpack(archive)
    }

    private fun stroka(n: Int) = "09-16 15:%02d:%02d.000 строка номер %05d\n".format(n / 60 % 60, n % 60, n)

    @Test
    fun `ротатор между посылками — каждая строка приезжает ровно один раз`() {
        val dir = tempDir("rot")
        val rotator = LogRotator(dir, "kelevra-app.log", 2000, 7)
        val marks = mutableMapOf<String, Mark>()
        val prishlo = StringBuilder()
        var n = 0
        // Посылки через разные промежутки: без ротации, с одной и с двумя ротациями подряд.
        for (porciya in listOf(30, 10, 60, 5, 150, 1, 90, 0, 40)) {
            repeat(porciya) { rotator.append(stroka(n++)) }
            prishlo.append(posylka(dir, marks))
        }
        rotator.close()
        val stroki = prishlo.lines().filter { it.contains("строка номер") }
        // Ротатор держит 7 файлов по 2000 байт — самые старые строки к концу вытеснены,
        // но всё, что было на диске в момент посылки, должно было приехать.
        for (i in 0 until n) {
            val s = stroka(i).trimEnd('\n')
            assertEquals("строка $i приехала ${stroki.count { it == s }} раз", 1, stroki.count { it == s })
        }
    }

    @Test
    fun `после ротации давно отправленный хвост не уезжает второй раз`() {
        val dir = tempDir("dup")
        val rotator = LogRotator(dir, "kelevra-core.log", 1000, 3)
        val marks = mutableMapOf<String, Mark>()
        var n = 0
        repeat(80) { rotator.append(stroka(n++)) }
        posylka(dir, marks)
        repeat(40) { rotator.append(stroka(n++)) }
        val vtoraya = posylka(dir, marks)
        assertFalse("старое не повторяется", vtoraya.contains(stroka(0).trimEnd('\n')))
        assertTrue(vtoraya.contains(stroka(n - 1).trimEnd('\n')))
        rotator.close()
    }
}
