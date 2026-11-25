package com.omnitrack.app.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val CLIENT_ID = "JuYmRhTqoISTeiJryvzCxEpoOhmwDDhR"
private const val REDIRECT_URI = "com.omnitrack.app:/mangabaka/oauth"

@Serializable
data class MangaBakaCredentials(
    val accessToken: String = "",
    val userId: String = "",
    val username: String = "",
    val state: String = "",
    val verifier: String = ""
)

class MangaBakaPreferences(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mangabaka_credentials"))
    private val links = context.getSharedPreferences("mangabaka_links", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("omnitrack_mangabaka", null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    "omnitrack_mangabaka",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun read(): MangaBakaCredentials {
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return MangaBakaCredentials()
        }
        require(bytes.size > 12) { "MangaBaka credentials are damaged; reconnect your account" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }

    fun write(credentials: MangaBakaCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun links(userId: String): Map<Long, MangaBakaLink> {
        val prefix = "$userId:"
        return links.all.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || value !is String) return@mapNotNull null
            val id = key.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
            id to json.decodeFromString<MangaBakaLink>(value)
        }.toMap()
    }

    fun link(userId: String, seriesId: Long, entryId: String, offset: Double) {
        check(links.edit().putString("$userId:$seriesId", json.encodeToString(MangaBakaLink(entryId, offset))).commit()) {
            "Could not save MangaBaka link"
        }
    }

    fun unlink(userId: String, seriesId: Long) {
        check(links.edit().remove("$userId:$seriesId").commit()) { "Could not remove MangaBaka link" }
    }
}

@Serializable
data class MangaBakaLink(val entryId: String, val offset: Double)

@Serializable
data class MangaBakaTitle(
    val entry: MangaBakaLibraryEntry,
    val series: MangaBakaSeries
) {
    val title: String
        get() = series.titles?.firstOrNull { it.language == "en" && it.primary == true }?.title
            ?: series.titles?.firstOrNull { it.language == "en" }?.title
            ?: series.titles?.firstOrNull()?.title
            ?: "MangaBaka #${series.id}"
}

@Serializable
data class MangaBakaLibraryEntry(
    @SerialName("series_id") val seriesId: Long,
    val state: String? = null,
    @SerialName("progress_chapter") val progress: Double? = null
)

@Serializable
data class MangaBakaSeries(
    val id: Long,
    @SerialName("canonical_url") val url: String,
    val type: String,
    val status: String,
    @SerialName("total_chapters") val totalChapters: Double? = null,
    val titles: List<MangaBakaSeriesTitle>? = null,
    val cover: MangaBakaCover? = null
)

@Serializable
data class MangaBakaSeriesTitle(
    val title: String,
    val language: String,
    @SerialName("is_primary") val primary: Boolean? = false
)

@Serializable
data class MangaBakaCover(val x250: String? = null, val raw: String? = null)

@Serializable
private data class LibraryPage(val data: List<MangaBakaTitle>, val pagination: Pagination)
@Serializable
private data class Pagination(val next: String? = null)
@Serializable
private data class TokenResponse(@SerialName("access_token") val accessToken: String)
@Serializable
private data class UserInfo(val sub: String, val name: String? = null)

class MangaBakaService(private val preferences: MangaBakaPreferences) {
    private val client = HttpClient(Android) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    fun authorizationUrl(): String {
        fun randomValue(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        val state = randomValue()
        val verifier = randomValue()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        preferences.write(preferences.read().copy(state = state, verifier = verifier))
        return Uri.parse("https://mangabaka.org/auth/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid profile library.read")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    suspend fun completeAuthorization(uri: Uri): MangaBakaCredentials = withContext(Dispatchers.IO) {
        require(uri.scheme == "com.omnitrack.app" && uri.host == null && uri.path == "/mangabaka/oauth") {
            "Invalid MangaBaka callback"
        }
        val saved = preferences.read()
        require(saved.state.isNotBlank() && uri.getQueryParameter("state") == saved.state) {
            "MangaBaka sign-in expired or did not match; try again"
        }
        val code = uri.getQueryParameter("code")
        require(!code.isNullOrBlank()) { "MangaBaka sign-in was cancelled or denied" }
        preferences.write(saved.copy(state = "", verifier = ""))
        val response = client.submitForm("https://mangabaka.org/auth/oauth2/token", Parameters.build {
            append("grant_type", "authorization_code")
            append("client_id", CLIENT_ID)
            append("redirect_uri", REDIRECT_URI)
            append("code", code)
            append("code_verifier", saved.verifier)
        })
        check(response.status.isSuccess()) { "MangaBaka sign-in failed (HTTP ${response.status.value})" }
        val token = response.body<TokenResponse>().accessToken
        val identityResponse = client.get("https://mangabaka.org/auth/oauth2/userinfo") {
            header("Authorization", "Bearer $token")
        }
        check(identityResponse.status.isSuccess()) { "Could not identify MangaBaka account (HTTP ${identityResponse.status.value})" }
        val identity = identityResponse.body<UserInfo>()
        require(identity.sub.isNotBlank()) { "MangaBaka returned an invalid account" }
        MangaBakaCredentials(accessToken = token, userId = identity.sub, username = identity.name.orEmpty())
            .also { preferences.write(it) }
    }

    suspend fun library(credentials: MangaBakaCredentials): List<MangaBakaTitle> = withContext(Dispatchers.IO) {
        check(credentials.accessToken.isNotBlank() && credentials.userId.isNotBlank()) {
            "Connect your MangaBaka account first"
        }
        var next: String? = "https://api.mangabaka.org/v2/my/library?limit=100"
        val visited = mutableSetOf<String>()
        val results = mutableListOf<MangaBakaTitle>()
        while (next != null) {
            val url = next
            val parsed = Uri.parse(url)
            check(parsed.scheme == "https" && parsed.host == "api.mangabaka.org" &&
                parsed.port == -1 && parsed.path == "/v2/my/library" && parsed.userInfo == null &&
                visited.add(url)) { "MangaBaka returned an invalid pagination link" }
            val response = client.get(url) { header("Authorization", "Bearer ${credentials.accessToken}") }
            if (response.status.value == 401 || response.status.value == 403) {
                error("MangaBaka authorization expired or was revoked; connect again")
            }
            check(response.status.isSuccess()) { "MangaBaka library request failed (HTTP ${response.status.value})" }
            val page = response.body<LibraryPage>()
            results.addAll(page.data)
            next = page.pagination.next
        }
        results
    }

    fun close() = client.close()
}
