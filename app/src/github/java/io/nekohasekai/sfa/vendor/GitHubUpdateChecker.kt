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
            if (versionName == null) {
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

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL(RELEASES_URL)
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
