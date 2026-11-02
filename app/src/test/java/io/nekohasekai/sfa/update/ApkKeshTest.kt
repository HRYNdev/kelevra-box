package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Уборка кэша обновлений перед скачиванием: недокачанный файл текущей версии — то, с чего
 * продолжится докачка, его стирать нельзя; файлы прошлых версий и остатки старых временных
 * файлов со случайными именами — мусор.
 */
class ApkKeshTest {

    @Test
    fun `уборка не сносит недокачанный и готовый файл текущей версии`() {
        val dir = Files.createTempDirectory("apk-kesh-test").toFile()
        try {
            val apkFile = File(dir, IshodUstanovki.imyaFayla(6)).apply { writeBytes(byteArrayOf(1)) }
            val partFile = File(dir, IshodUstanovki.imyaChastichnogoFayla(6)).apply { writeBytes(ByteArray(1024)) }
            // Сколько бы ни пролежал недокачанный файл, докачка начнётся с него.
            partFile.setLastModified(System.currentTimeMillis() - 7 * 24 * 3600_000L)

            IshodUstanovki.kUdaleniyu(dir.listFiles()!!.toList(), 6).forEach { it.delete() }

            assertTrue(apkFile.exists())
            assertTrue(partFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `уборка сносит файлы прошлой версии и старые временные со случайным именем`() {
        val dir = Files.createTempDirectory("apk-kesh-test").toFile()
        try {
            val staryyApk = File(dir, IshodUstanovki.imyaFayla(5)).apply { writeBytes(byteArrayOf(1)) }
            val staryyPart = File(dir, IshodUstanovki.imyaChastichnogoFayla(5)).apply { writeBytes(byteArrayOf(1)) }
            val sluchaynyyPart = File(dir, IshodUstanovki.imyaFayla(6) + ".3f2a.part").apply { writeBytes(byteArrayOf(1)) }

            IshodUstanovki.kUdaleniyu(dir.listFiles()!!.toList(), 6).forEach { it.delete() }

            assertFalse("файл прошлой версии должен быть снесён", staryyApk.exists())
            assertFalse("недокачанный файл прошлой версии должен быть снесён", staryyPart.exists())
            assertFalse("временный файл со случайным именем должен быть снесён", sluchaynyyPart.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `имя недокачанного файла постоянное для версии`() {
        assertEquals(IshodUstanovki.imyaChastichnogoFayla(711164), IshodUstanovki.imyaChastichnogoFayla(711164))
        assertEquals("update-711164.apk.part", IshodUstanovki.imyaChastichnogoFayla(711164))
    }

    @Test
    fun `версия, тихую установку которой отклонила проверка, ставится с окном`() {
        assertFalse(IshodUstanovki.tihoDlyaVersii(711164, otklonenaDlya = 711164))
        assertTrue("следующая версия снова пробует тихо", IshodUstanovki.tihoDlyaVersii(711165, otklonenaDlya = 711164))
        assertTrue("отказов не было", IshodUstanovki.tihoDlyaVersii(711164, otklonenaDlya = 0))
        assertTrue("номер файла неизвестен — решает обычный путь", IshodUstanovki.tihoDlyaVersii(0, otklonenaDlya = 0))
    }
}
