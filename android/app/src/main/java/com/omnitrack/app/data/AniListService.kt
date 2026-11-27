package com.omnitrack.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.omnitrack.app.domain.model.UserStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

private const val CLIENT_ID = "58236"

internal object AniListRequestGate {
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun <T> execute(retryAfterMillis: (T) -> Long = { 0L }, request: suspend () -> T): T =
        mutex.withLock {
            val wait = nextRequestAt - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            val result = try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nextRequestAt = SystemClock.elapsedRealtime() + 2_000L
                throw e
            }
            val now = SystemClock.elapsedRealtime()
            val retry = retryAfterMillis(result).coerceIn(0L, Long.MAX_VALUE - now)
            nextRequestAt = maxOf(now + 2_000L, now + retry)
            result
        }
}

@Serializable
data class AniListCredentials(
    val accessToken: String = "",
    val userId: Int = 0,
    val username: String = "",
    val expiresAt: Long = 0,
    val authorizationPending: Boolean = false,
    val avatarUrl: String? = null
)

class AniListPreferences(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "anilist_credentials"))
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("omnitrack_anilist", null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                "omnitrack_anilist", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): AniListCredentials {
        val bytes = try { file.readFully() } catch (e: FileNotFoundException) { return AniListCredentials() }
        require(bytes.size > 12) { "AniList credentials are damaged; reconnect your account" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }

    fun write(credentials: AniListCredentials) {
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

    fun disconnect() {
        file.delete()
        check(!file.baseFile.exists()) { "Could not remove AniList credentials" }
    }
}

@Serializable
data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)

@Serializable
data class AniListCover(val large: String? = null)

@Serializable
data class AniListStudio(val name: String)

@Serializable
data class AniListStudios(val nodes: List<AniListStudio> = emptyList())

@Serializable
data class AniListMedia(
    val id: Int,
    val title: AniListTitle,
    val synonyms: List<String> = emptyList(),
    val episodes: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val studios: AniListStudios? = null,
    val coverImage: AniListCover? = null,
    val siteUrl: String? = null
)

@Serializable
data class AniListLibraryEntry(
    val id: Int,
    val mediaId: Int,
    val status: String? = null,
    val progress: Int? = null,
    val updatedAt: Long? = null,
    val media: AniListMedia
) {
    val displayTitle: String get() = media.title.english ?: media.title.romaji ?: "AniList #$mediaId"
    val sourceUrl: String get() = media.siteUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: "https://anilist.co/anime/$mediaId"
}

