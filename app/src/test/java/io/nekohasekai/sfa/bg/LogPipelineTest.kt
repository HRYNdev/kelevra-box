package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.LogUploadWork.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Свой журнал и суточная посылка — проверка по сценариям, а не по строчкам.
 *
 * Повод. Логи телефона до этого писал внешний adb-шелл в /sdcard/Download/kelevra-logs,
 * файлы принадлежали шеллу — и на Android 11+ приложение читало их только с «Доступом
 * ко всем файлам», который человек выдаёт руками в системных настройках. С одним
 * телефоном разработчика это работало. С семьёй — нет: подпиской пользуются несколько
 * человек с обычными телефонами, никто из них в системные настройки за таким
 * разрешением не пойдёт, а логи нужны СО ВСЕХ устройств. Поэтому запись переехала
 * внутрь приложения, в собственный каталог, где разрешений не требуется вовсе.
 *
 * Проверяем ровно то, от чего это зависит:
 *  1. своя запись не съедает телефон — ротация держит жёсткий потолок;
 *  2. посылка собирается из своего каталога и читается обычным `gzip -d`;
 *  3. недоступная папка Download отправку не срывает — она просто не участвует;
 *  4. доступная — приезжает вторым источником, как на телефоне разработчика;
 *  5. дважды один и тот же кусок не уходит.
 */
class LogPipelineTest {

    /** Отметки времени берём настоящие: файловые системы не любят 1970 год. */
    private val сутки = 24L * 60 * 60 * 1000
    private val вчера = 1_756_000_000_000L
    private val сегодня = вчера + сутки

    private fun tempDir(name: String): File =
        Files.createTempDirectory("kelevra-$name").toFile().also { it.deleteOnExit() }

    private fun File.put(name: String, text: String, modified: Long = вчера): File =
        File(this, name).also {
            it.parentFile?.mkdirs()
            it.writeText(text)
            it.setLastModified(modified)
        }

    private fun unpack(archive: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(archive)).use { String(it.readBytes()) }

    /** Что делает [LogUploadWork.runOnce] после удачной отправки: двигает отметки. */
    private fun remember(marks: MutableMap<String, Mark>, taken: List<LogUploadWork.Part>) {
        taken.forEach { part ->
            marks[part.label] = Mark(
                size = part.offset + part.length,
                modified = part.modified,
                sent = part.offset + part.length,
            )
        }
    }

    // ------------------------------------------------------ 1. своя запись и потолок

    @Test
    fun `своя запись ротируется и не перерастает потолок`() {
        val dir = tempDir("rotate")
        // Игрушечные размеры: 3 файла по 100 байт. Потолок — 300 байт, и ни байтом больше.
        val journal = LogRotator(dir, "kelevra-app.log", maxFileBytes = 100, maxFiles = 3)
        repeat(200) { index -> journal.append("строка номер $index\n") }
        journal.close()

        val files = journal.files()
        assertTrue("журнал должен состоять максимум из 3 файлов, а не ${files.size}", files.size <= 3)
        assertTrue("ни один файл не длиннее 100 байт", files.all { it.length() <= 100 })
        assertTrue("весь журнал не больше 300 байт, а вышло ${journal.totalBytes()}", journal.totalBytes() <= 300)
    }

    @Test
    fun `после ротации в журнале остаётся самое свежее, а не самое старое`() {
        val dir = tempDir("freshest")
        val journal = LogRotator(dir, "kelevra-app.log", maxFileBytes = 60, maxFiles = 3)
        repeat(100) { index -> journal.append("событие-$index\n") }
        journal.close()

        val all = journal.files().joinToString("") { it.readText() }
        assertTrue("последнее событие обязано уцелеть", all.contains("событие-99"))
        assertFalse("самое старое должно быть вытеснено", all.contains("событие-0\n"))
    }

    @Test
    fun `строка длиннее целого файла не пробивает потолок`() {
        val dir = tempDir("longline")
        val journal = LogRotator(dir, "kelevra-app.log", maxFileBytes = 64, maxFiles = 2)
        journal.append("x".repeat(10_000) + "\n")
        journal.append("x".repeat(10_000) + "\n")
        journal.close()

        assertTrue("потолок 128 байт, а вышло ${journal.totalBytes()}", journal.totalBytes() <= 128)
    }

