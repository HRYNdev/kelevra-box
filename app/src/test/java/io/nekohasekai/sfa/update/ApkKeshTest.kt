package io.nekohasekai.sfa.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Уборка кэша обновлений перед скачиванием не должна сносить `.part`, в который прямо
 * сейчас пишет другой воркер. Входов в `ApkDownloader.download()` два — `UpdateWorker`
 * и `UpdatePovtorWork` — и они не сериализованы между собой (#14).
 */
class ApkKeshTest {

    @Test
    fun `уборка не сносит свежий part другого воркера`() {
        val dir = Files.createTempDirectory("apk-kesh-test").toFile()
        try {
            val apkFile = File(dir, IshodUstanovki.imyaFayla(5))
            val partFile = File(dir, apkFile.name + ".part")
            partFile.writeBytes(ByteArray(1024)) // первый воркер уже пишет .part

            // второй воркер входит в download() той же версии и делает уборку кэша
            IshodUstanovki.kUdaleniyu(dir.listFiles()!!.toList(), apkFile.name, System.currentTimeMillis())
                .forEach { it.delete() }

            assertTrue("part-файл первого воркера пережил уборку второго", partFile.exists())
            // первый воркер дописал файл и переименовывает его в готовый apk
            assertTrue("переименование не удалось: файл потерян", partFile.renameTo(apkFile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `уборка по-прежнему сносит файл прошлой версии и протухший part`() {
        val dir = Files.createTempDirectory("apk-kesh-test").toFile()
        try {
            val apkFile = File(dir, IshodUstanovki.imyaFayla(6))
            val staryyApk = File(dir, IshodUstanovki.imyaFayla(5)).apply { writeBytes(byteArrayOf(1)) }
            val protuhshiyPart = File(dir, apkFile.name + ".old.part").apply { writeBytes(byteArrayOf(1)) }
            val seychas = System.currentTimeMillis()
            val porogMs = 5 * 60_000L
            // «протух»: последняя запись в него была раньше порога уборки
            protuhshiyPart.setLastModified(seychas - porogMs - 1000L)

            IshodUstanovki.kUdaleniyu(dir.listFiles()!!.toList(), apkFile.name, seychas, porogMs)
                .forEach { it.delete() }

            assertFalse("файл прошлой версии должен быть снесён", staryyApk.exists())
            assertFalse("протухший .part должен быть снесён", protuhshiyPart.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
