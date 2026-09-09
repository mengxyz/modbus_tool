package dev.modbustool

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val githubJson = Json { ignoreUnknownKeys = true }

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val version: String) : UpdateCheckState
    data class Available(val version: String, val releaseUrl: String) : UpdateCheckState
    data object NoPublishedReleases : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

internal class UpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION,
    private val fetchLatest: () -> FetchResult = ::fetchLatestRelease,
) {
    suspend fun check(): UpdateCheckState = withContext(Dispatchers.IO) {
        when (val result = runCatching(fetchLatest).getOrElse { FetchResult.Failure(it.message ?: "Unknown error") }) {
            is FetchResult.Release -> compareRelease(currentVersion, result.tag, result.url)
            FetchResult.NotFound -> UpdateCheckState.NoPublishedReleases
            is FetchResult.Failure -> UpdateCheckState.Failed(result.message)
        }
    }
}

internal sealed interface FetchResult {
    data class Release(val tag: String, val url: String) : FetchResult
    data object NotFound : FetchResult
    data class Failure(val message: String) : FetchResult
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val url: String,
)

private fun fetchLatestRelease(): FetchResult {
    val connection = URI(BuildConfig.LATEST_RELEASE_API).toURL().openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "modbus-tool-update-check")
        when (val status = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val release = githubJson.decodeFromString<GitHubRelease>(
                    connection.inputStream.bufferedReader().use { it.readText() },
                )
                FetchResult.Release(release.tag, release.url)
            }
            HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.NotFound
            else -> FetchResult.Failure("GitHub returned HTTP $status")
        }
    } finally {
        connection.disconnect()
    }
}

internal fun compareRelease(current: String, latest: String, releaseUrl: String): UpdateCheckState {
    val currentVersion = SemanticVersion.parse(current)
        ?: return UpdateCheckState.Failed("Invalid app version: $current")
    val latestVersion = SemanticVersion.parse(latest)
        ?: return UpdateCheckState.Failed("Release tag is not semantic versioning: $latest")
    return if (latestVersion > currentVersion) {
        UpdateCheckState.Available(latest, releaseUrl)
    } else {
        UpdateCheckState.UpToDate(current)
    }
}

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return when {
                preRelease.isEmpty() && other.preRelease.isNotEmpty() -> 1
                preRelease.isNotEmpty() && other.preRelease.isEmpty() -> -1
                else -> 0
            }
        }
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList(),
            )
        }
    }
}
