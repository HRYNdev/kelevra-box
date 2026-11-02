package io.nekohasekai.sfa.vendor

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.IstochnikZagruzki
import io.nekohasekai.sfa.update.OtvetZagruzki
import io.nekohasekai.sfa.update.ProverkaFayla
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.update.ZagruzkaApk
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ApkDownloader {
    private const val TAG = "KelevraObnovlenie"

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

    /**
     * Сверенный файл обновления: уже скачанный — сразу, иначе скачивается с докачкой
     * ([ZagruzkaApk]). Единственный вход для всех, кому нужен файл: фоновая проверка,
     * повтор после отказа, сборка из пульта и кнопка «Обновить».
     *
     * [peredSetyu] зовётся один раз прямо перед первым сетевым запросом — фоновая работа
     * закрепляется здесь на переднем плане, и только когда действительно есть что качать.
     */
    suspend fun download(
        url: String,
        info: UpdateInfo? = null,
        peredSetyu: suspend () -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val versionCode = info?.versionCode ?: 0
        val apkFile = ZagruzkaApk.poluchit(
            papka = File(Application.application.cacheDir, "updates"),
            versionCode = versionCode,
            ozhidaemyyRazmer = info?.fileSize ?: 0L,
            dokachka = info?.sha256 != null,
            url = url,
            istochnik = IstochnikZagruzki { u, s -> otkryt(u, s) },
            proverit = { proverit(it, info) },
            progress = { skachano, vsego ->
                UpdateState.downloadProgress.value =
                    if (vsego > 0) skachano.toFloat() / vsego.toFloat() else null
            },
            zhurnal = { Log.i(TAG, "$it, версия ${info?.versionName ?: "неизвестна"}") },
            peredSetyu = peredSetyu,
        )
        UpdateState.saveApkPath(apkFile)
        apkFile
    }

    /**
     * Новое соединение на каждую попытку. Клиент ядра здесь не годится: ответ 206 он
     * считает ошибкой, файл открывает с обрезкой, и у него нет срока на чтение — повтор
     * после обрыва висел минутами на мёртвом соединении из его пула.
     */
    private fun otkryt(url: String, sBayta: Long): OtvetZagruzki {
        // В debug адрес подменяется стендом (files/debug-server-url.txt): сборку из пульта
        // качаем с локальной выдачи; в релизе adresSeti возвращает адрес как есть.
        val conn = URL(io.nekohasekai.sfa.Kelevra.adresSeti(url)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", HTTPClient.userAgent)
            // Своё соединение на попытку: старое после обрыва не берётся из пула.
            conn.setRequestProperty("Connection", "close")
            // Без сжатия: иначе длина и куски считаются по сжатому потоку.
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (sBayta > 0) conn.setRequestProperty("Range", "bytes=$sBayta-")
            val kod = conn.responseCode
            val dlina = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val nachalo = ZagruzkaApk.nachaloIzContentRange(conn.getHeaderField("Content-Range"))
            val telo = if (kod in 200..299) conn.inputStream else ByteArrayInputStream(ByteArray(0))
            return OtvetZagruzki(kod, dlina, nachalo, telo) { conn.disconnect() }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}