    @Test
    fun `перезапуск приложения дописывает в тот же журнал, а не начинает с нуля`() {
        val dir = tempDir("restart")
        LogRotator(dir, "kelevra-app.log", maxFileBytes = 1000, maxFiles = 3).apply {
            append("до перезапуска\n")
            close()
        }
        val second = LogRotator(dir, "kelevra-app.log", maxFileBytes = 1000, maxFiles = 3)
        second.append("после перезапуска\n")
        second.close()

        val head = File(dir, "kelevra-app.log").readText()
        assertTrue(head.contains("до перезапуска"))
        assertTrue(head.contains("после перезапуска"))
    }

    // ------------------------------------------------- 2. посылка из своего каталога

    @Test
    fun `перезагрузка телефона не начинает журнал с нуля и не теряет хвосты`() {
        val dir = tempDir("reboot")
        // До перезагрузки: головной файл уже вырос и один хвост отложен.
        val before = LogRotator(dir, "kelevra-app.log", maxFileBytes = 60, maxFiles = 4)
        repeat(20) { before.append("до перезагрузки $it\n") }
        before.close()
        val хвостов = dir.listFiles()!!.count { it.name.startsWith("kelevra-app.log.") }
        assertTrue("для проверки нужен хотя бы один хвост", хвостов >= 1)

        // Телефон перезагрузился: процесс новый, каталог тот же.
        val after = LogRotator(dir, "kelevra-app.log", maxFileBytes = 60, maxFiles = 4)
        after.append("после перезагрузки\n")
        after.close()

        val all = after.files().joinToString("") { it.readText() }
        assertTrue("запись после перезагрузки обязана быть", all.contains("после перезагрузки"))
        assertTrue("прошлые записи обязаны уцелеть", all.contains("до перезагрузки 19"))
        assertTrue("хвосты обязаны уцелеть", after.files().size > 1)
        assertTrue("потолок держится и после перезагрузки", after.totalBytes() <= 240)
    }

    // ------------------------------------------------ 6. отчёты о падениях едут тоже

    @Test
    fun `отчёт о падении ядра попадает в посылку`() {
        val root = tempDir("crash")
        val reports = File(root, "crash_reports")
        File(reports, "20260828-231045").also { it.mkdirs() }.let { report ->
            File(report, "go.log").writeText("panic: runtime error\n")
            File(report, "metadata.json").writeText("{\"version\":\"kelevra27\"}\n")
            // Дамп памяти в посылку не берём: мегабайты, читаемые только инструментом.
            File(report, "heap.pprof").writeText("двоичный мусор")
        }

        val parts = LogUploadWork.collectReports(listOf(reports), emptyMap())
        assertEquals(
            setOf("20260828-231045/go.log", "20260828-231045/metadata.json"),
            parts.map { it.label }.toSet(),
        )

        val text = unpack(LogUploadWork.buildArchive(parts).first)
        assertTrue("разбор падения обязан приехать целиком", text.contains("panic: runtime error"))
        assertFalse("дамп памяти в посылке не нужен", text.contains("двоичный мусор"))
    }

    @Test
    fun `один и тот же отчёт о падении второй раз не уезжает`() {
        val root = tempDir("crash-again")
        val reports = File(root, "crash_reports")
        File(reports, "20260828-231045").also { it.mkdirs() }.let { report ->
            File(report, "go.log").writeText("panic: runtime error\n")
        }

        val marks = mutableMapOf<String, Mark>()
        val first = LogUploadWork.collectReports(listOf(reports), marks)
        assertEquals(1, first.size)
        remember(marks, LogUploadWork.buildArchive(first).second)

        assertTrue(
            "отчёт уже уехал — второй раз его слать незачем",
            LogUploadWork.collectReports(listOf(reports), marks).isEmpty(),
        )
    }

    @Test
    fun `отчёты у разных падений не затирают друг друга`() {
        val root = tempDir("crash-two")
        val reports = File(root, "crash_reports")
        listOf("20260828-231045", "20260829-070312").forEach { id ->
            File(reports, id).also { it.mkdirs() }.let { File(it, "go.log").writeText("падение $id\n") }
        }

        val parts = LogUploadWork.collectReports(listOf(reports), emptyMap())
        assertEquals("оба падения обязаны приехать под своими именами", 2, parts.map { it.label }.toSet().size)
        val text = unpack(LogUploadWork.buildArchive(parts).first)
        assertTrue(text.contains("падение 20260828-231045"))
        assertTrue(text.contains("падение 20260829-070312"))
    }

