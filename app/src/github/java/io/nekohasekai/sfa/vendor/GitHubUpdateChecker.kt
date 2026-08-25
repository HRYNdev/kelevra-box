package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

class GitHubUpdateChecker : Closeable {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/HRYNdev/kelevra-box/releases"
        private const val RELEASES_PER_PAGE = 100
        // Предохранитель от бесконечного цикла, а не рабочий предел: 1000 релизов —
        // это годы при нынешнем темпе (62 релиза за месяц), а страница берётся только
        // если предыдущая пришла полной.
        private const val RELEASES_MAX_PAGES = 10
        private const val METADATA_FILENAME = "kelevra-version.json"
        private val APK_VERSION = Regex("""^Kelevra-(\d+\.\d+\.\d+)-.*\.apk$""")
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        val releases = getReleases(githubToken)
        var selected: ReleaseCandidate? = null
        val withoutVersionInName = mutableListOf<GitHubRelease>()

        // Версия уже написана в имени APK: app/build.gradle.kts задаёт
        // base.archivesName = "Kelevra-${versionName}". Пока имя разбирается,
        // kelevra-version.json скачивать незачем — он нужен только победителю, ради
        // version_code. Раньше метаданные качались у ВСЕХ релизов страницы, причём
        // до отсева по версии: 29 запросов на одну проверку вместо двух.
        for (release in releases) {
            if (!isReleaseInTrack(release, track)) {
                continue
            }
            val versionName = versionNameFromAssets(release)
            // Кандидатом по имени берём только релиз, у которого метаданные вообще есть:
            // победителю они понадобятся ради version_code, и если их нет, проверка
            // возвращала null и глушила обновление целиком. Список ассетов уже скачан,
            // так что эта проверка не стоит ни одного запроса.
            if (versionName == null || !hasMetadata(release)) {
                withoutVersionInName.add(release)
                continue
            }
            if (!isNewerThanCurrent(versionName)) {
                continue
            }
            val currentBest = selected
            if (currentBest == null || isBetterCandidate(release, versionName, currentBest)) {
                selected = ReleaseCandidate(release, versionName, null)
            }
        }

        // Запасной путь для релизов с чужим именем ассета — как раньше, через метаданные.
        for (release in withoutVersionInName) {
            val metadata = runCatching { downloadMetadata(release) }.getOrNull() ?: continue
            if (!isNewerThanCurrent(metadata.versionName)) {
                continue
            }
            val currentBest = selected
            if (currentBest == null || isBetterCandidate(release, metadata.versionName, currentBest)) {
                selected = ReleaseCandidate(release, metadata.versionName, metadata)
            }
        }

        val release = selected?.release ?: return null
        val metadata = selected.metadata
            ?: runCatching { downloadMetadata(release) }.getOrNull()
            ?: return null

        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val apkAsset = release.assets.find { asset ->
            asset.name.endsWith(".apk") &&
                !asset.name.contains("play") &&
                asset.name.contains("legacy-android-5") == isLegacy
        }

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset?.size ?: 0,
        )
    }

    // Запрос без per_page отдаёт 30 релизов — окно, а не список. Замер 25.08.2026 на живом
    // репозитории: релизов 62, то есть половина не видна вообще. Обновление выбирается
    // максимумом по версии среди ВИДИМЫХ релизов, поэтому выпадение за окно не ошибка,
    // а тишина: обновления просто нет.
    //
    // Порядок страницы почти всегда по убыванию created_at, но «почти»: в тех же 62 релизах
    // одно нарушение — kelevra15 (03.08 19:48) лежит 53-м, ниже kelevra6/5/4 (03.08 12:29-13:29).
    // Черновик стоит нулевым независимо от даты. Значит на порядок опираться нельзя,
    // и единственная защита от тихого пропуска — читать список целиком.
    // В соседнем kelevra-desktop то же окно (там 20) уже привело к тому, что новая установка
    // осталась без ядра: оно лежало 31-м из 32.
    //
    // Читаем страницами по 100, пока страница приходит полной: на сегодняшних 62 релизах
    // это по-прежнему ОДИН запрос. Обрыв на второй и дальше странице не роняет проверку —
    // лучше решить по неполному списку, чем не решить вообще; первая страница обязательна.
    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val releases = mutableListOf<GitHubRelease>()
        for (page in 1..RELEASES_MAX_PAGES) {
            val batch = if (page == 1) {
                getReleasesPage(githubToken, page)
            } else {
                runCatching { getReleasesPage(githubToken, page) }.getOrNull() ?: break
            }
            releases.addAll(batch)
            if (batch.size < RELEASES_PER_PAGE) {
                break
            }
        }
        return releases
    }

    private fun getReleasesPage(githubToken: String, page: Int): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL("$RELEASES_URL?per_page=$RELEASES_PER_PAGE&page=$page")
        request.setHeader("Accept", "application/vnd.github.v3+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap

        return json.decodeFromString(content)
    }

    private fun isReleaseInTrack(release: GitHubRelease, track: UpdateTrack): Boolean {
        if (release.draft) {
            return false
        }
        return when (track) {
            UpdateTrack.STABLE -> !release.prerelease
            UpdateTrack.BETA -> true
        }
    }

    private fun isNewerThanCurrent(versionName: String): Boolean = Libbox.compareSemver(versionName, BuildConfig.VERSION_NAME)

    private fun hasMetadata(release: GitHubRelease): Boolean =
        release.assets.any { it.name == METADATA_FILENAME }

    private fun versionNameFromAssets(release: GitHubRelease): String? {
        for (asset in release.assets) {
            val match = APK_VERSION.find(asset.name) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    private fun isBetterCandidate(release: GitHubRelease, versionName: String, best: ReleaseCandidate): Boolean {
        if (Libbox.compareSemver(versionName, best.versionName)) {
            return true
        }
        if (Libbox.compareSemver(best.versionName, versionName)) {
            return false
        }
        // Равные versionName: прежний разрыв ничьей по version_code. Метаданные качаются
        // только здесь, и на живых релизах этот случай не встречается ни разу.
        val code = runCatching { downloadMetadata(release) }.getOrNull()?.versionCode ?: return false
        val bestMetadata = best.metadata ?: runCatching { downloadMetadata(best.release) }.getOrNull()
        if (bestMetadata == null) {
            return true
        }
        return code > bestMetadata.versionCode
    }

    private fun downloadMetadata(release: GitHubRelease): VersionMetadata? {
        val metadataAsset = release.assets.find { it.name == METADATA_FILENAME }
            ?: return null

        val request = client.newRequest()
        request.setURL(metadataAsset.browserDownloadUrl)
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap

        return json.decodeFromString<VersionMetadata>(content)
    }

    override fun close() {
        client.close()
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val versionName: String,
        val metadata: VersionMetadata?,
    )
}
