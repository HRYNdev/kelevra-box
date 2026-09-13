package io.nekohasekai.sfa.vendor

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.nekohasekai.libbox.HTTPResponseWriteToProgressHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.FaylNeGoden
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.ProverkaFayla
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

class ApkDownloader : Closeable {
    companion object {
        private const val TAG = "KelevraObnovlenie"

        /** Вторая попытка ловит разовый обрыв; дальше решает лестница повторов. */
        private const val POPYTOK = 2

        /**
         * Проверка файла до передачи системе. null — годен, иначе причина словами для журнала.
         *
         * Отказ «не APK» приходил от системы уже после передачи файла, а повтор тем же файлом
         * ничего не менял: файл лежал в кэше и брался снова. Проверить самим дешевле: размер
         * и хеш из списка релизов, затем разбор тем же PackageManager, которым ставит система.
         */
        fun proverit(file: File, info: UpdateInfo?): String? {
            val ozhidaemyyHesh = info?.sha256
            val hesh = if (ozhidaemyyHesh != null) sha256(file) else null
            when (IshodUstanovki.proveritFayl(file.length(), info?.fileSize ?: 0L, hesh, ozhidaemyyHesh)) {
                ProverkaFayla.OK -> Unit
                ProverkaFayla.PUSTOY -> return "пустой файл"
                ProverkaFayla.RAZMER -> return "размер ${file.length()} вместо ${info?.fileSize}"
                ProverkaFayla.HESH -> return "хеш не совпал со списком релизов"
            }
            val pm = Application.application.packageManager
            val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            } ?: return "система не разбирает файл"
            if (archive.packageName != Application.application.packageName) {
                return "в файле другое приложение: ${archive.packageName}"
            }
            // Номер из файла не сверяется с номером из метаданных релиза один в один: при
            // расхождении это остановило бы все обновления. Достаточно, что он новее.
            val code = PackageInfoCompat.getLongVersionCode(archive)
            if (code <= BuildConfig.VERSION_CODE.toLong()) {
                return "в файле версия $code, не новее установленной"
            }
            return null
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    suspend fun download(url: String, info: UpdateInfo? = null): File = withContext(Dispatchers.IO) {
        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File(cacheDir, IshodUstanovki.imyaFayla(info?.versionCode ?: 0))
        val partFile = File(cacheDir, apkFile.name + ".part")
        // Файлы прошлых версий и недокачанные остатки больше не нужны.
        cacheDir.listFiles()?.forEach { if (it.name != apkFile.name) it.delete() }

        // Качаем во временный файл и переименовываем только после проверки: под постоянным
        // именем никогда не лежит недокачанное.
        var prichina = "не скачан"
        for (popytka in 1..POPYTOK) {
            partFile.delete()
            try {
                skachat(url, partFile)
            } catch (e: CancellationException) {
                partFile.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "скачивание обновления сорвалось (попытка $popytka): ${e.message}")
                if (popytka == POPYTOK) {
                    partFile.delete()
                    throw e
                }
                continue
            }
            val otkaz = proverit(partFile, info)
            if (otkaz == null) {
                apkFile.delete()
                if (!partFile.renameTo(apkFile)) {
                    partFile.copyTo(apkFile, overwrite = true)
                    partFile.delete()
                }
                Log.i(
                    TAG,
                    "файл обновления скачан и проверен: версия ${info?.versionName ?: "неизвестна"}, " +
                        "${apkFile.length()} байт, хеш ${if (info?.sha256 != null) "сверен" else "в релизе не указан"}",
                )
                UpdateState.saveApkPath(apkFile)
                return@withContext apkFile
            }
            prichina = otkaz
            Log.w(TAG, "файл обновления не прошёл проверку (попытка $popytka): $otkaz")
        }
        partFile.delete()
        throw FaylNeGoden(prichina)
    }

    private fun skachat(url: String, target: File) {
        val request = client.newRequest()
        request.setUserAgent(HTTPClient.userAgent)
        request.setURL(url)

        val response = request.execute()
        response.writeToWithProgress(
            target.absolutePath,
            object : HTTPResponseWriteToProgressHandler {
                override fun update(progress: Long, total: Long) {
                    UpdateState.downloadProgress.value =
                        if (total > 0) progress.toFloat() / total.toFloat() else null
                }
            },
        )
    }

    override fun close() {
        client.close()
    }
}
