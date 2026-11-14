package com.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

@Serializable
data class SpotifyDirectPreviewInfo(
    @SerialName("track_id") val trackId: String,
    @SerialName("title") val title: String,
    @SerialName("artist") val artist: String,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("is_drm_protected") val isDrmProtected: Boolean = true
)

/**
 * Автономный клиент модуля `:spotify` с поддержкой раздельных OkHttp-конфигураций:
 * - `apiOkHttpInitializer` — для запросов к Web/GraphQL API и получения Embed/метаданных;
 * - `contentOkHttpInitializer` — для обращения к аудио-CDN (`p.scdn.co`, `audio-ak-spotify-com.akamaized.net`).
 */
class SpotifyModuleClient(
    private val apiOkHttpInitializer: (OkHttpClient.Builder) -> OkHttpClient.Builder = { it },
    private val contentOkHttpInitializer: (OkHttpClient.Builder) -> OkHttpClient.Builder = { it },
    private val accessTokenProvider: () -> String? = { null }
) {
    companion object {
        const val TRACK_ID_PREFIX = "sp_"
        private const val REST_API_BASE = "https://api.spotify.com/v1"
        private const val EMBED_TRACK_URL = "https://open.spotify.com/embed/track"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val nextDataRegex =
            Regex("""<script id="__NEXT_DATA__" type="application/json">(.+?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val audioPreviewRegex =
            Regex(""""audioPreview"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""")
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val apiClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SpotifyModuleClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                accessTokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(25, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    apiOkHttpInitializer(this)
                }
            }
        }
    }

    val contentClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SpotifyModuleClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 25_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
            }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(25, TimeUnit.SECONDS)
                    writeTimeout(20, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    contentOkHttpInitializer(this)
                }
            }
        }
    }

    /**
     * Извлекает публичные метаданные трека и unencrypted CDN preview (`p.scdn.co/mp3-preview/...`)
     * со страницы Spotify Embed без необходимости платной подписки, работая через API-прокси.
     */
    suspend fun fetchTrackInfoFromEmbed(spotifyTrackId: String): Result<SpotifyDirectPreviewInfo> = runCatching {
        val cleanId = spotifyTrackId
            .removePrefix(TRACK_ID_PREFIX)
            .removePrefix("spotify:track:")
            .trim()
        val html = apiClient.get("$EMBED_TRACK_URL/$cleanId") {
            parameter("utm_source", "generator")
        }.bodyAsText()

        val nextDataMatch = nextDataRegex.find(html)
        if (nextDataMatch != null) {
            val root = json.parseToJsonElement(nextDataMatch.groupValues[1]).jsonObject
            val entity = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject

            if (entity != null) {
                val title = entity.str("title") ?: entity.str("name") ?: ""
                val artists = entity.arr("artists")
                    ?.mapNotNull { it.jsonObject.str("name") }
                    ?.joinToString(", ")
                    .orEmpty()
                val durationMs = entity.int("duration") ?: entity.int("duration_ms") ?: 0
                val previewUrl = entity.obj("audioPreview")?.str("url")
                val coverUrl = entity.obj("visualIdentity")?.obj("image")
                    ?.arr("0")?.firstOrNull()?.jsonObject?.str("url")
                    ?: entity.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")

                return@runCatching SpotifyDirectPreviewInfo(
                    trackId = cleanId,
                    title = title,
                    artist = artists,
                    durationMs = durationMs,
                    artworkUrl = coverUrl,
                    previewUrl = previewUrl,
                    isDrmProtected = true
                )
            }
        }

        val fallbackPreview = audioPreviewRegex.find(html)?.groupValues?.getOrNull(1)
        SpotifyDirectPreviewInfo(
            trackId = cleanId,
            title = "",
            artist = "",
            durationMs = 0,
            artworkUrl = null,
            previewUrl = fallbackPreview,
            isDrmProtected = true
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.arr(key: String): JsonArray? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonArray }.getOrNull()
}
