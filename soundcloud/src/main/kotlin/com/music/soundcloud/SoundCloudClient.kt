package com.music.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable
data class ScUserDto(
    @SerialName("id") val id: Long = 0L,
    @SerialName("username") val username: String = "",
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("followers_count") val followersCount: Int? = null,
    @SerialName("verified") val verified: Boolean = false
) {
    fun highResAvatarUrl(): String? = SoundCloudArtwork.toHighRes(avatarUrl)
}

@Serializable
data class ScFormatDto(
    @SerialName("protocol") val protocol: String = "",
    @SerialName("mime_type") val mimeType: String = ""
)

@Serializable
data class ScTranscodingDto(
    @SerialName("url") val url: String = "",
    @SerialName("preset") val preset: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("snipped") val snipped: Boolean = false,
    @SerialName("format") val format: ScFormatDto? = null,
    @SerialName("quality") val quality: String? = null
)

@Serializable
data class ScMediaDto(
    @SerialName("transcodings") val transcodings: List<ScTranscodingDto> = emptyList()
)

@Serializable
data class ScPublisherMetadataDto(
    @SerialName("artist") val artist: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("explicit") val explicit: Boolean? = null
)

@Serializable
data class ScTrackDto(
    @SerialName("id") val id: Long = 0L,
    @SerialName("title") val title: String = "",
    @SerialName("duration") val durationMs: Long = 0L,
    @SerialName("full_duration") val fullDurationMs: Long? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("waveform_url") val waveformUrl: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("playback_count") val playbackCount: Long? = null,
    @SerialName("likes_count") val likesCount: Long? = null,
    @SerialName("user") val user: ScUserDto? = null,
    @SerialName("media") val media: ScMediaDto? = null,
    @SerialName("publisher_metadata") val publisherMetadata: ScPublisherMetadataDto? = null,
    @SerialName("streamable") val streamable: Boolean = true,
    @SerialName("policy") val policy: String? = null
) {
    fun highResArtworkUrl(): String? =
        SoundCloudArtwork.toHighRes(artworkUrl ?: user?.avatarUrl)

    fun effectiveArtistName(): String =
        publisherMetadata?.artist?.takeIf { it.isNotBlank() }
            ?: user?.username?.takeIf { it.isNotBlank() }
            ?: "SoundCloud Artist"
}

@Serializable
data class ScPlaylistDto(
    @SerialName("id") val id: Long = 0L,
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("is_album") val isAlbum: Boolean = false,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("user") val user: ScUserDto? = null,
    @SerialName("tracks") val tracks: List<ScTrackDto> = emptyList()
) {
    fun highResArtworkUrl(): String? =
        SoundCloudArtwork.toHighRes(
            artworkUrl
                ?: tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
                ?: user?.avatarUrl
        )
}

@Serializable
data class ScSearchCollectionDto<T>(
    @SerialName("collection") val collection: List<T> = emptyList(),
    @SerialName("next_href") val nextHref: String? = null,
    @SerialName("total_results") val totalResults: Int? = null
)

@Serializable
data class ScStreamUrlResponseDto(
    @SerialName("url") val url: String = ""
)

@Serializable
data class ScResolvedStream(
    val streamUrl: String,
    val protocol: String,
    val mimeType: String,
    val isHls: Boolean,
    val durationMs: Long,
    val bitrate: Int,
    val track: ScTrackDto
)

object SoundCloudArtwork {
    private val sizeSuffixRegex =
        Regex("-(large|t300x300|crop|t67x67|badge|small|tiny|mini|t50x50|t200x200)\\.(jpg|png|jpeg)", RegexOption.IGNORE_CASE)

    fun toHighRes(url: String?, targetSize: String = "t500x500"): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (sizeSuffixRegex.containsMatchIn(raw)) {
            sizeSuffixRegex.replace(raw) { match ->
                val ext = match.groupValues.getOrElse(2) { "jpg" }
                "-$targetSize.$ext"
            }
        } else {
            raw
        }
    }
}