    @Test
    fun `падений не было — отчётов в посылке нет, и это не ошибка`() {
        val root = tempDir("crash-none")
        assertTrue(LogUploadWork.collectReports(listOf(File(root, "crash_reports")), emptyMap()).isEmpty())
    }

    @Test
    fun `посылка собирается из своего каталога и разжимается обычным gzip`() {
        val own = tempDir("own")
        val свежее = "AutoMode: дом подтверждён\n"
        own.put("kelevra-app.log", свежее, modified = сегодня)
        own.put("kelevra-app.log.1", "BoxService: туннель поднят\n", modified = вчера)

        val parts = LogUploadWork.collectParts(listOf(own), emptyMap())
        assertEquals(2, parts.size)

        val (archive, taken) = LogUploadWork.buildArchive(parts)
        assertEquals(2, taken.size)

        val text = unpack(archive)
        val шапка = "===== kelevra-app.log offset=0 bytes=${свежее.toByteArray().size} ====="
        assertTrue("в посылке нет шапки «$шапка»", text.contains(шапка))
        assertTrue(text.contains("AutoMode: дом подтверждён"))
        assertTrue(text.contains("BoxService: туннель поднят"))
    }

    @Test
    fun `свежие куски идут первыми — при обрезке по потолку теряется старое`() {
        val own = tempDir("order")
        own.put("kelevra-app.log.1", "старое\n", modified = вчера)
        own.put("kelevra-app.log", "свежее\n", modified = сегодня)

        val parts = LogUploadWork.collectParts(listOf(own), emptyMap())
        assertEquals("kelevra-app.log", parts.first().label)
    }

    // -------------------------------------- 3. Download недоступен — отправка живёт

    @Test
    fun `каталог Download недоступен — посылка всё равно собирается из своего`() {
        val own = tempDir("own-only")
        own.put("kelevra-app.log", "NetDns: резолверы молчат\n")
        // Так это выглядит на телефоне домашних: папки нет вовсе, разрешение никто не выдавал.
        val download = File(tempDir("nowhere"), "kelevra-logs")
        assertFalse(download.exists())

        val dirs = LogUploadWork.sourceDirs(own, download)
        assertEquals(listOf(own), dirs)

        val parts = LogUploadWork.collectParts(dirs, emptyMap())
        assertEquals(1, parts.size)
        val (archive, taken) = LogUploadWork.buildArchive(parts)
        assertTrue("отправлять есть что", archive.isNotEmpty() && taken.isNotEmpty())
        assertTrue(unpack(archive).contains("NetDns: резолверы молчат"))
    }

    @Test
    fun `нечитаемый Download не роняет сбор — просто не участвует`() {
        val own = tempDir("own-quiet")
        own.put("kelevra-app.log", "события\n")
        // Файл вместо каталога: listFiles вернёт null — ровно то же, что видит
        // приложение без «Доступа ко всем файлам».
        val download = tempDir("fake").put("kelevra-logs", "это не каталог")

        assertEquals(listOf(own), LogUploadWork.sourceDirs(own, download))
        assertEquals(1, LogUploadWork.collectParts(listOf(own, download), emptyMap()).size)
    }

    // ---------------------------------------- 4. Download доступен — второй источник

    @Test
    fun `каталог Download доступен — его содержимое приезжает вторым источником`() {
        val own = tempDir("own-plus")
        own.put("kelevra-app.log", "своё\n", modified = сегодня)
        val download = tempDir("download")
            .put("kelevra-logs/automode.log", "adb: automode\n", modified = вчера)
            .parentFile!!
        download.put("events.log", "adb: events\n", modified = вчера)

        val dirs = LogUploadWork.sourceDirs(own, download)
        assertEquals(listOf(own, download), dirs)

        val parts = LogUploadWork.collectParts(dirs, emptyMap())
        assertEquals(setOf("kelevra-app.log", "automode.log", "events.log"), parts.map { it.label }.toSet())

        val text = unpack(LogUploadWork.buildArchive(parts).first)
        assertTrue(text.contains("adb: automode"))
        assertTrue(text.contains("adb: events"))
        assertTrue(text.contains("своё"))
    }