class AniListService(private val preferences: AniListPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(Android) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    fun authorizationUrl(): String {
        preferences.write(preferences.read().copy(authorizationPending = true))
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token"
    }

    suspend fun completeAuthorization(uri: Uri): AniListCredentials = withContext(Dispatchers.IO) {
        require(uri.scheme == "com.omnitrack.app" && uri.host == null && uri.path == "/anilist/oauth") {
            "Invalid AniList callback"
        }
        val saved = preferences.read()
        require(saved.authorizationPending) { "AniList sign-in was not initiated; try again" }
        preferences.write(saved.copy(authorizationPending = false))
        val fragment = uri.encodedFragment ?: error("AniList sign-in was cancelled or denied")
        val parameters = Uri.parse("https://localhost/?$fragment")
        val token = parameters.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }
            ?: error("AniList sign-in was cancelled or denied")
        val tokenType = parameters.getQueryParameter("token_type")
        require(tokenType == null || tokenType.equals("Bearer", true)) { "Unsupported AniList token type" }
        val expiry = parameters.getQueryParameter("expires_in")?.let {
            val seconds = it.toLongOrNull()
            require(seconds != null && seconds > 0 && seconds <= (Long.MAX_VALUE - System.currentTimeMillis()) / 1000) {
                "Invalid AniList token expiry"
            }
            System.currentTimeMillis() + seconds * 1000
        } ?: 0L
        val data = request(token, "query { Viewer { id name avatar { medium } } }")
        val viewer = data["Viewer"]?.jsonObject ?: error("AniList returned an invalid account")
        val id = viewer["id"]?.jsonPrimitive?.int ?: error("AniList returned an invalid account")
        val name = viewer["name"]?.jsonPrimitive?.content ?: error("AniList returned an invalid account")
        require(id > 0 && name.isNotBlank()) { "AniList returned an invalid account" }
        val avatar = (viewer["avatar"] as? JsonObject)?.get("medium")?.jsonPrimitive?.content
        AniListCredentials(token, id, name, expiry, avatarUrl = avatar).also { preferences.write(it) }
    }

    suspend fun library(credentials: AniListCredentials): List<AniListLibraryEntry> = withContext(Dispatchers.IO) {
        check(credentials.accessToken.isNotBlank() && credentials.userId > 0) { "Connect AniList first" }
        check(credentials.expiresAt == 0L || credentials.expiresAt > System.currentTimeMillis()) {
            "AniList authorization expired or was revoked; reconnect your account"
        }
        val query = """
            query (${'$'}userId: Int, ${'$'}chunk: Int) {
              MediaListCollection(userId: ${'$'}userId, type: ANIME, chunk: ${'$'}chunk, perChunk: 500) {
                hasNextChunk
                lists { entries { id mediaId status progress updatedAt
                  media { id siteUrl title { romaji english native } synonyms episodes format status coverImage { large }
                    studios(isMain: true) { nodes { name } }
                  }
                } }
              }
            }
        """.trimIndent()
        val entries = mutableListOf<AniListLibraryEntry>()
        var chunk = 1
        var hasNext: String
        do {
            val data = request(credentials.accessToken, query, buildJsonObject {
                put("userId", credentials.userId)
                put("chunk", chunk)
            })
            val collection = data["MediaListCollection"]?.jsonObject
                ?: error("AniList returned an invalid library")
            for (list in collection["lists"]?.jsonArray ?: error("AniList returned an invalid library")) {
                for (entry in list.jsonObject["entries"]?.jsonArray ?: error("AniList returned an invalid library")) {
                    val decoded = json.decodeFromJsonElement(AniListLibraryEntry.serializer(), entry)
                    check(decoded.id > 0 && decoded.mediaId > 0 && decoded.media.id == decoded.mediaId &&
                        (decoded.progress == null || decoded.progress >= 0)) {
                        "AniList returned an invalid library entry"
                    }
                    entries.add(decoded)
                }
            }
            hasNext = collection["hasNextChunk"]?.jsonPrimitive?.content
                ?: error("AniList returned invalid pagination")
            check(hasNext == "true" || hasNext == "false") { "AniList returned invalid pagination" }
            chunk++
        } while (hasNext == "true")
        return@withContext entries.distinctBy { it.id }
    }

    suspend fun updateStatus(credentials: AniListCredentials, mediaId: Int, status: UserStatus) =
        withContext(Dispatchers.IO) {
            check(credentials.accessToken.isNotBlank() && credentials.userId > 0) { "Connect AniList first" }
            check(credentials.expiresAt == 0L || credentials.expiresAt > System.currentTimeMillis()) {
                "AniList authorization expired or was revoked; reconnect your account"
            }
            val remoteStatus = when (status) {
                UserStatus.IN_PROGRESS -> "CURRENT"
                UserStatus.PLANNING -> "PLANNING"
                UserStatus.PAUSED -> "PAUSED"
                UserStatus.COMPLETED -> "COMPLETED"
                UserStatus.DROPPED -> "DROPPED"
            }
            val data = request(credentials.accessToken, """
                mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus) {
                  SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status) { mediaId status }
                }
            """.trimIndent(), buildJsonObject {
                put("mediaId", mediaId)
                put("status", remoteStatus)
            })
            val saved = data["SaveMediaListEntry"]?.jsonObject
                ?: error("AniList did not update the status")
            check(saved["mediaId"]?.jsonPrimitive?.int == mediaId &&
                saved["status"]?.jsonPrimitive?.content == remoteStatus) {
                "AniList returned an unexpected status"
            }
        }

    private suspend fun request(token: String, query: String, variables: JsonObject = buildJsonObject { }): JsonObject {
        suspend fun send() = AniListRequestGate.execute(
            retryAfterMillis = { response ->
                if (response.status.value == 429) {
                    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceAtLeast(0)
                        ?.coerceAtMost(Long.MAX_VALUE / 1_000)?.times(1_000) ?: 60_000L
                } else 0L
            }
        ) {
            client.post("https://graphql.anilist.co") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("query", query); put("variables", variables) }.toString())
            }
        }
        var response = send()
        if (response.status.value == 429) response = send()
        if (response.status.value == 401 || response.status.value == 403) {
            error("AniList authorization expired or was revoked; reconnect your account")
        }
        if (response.status.value == 429) {
            error("AniList rate limit reached (HTTP 429); retry shortly")
        }
        check(response.status.isSuccess()) { "AniList request failed (HTTP ${response.status.value})" }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        check(root["errors"] == null || root["errors"]?.jsonArray?.isEmpty() == true) {
            "AniList returned a GraphQL error"
        }
        return root["data"]?.jsonObject ?: error("AniList returned an invalid response")
    }

    fun disconnect() = preferences.disconnect()
    fun close() = client.close()
}