class SoundCloudClient(
    private val apiOkHttpInitializer: (OkHttpClient.Builder) -> OkHttpClient.Builder = { it },
    private val contentOkHttpInitializer: (OkHttpClient.Builder) -> OkHttpClient.Builder = { it },
    private val oauthTokenProvider: () -> String? = { null }
) {
    companion object {
        private const val API_V2_BASE = "https://api-v2.soundcloud.com"
        private const val WEB_BASE = "https://soundcloud.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val FALLBACK_CLIENT_IDS = listOf(
            "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX",
            "a3e059563d7fd3372b49b37f00a00bcf",
            "fDoItMDbsbZz8dY16ZzARCZmzgHBPotA",
            "LBCcHmRB8XSStWL6wKH2HPACspQlXg2P"
        )

        private val scriptSrcRegex =
            Regex("""<script[^>]+src=["'](https://a-v2\.sndcdn\.com/assets/[^"']+\.js)["']""")
        private val clientIdRegexes = listOf(
            Regex("""client_id\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""client_id=["']([a-zA-Z0-9]{32})["']"""),
            Regex(""""client_id"\s*:\s*"([a-zA-Z0-9]{32})"""")
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val clientIdMutex = Mutex()

    @Volatile
    private var cachedClientId: String? = null

    @Volatile
    private var cachedClientIdTimestampMs: Long = 0L

    private val apiHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SoundCloudClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,ru;q=0.8")
                header("Origin", WEB_BASE)
                header("Referer", "$WEB_BASE/")
                oauthTokenProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                    val normalized = if (token.startsWith("OAuth ", ignoreCase = true)) token else "OAuth $token"
                    header(HttpHeaders.Authorization, normalized)
                }
            }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(20, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    apiOkHttpInitializer(this)
                }
            }
        }
    }

    private val contentHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SoundCloudClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "*/*")
                header("Origin", WEB_BASE)
                header("Referer", "$WEB_BASE/")
                oauthTokenProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                    val normalized = if (token.startsWith("OAuth ", ignoreCase = true)) token else "OAuth $token"
                    header(HttpHeaders.Authorization, normalized)
                }
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

    suspend fun getClientId(forceRefresh: Boolean = false): String = clientIdMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedClientId != null && (now - cachedClientIdTimestampMs) < 3_600_000L) {
            return@withLock cachedClientId!!
        }

        runCatching {
            val homeHtml = apiHttpClient.get(WEB_BASE).bodyAsText()
            val assetUrls = scriptSrcRegex.findAll(homeHtml).map { it.groupValues[1] }.toList().takeLast(6).reversed()
            for (assetUrl in assetUrls) {
                val jsText = runCatching { apiHttpClient.get(assetUrl).bodyAsText() }.getOrNull() ?: continue
                for (regex in clientIdRegexes) {
                    val match = regex.find(jsText)
                    if (match != null) {
                        val extracted = match.groupValues[1]
                        if (extracted.length == 32) {
                            cachedClientId = extracted
                            cachedClientIdTimestampMs = now
                            return@withLock extracted
                        }
                    }
                }
            }
        }

        val fallback = FALLBACK_CLIENT_IDS.first()
        cachedClientId = fallback
        cachedClientIdTimestampMs = now
        fallback
    }

    suspend fun searchTracks(
        query: String,
        limit: Int = 25,
        offset: Int = 0
    ): Result<ScSearchCollectionDto<ScTrackDto>> = runCatching {
        executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/search/tracks") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("linked_partitioning", 1)
            }.body()
        }
    }

    suspend fun searchAlbums(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<ScSearchCollectionDto<ScPlaylistDto>> = runCatching {
        executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/search/albums") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("linked_partitioning", 1)
            }.body()
        }
    }

    suspend fun searchPlaylists(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<ScSearchCollectionDto<ScPlaylistDto>> = runCatching {
        executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/search/playlists_without_albums") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("linked_partitioning", 1)
            }.body()
        }
    }

    suspend fun searchUsers(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<ScSearchCollectionDto<ScUserDto>> = runCatching {
        executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/search/users") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("linked_partitioning", 1)
            }.body()
        }
    }

    suspend fun getTrack(trackId: Long): Result<ScTrackDto> = runCatching {
        executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/tracks/$trackId") {
                parameter("client_id", clientId)
            }.body()
        }
    }

    suspend fun resolveStream(trackId: Long): Result<ScResolvedStream> = runCatching {
        val track = getTrack(trackId).getOrThrow()
        val transcodings = track.media?.transcodings.orEmpty()
        if (transcodings.isEmpty()) {
            error("У трека SoundCloud ($trackId) отсутствуют доступные потоки (transcodings)")
        }

        // Priority 1: Progressive MP3 (direct HTTP stream)
        // Priority 2: HLS MP3 / Opus (.m3u8)
        val sortedTranscodings = transcodings
            .filter { !it.snipped && it.url.isNotBlank() }
            .ifEmpty { transcodings.filter { it.url.isNotBlank() } }
            .sortedWith(
                compareBy<ScTranscodingDto> {
                    val proto = it.format?.protocol?.lowercase().orEmpty()
                    val mime = it.format?.mimeType?.lowercase().orEmpty()
                    when {
                        proto == "progressive" && mime.contains("mpeg") -> 0
                        proto == "progressive" -> 1
                        proto == "hls" && mime.contains("mpeg") -> 2
                        proto == "hls" -> 3
                        else -> 4
                    }
                }.thenByDescending {
                    it.quality == "hq"
                }
            )

        var lastError: Throwable? = null
        for (transcoding in sortedTranscodings) {
            try {
                val streamInfo = executeWithClientIdRetry { clientId ->
                    // Resolve the transcoding endpoint through the CONTENT proxy client
                    contentHttpClient.get(transcoding.url) {
                        parameter("client_id", clientId)
                    }.body<ScStreamUrlResponseDto>()
                }

                if (streamInfo.url.isNotBlank()) {
                    val protocol = transcoding.format?.protocol?.lowercase().orEmpty()
                    val rawMime = transcoding.format?.mimeType?.lowercase().orEmpty()
                    val isHls = protocol == "hls" || streamInfo.url.contains(".m3u8")
                    val mimeType = when {
                        isHls -> "application/x-mpegURL"
                        rawMime.contains("ogg") || rawMime.contains("opus") -> "audio/ogg"
                        else -> "audio/mpeg"
                    }
                    val bitrate = if (transcoding.quality == "hq") 256_000 else 128_000
                    return@runCatching ScResolvedStream(
                        streamUrl = streamInfo.url,
                        protocol = protocol,
                        mimeType = mimeType,
                        isHls = isHls,
                        durationMs = track.durationMs.takeIf { it > 0 } ?: (transcoding.duration ?: 0L),
                        bitrate = bitrate,
                        track = track
                    )
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw (lastError ?: IllegalStateException("Не удалось получить ссылку на аудиопоток SoundCloud для $trackId"))
    }

    private suspend fun <T> executeWithClientIdRetry(block: suspend (String) -> T): T {
        val firstClientId = getClientId(forceRefresh = false)
        return try {
            block(firstClientId)
        } catch (firstError: Throwable) {
            val refreshedId = getClientId(forceRefresh = true)
            try {
                block(refreshedId)
            } catch (secondError: Throwable) {
                for (fallbackId in FALLBACK_CLIENT_IDS) {
                    if (fallbackId == firstClientId || fallbackId == refreshedId) continue
                    try {
                        val result = block(fallbackId)
                        cachedClientId = fallbackId
                        cachedClientIdTimestampMs = System.currentTimeMillis()
                        return result
                    } catch (_: Throwable) {
                    }
                }
                throw secondError
            }
        }
    }
}