    @Test
    fun `одинаковые имена в двух источниках не затирают друг друга`() {
        val own = tempDir("own-clash")
        own.put("kelevra-app.log", "своё\n", modified = сегодня)
        val download = tempDir("dl-clash")
            .put("kelevra-logs/kelevra-app.log", "чужое\n", modified = вчера)
            .parentFile!!

        val labels = LogUploadWork.collectParts(listOf(own, download), emptyMap()).map { it.label }
        assertEquals("оба куска должны попасть в посылку под разными именами", 2, labels.toSet().size)
        assertTrue(labels.contains("kelevra-app.log"))
        assertTrue(labels.contains("kelevra-logs/kelevra-app.log"))
    }

    // ------------------------------------------- 5. дважды одно и то же не уезжает

    @Test
    fun `повторная отправка не шлёт уже отправленное`() {
        val own = tempDir("again")
        val head = own.put("kelevra-app.log", "первый день\n", modified = вчера)

        val marks = mutableMapOf<String, Mark>()
        val first = LogUploadWork.collectParts(listOf(own), marks)
        assertEquals(1, first.size)
        remember(marks, LogUploadWork.buildArchive(first).second)

        // Ничего не изменилось — второй заход должен оказаться пустым.
        assertTrue("новых логов нет — отправлять нечего", LogUploadWork.collectParts(listOf(own), marks).isEmpty())

        // Дописали хвост — уходит ровно хвост, а не файл целиком.
        head.appendText("второй день\n")
        head.setLastModified(сегодня)
        val second = LogUploadWork.collectParts(listOf(own), marks)
        assertEquals(1, second.size)
        assertEquals("первый день\n".toByteArray().size.toLong(), second.first().offset)

        val text = unpack(LogUploadWork.buildArchive(second).first)
        assertTrue(text.contains("второй день"))
        assertFalse("старое второй раз не уезжает", text.contains("первый день"))
    }

    @Test
    fun `ротация под другим именем не гонит один и тот же кусок повторно`() {
        val own = tempDir("rotated")
        own.put("kelevra-app.log", "события дня\n", modified = вчера)

        val marks = mutableMapOf<String, Mark>()
        val parts = LogUploadWork.collectParts(listOf(own), marks)
        val времяФайла = parts.first().modified
        remember(marks, LogUploadWork.buildArchive(parts).second)

        // Ротация переименовала файл: имя другое, содержимое то же, время не менялось.
        val хвост = File(own, "kelevra-app.log.1")
        assertTrue(File(own, "kelevra-app.log").renameTo(хвост))
        хвост.setLastModified(времяФайла)

        assertTrue(
            "тот же кусок под новым именем повторно не отправляется",
            LogUploadWork.collectParts(listOf(own), marks).isEmpty(),
        )
    }

    // ------------------------- 7. отпечаток хвоста для счётчика безнадёжных отказов

    @Test
    fun `отпечаток одного и того же хвоста не меняется от порядка кусков`() {
        val a = LogUploadWork.Part(File("a.log"), "a.log", offset = 0, length = 10, modified = 1L)
        val b = LogUploadWork.Part(File("b.log"), "b.log", offset = 5, length = 20, modified = 2L)

        assertEquals(
            "порядок в списке не должен влиять на отпечаток",
            LogUploadWork.signature(listOf(a, b)),
            LogUploadWork.signature(listOf(b, a)),
        )
    }

    @Test
    fun `новые байты в том же файле меняют отпечаток хвоста`() {
        val было = LogUploadWork.Part(File("a.log"), "a.log", offset = 0, length = 10, modified = 1L)
        val стало = LogUploadWork.Part(File("a.log"), "a.log", offset = 0, length = 15, modified = 1L)

        assertFalse(
            "хвост с новыми байтами обязан считаться другим — иначе счётчик отказов доедет и живые логи",
            LogUploadWork.signature(listOf(было)) == LogUploadWork.signature(listOf(стало)),
        )
    }

    @Test
    fun `сорванная отправка не съедает кусок — отметки двигаются только после успеха`() {
        val own = tempDir("failed")
        own.put("kelevra-app.log", "важное событие\n")

        val marks = mutableMapOf<String, Mark>()
        val parts = LogUploadWork.collectParts(listOf(own), marks)
        LogUploadWork.buildArchive(parts)
        // Сеть отвалилась: remember не зовём — ровно как runOnce при неудаче.

        val retry = LogUploadWork.collectParts(listOf(own), marks)
        assertEquals(1, retry.size)
        assertEquals(0L, retry.first().offset)
    }
}
