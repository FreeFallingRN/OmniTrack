package com.omnitrack.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String?,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String
)

object UpdateChecker {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    // Replace with your actual GitHub repository (e.g., "owner/repo")
    private const val GITHUB_REPO = "your-username/OmniTrack"

    suspend fun checkForUpdate(currentVersion: String): Triple<String, String, String>? {
        return try {
            val response: String = client.get("https://api.github.com/repos/$GITHUB_REPO/releases/latest") {
                header("Accept", "application/vnd.github+json")
            }.body()

            val release = json.decodeFromString<GitHubRelease>(response)
            val latestVersion = release.tag_name.removePrefix("v")

            if (latestVersion != currentVersion) {
                // Find the APK asset in the release
                val apkAsset = release.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
                if (apkAsset != null) {
                    Triple(latestVersion, release.name ?: release.tag_name, apkAsset.browser_download_url)
                } else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun downloadAndInstallApk(context: Context, apkUrl: String, onProgress: (Float) -> Unit): Boolean {
        return try {
            val apkFile = File(context.cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val response: HttpResponse = client.get(apkUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null) {
                        if (contentLength > 0) {
                            onProgress(bytesSentTotal.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }

            val channel = response.bodyAsChannel()
            // Stream bytes to file
            apkFile.outputStream().use { output ->
                while (!channel.isClosedForRead) {
                    val packet = channel.readBuffer(DEFAULT_BUFFER_SIZE.toLong())
                    while (!packet.exhausted()) {
                        val bytes = packet.readByteArray()
                        output.write(bytes)
                    }
                }
            }

            triggerInstall(context, apkFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun triggerInstall(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}