package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Свой журнал мимо logcat (прошивка Honor режет всё ниже E) и пояс в шапке файла.
 *
 * Разбор на сервере (netmon `razbor_zhurnalov.py`) берёт тег регулярным выражением
 * `RX_TEG` и время — `СТАМП`; строка, записанная напрямую, обязана читаться им так же,
 * как строка из logcat.
 */
class KLogTest {
    /** Тот же образец, что у разборщика netmon. */
    private val rxTeg = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([A-Za-z0-9_.\-]+):\s""")

    @Test
    fun `прямая строка читается разбором как строка logcat`() {
        val s = KLog.stroka("10-20 13:12:12.280", 4321, 4350, 'I', "AutoMode", "заход: обычная", null)
        assertEquals("10-20 13:12:12.280  4321  4350 I AutoMode: заход: обычная\n", s)
        assertEquals("AutoMode", rxTeg.find(s)?.groupValues?.get(1))
        // И собственный отсев шума её не трогает.
        assertFalse(AppLog.shumnaya(s.trimEnd()))
    }

    @Test
    fun `многострочное сообщение — каждая строка со своим началом`() {
        val s = KLog.stroka("10-20 13:12:12.280", 1, 2, 'W', "BoxService", "раз\nдва", IllegalStateException("беда"))
        val lines = s.trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines.all { rxTeg.containsMatchIn(it) })
        assertTrue(lines[2].endsWith("java.lang.IllegalStateException: беда"))
    }

    @Test
    fun `режется то, чей маркер не вернулся, и V вслед за D`() {
        assertEquals(setOf('D', 'I', 'W', 'V'), KLog.rezhetsyaPo(setOf('E')))
        assertEquals(emptySet<Char>(), KLog.rezhetsyaPo(setOf('D', 'I', 'W', 'E')))
        assertEquals(setOf('D', 'I', 'W', 'E', 'V'), KLog.rezhetsyaPo(emptySet()))
    }

    @Test
    fun `признак для паспорта`() {
        assertEquals("?", KLog.opisanie(null))
        assertEquals("ok", KLog.opisanie(emptySet()))
        assertEquals("rezhet_DIW", KLog.opisanie(setOf('W', 'V', 'D', 'I')))
    }

    @Test
    fun `опоздавший маркер снимает уровень с прямой записи`() {
        KLog.reshit(setOf('D', 'I', 'W', 'V'))
        assertTrue(KLog.vernulsya('I'))
        assertFalse(KLog.vernulsya('I'))
        assertEquals("rezhet_DW", KLog.priznak())
        KLog.reshit(emptySet())
    }

    @Test
    fun `смещение пояса`() {
        assertEquals("+03:00", AppLog.smeshchenie(3 * 3600_000))
        assertEquals("+05:00", AppLog.smeshchenie(5 * 3600_000))
        assertEquals("-04:30", AppLog.smeshchenie(-(4 * 3600_000 + 30 * 60_000)))
        assertEquals("+00:00", AppLog.smeshchenie(0))
    }

    @Test
    fun `шапка ложится в начало каждого нового файла и только туда`() {
        val dir = Files.createTempDirectory("kelevra-shapka").toFile().also { it.deleteOnExit() }
        val journal = LogRotator(dir, "kelevra-core.log", maxFileBytes = 80, maxFiles = 3, header = { "ШАПКА\n" })
        repeat(12) { journal.append("строка $it\n") }
        journal.close()
        val files = journal.files()
        assertTrue(files.size >= 2)
        for (f in files) {
            val lines = f.readLines()
            assertEquals("ШАПКА", lines.first())
            assertEquals(1, lines.count { it == "ШАПКА" })
            assertTrue(f.length() <= 80)
        }
        // Продолжение непустого файла после перезапуска шапку второй раз не кладёт.
        val again = LogRotator(dir, "kelevra-core.log", maxFileBytes = 80, maxFiles = 3, header = { "ШАПКА\n" })
        again.append("x\n")
        again.close()
        assertEquals(1, again.head.readLines().count { it == "ШАПКА" })
    }
}
