package io.nekohasekai.sfa.bg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Свой журнал мимо logcat (Huawei режет logcat приложения в ноль, Honor — до E) и пояс
 * в шапке файла.
 *
 * Разбор на сервере (netmon `razbor_zhurnalov.py`) берёт тег регулярным выражением
 * `RX_TEG` и время — `СТАМП`; строка, записанная напрямую, обязана читаться им так же,
 * как строка из logcat.
 *
 * В юнит-тестах android.util.Log и Process — заглушки, которые бросают: KLog обязан
 * писать в файл и тогда (на телефоне это тот же случай — logcat недоступен).
 */
class KLogTest {
    /** Тот же образец, что у разборщика netmon. */
    private val rxTeg = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([A-Za-z0-9_.\-]+):\s""")

    private val zhurnaly = mutableListOf<File>()

    private fun novyZhurnal(): LogRotator {
        val dir = Files.createTempDirectory("kelevra-klog").toFile()
        zhurnaly += dir
        return LogRotator(dir, "kelevra-app.log", maxFileBytes = 1024 * 1024, maxFiles = 2)
    }

    @After
    fun ubrat() {
        AppLog.otklyuchit()
        zhurnaly.forEach { it.deleteRecursively() }
    }

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
    fun `цепочка причин исключения — без строк стека`() {
        val tr = RuntimeException("сверху", java.io.IOException("снизу"))
        val lines = KLog.stroka("10-20 13:12:12.280", 1, 2, 'E', "BoxService", "сорвалось", tr).trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[2].endsWith("Caused by: java.io.IOException: снизу"))
        assertTrue(lines.none { it.contains("\tat ") })
    }

    @Test
    fun `KLog пишет в файл напрямую всегда и на всех уровнях`() {
        val journal = novyZhurnal()
        AppLog.podklyuchit(journal)
        KLog.v("KLogTestV", "уровень v")
        KLog.d("KLogTestD", "уровень d")
        KLog.i("KLogTestI", "уровень i")
        KLog.w("KLogTestW", "уровень w", IllegalStateException("беда"))
        KLog.e("KLogTestE", "уровень e")
        val lines = journal.head.readLines()
        for ((level, tag) in listOf('V' to "KLogTestV", 'D' to "KLogTestD", 'I' to "KLogTestI", 'W' to "KLogTestW", 'E' to "KLogTestE")) {
            val line = lines.single { it.contains(" $level $tag: уровень ") }
            assertEquals(tag, rxTeg.find(line)?.groupValues?.get(1))
        }
        assertTrue(lines.any { it.endsWith("KLogTestW: java.lang.IllegalStateException: беда") })
        // Теги запомнены — чтение logcat такие строки своего процесса отбросит.
        assertTrue(KLog.tegi.containsAll(listOf("KLogTestV", "KLogTestD", "KLogTestI", "KLogTestW", "KLogTestE")))
    }

    @Test
    fun `строки до открытия файла ждут и дописываются при открытии`() {
        AppLog.otklyuchit()
        KLog.i("KLogTestRano", "до открытия журнала")
        val journal = novyZhurnal()
        AppLog.podklyuchit(journal)
        KLog.i("KLogTestRano", "после открытия")
        val lines = journal.head.readLines()
        val rano = lines.indexOfFirst { it.endsWith("KLogTestRano: до открытия журнала") }
        val pozzhe = lines.indexOfFirst { it.endsWith("KLogTestRano: после открытия") }
        assertTrue(rano >= 0)
        assertTrue(pozzhe > rano)
    }

    @Test
    fun `шумный тег напрямую не пишется`() {
        val journal = novyZhurnal()
        AppLog.podklyuchit(journal)
        KLog.w("PrivilegeSettingsClient", "шум")
        KLog.i("KLogTestTiho", "дело")
        val text = journal.head.readText()
        assertFalse(text.contains("PrivilegeSettingsClient"))
        assertTrue(text.contains("KLogTestTiho: дело"))
    }

    @Test
    fun `падение — стек целиком в файле`() {
        val journal = novyZhurnal()
        AppLog.podklyuchit(journal)
        KLog.padenie(Thread.currentThread(), RuntimeException("наверху", IllegalStateException("причина")))
        val lines = journal.head.readLines().filter { it.contains(" E ${KLog.TEG_PADENIE}: ") }
        assertTrue(lines.first().contains("FATAL EXCEPTION: поток "))
        assertTrue(lines.any { it.endsWith("java.lang.RuntimeException: наверху") })
        assertTrue(lines.any { it.contains("\tat io.nekohasekai.sfa.bg.KLogTest") })
        assertTrue(lines.any { it.contains("Caused by: java.lang.IllegalStateException: причина") })
        // Каждая строка стека — со своим началом: разбор не приклеит её к чужой записи.
        assertTrue(lines.size > 4)
        assertTrue(lines.all { rxTeg.containsMatchIn(it) })
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
