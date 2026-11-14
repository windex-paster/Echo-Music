package com.music.echo.soundcloud

import com.music.echo.proxy.ProxyChannel
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.innertube.models.Album
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.Thumbnails
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.music.innertube.models.response.PlayerResponse
import com.music.innertube.pages.SearchSummary
import com.music.innertube.pages.SearchSummaryPage
import echo.music.iad1tya.models.ItemsPage
import echo.music.iad1tya.utils.YTPlayerUtils
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

object SoundCloud {
    const val TRACK_ID_PREFIX = "sc_"
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
    private var cachedClientIdTimeMs: Long = 0L

    @Volatile
    var oauthToken: String? = null

    private val trackCache = ConcurrentHashMap<Long, SoundCloudTrack>()

    /**
     * Клиент для входа, поиска, метаданных и получения client_id.
     * Маршрутизируется строго через профиль прокси SoundCloud (канал API).
     */
    private val apiHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SoundCloud.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                header("Origin", WEB_BASE)
                header("Referer", "$WEB_BASE/")
                oauthToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
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
                    ProxyRouter.configureOkHttpBuilder(
                        builder = this,
                        source = ProxyServiceSource.SOUNDCLOUD,
                        channel = ProxyChannel.API
                    )
                }
            }
        }
    }

    /**
     * Клиент для резолва потоковых ссылок (progressive MP3 / HLS .m3u8).
     * Маршрутизируется строго через профиль прокси SoundCloud (канал CONTENT).
     */
    private val contentHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SoundCloud.json)
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
                oauthToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
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
                    ProxyRouter.configureOkHttpBuilder(
                        builder = this,
                        source = ProxyServiceSource.SOUNDCLOUD,
                        channel = ProxyChannel.CONTENT
                    )
                }
            }
        }
    }

    fun isSoundCloudMediaId(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank()) return false
        return mediaId.startsWith(TRACK_ID_PREFIX) || mediaId.startsWith("soundcloud_")
    }

    fun extractTrackId(mediaId: String): Long? {
        val raw = mediaId
            .removePrefix(TRACK_ID_PREFIX)
            .removePrefix("soundcloud_")
            .trim()
        return raw.toLongOrNull()
    }

    suspend fun getClientId(forceRefresh: Boolean = false): String = clientIdMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedClientId != null && (now - cachedClientIdTimeMs) < 3_600_000L) {
            return@withLock cachedClientId!!
        }

        runCatching {
            val html = apiHttpClient.get(WEB_BASE).bodyAsText()
            val scriptUrls = scriptSrcRegex.findAll(html).map { it.groupValues[1] }.toList().takeLast(6).reversed()
            for (scriptUrl in scriptUrls) {
                val scriptContent = runCatching { apiHttpClient.get(scriptUrl).bodyAsText() }.getOrNull() ?: continue
                for (regex in clientIdRegexes) {
                    val match = regex.find(scriptContent)
                    if (match != null) {
                        val candidate = match.groupValues[1]
                        if (candidate.length == 32) {
                            cachedClientId = candidate
                            cachedClientIdTimeMs = now
                            return@withLock candidate
                        }
                    }
                }
            }
        }

        val fallback = FALLBACK_CLIENT_IDS.first()
        cachedClientId = fallback
        cachedClientIdTimeMs = now
        fallback
    }

    suspend fun searchTracks(
        query: String,
        limit: Int = 25,
        offset: Int = 0
    ): Result<SoundCloudSearchCollection<SoundCloudTrack>> = runCatching {
        val response: SoundCloudSearchCollection<SoundCloudTrack> = executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/search/tracks") {
                parameter("q", query)
                parameter("client_id", clientId)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("linked_partitioning", 1)
            }.body()
        }
        response.collection.forEach { track ->
            if (track.id > 0L) {
                trackCache[track.id] = track
            }
        }
        response
    }

    suspend fun searchAlbums(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<SoundCloudSearchCollection<SoundCloudPlaylist>> = runCatching {
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
    ): Result<SoundCloudSearchCollection<SoundCloudPlaylist>> = runCatching {
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

    suspend fun searchArtists(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<SoundCloudSearchCollection<SoundCloudUser>> = runCatching {
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

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> = runCatching {
        coroutineScope {
            val tracksDeferred = async { searchTracks(query, limit = 15, offset = 0).getOrNull() }
            val albumsDeferred = async { searchAlbums(query, limit = 8, offset = 0).getOrNull() }
            val playlistsDeferred = async { searchPlaylists(query, limit = 8, offset = 0).getOrNull() }
            val artistsDeferred = async { searchArtists(query, limit = 8, offset = 0).getOrNull() }

            val summaries = mutableListOf<SearchSummary>()

            val trackItems = tracksDeferred.await()?.collection.orEmpty()
                .filter { it.id > 0L && it.title.isNotBlank() }
                .map { it.toSongItem() }
            if (trackItems.isNotEmpty()) {
                summaries.add(SearchSummary(title = "SoundCloud • Треки", items = trackItems))
            }

            val artistItems = artistsDeferred.await()?.collection.orEmpty()
                .filter { it.id > 0L && it.username.isNotBlank() }
                .map { it.toArtistItem() }
            if (artistItems.isNotEmpty()) {
                summaries.add(SearchSummary(title = "SoundCloud • Артисты", items = artistItems))
            }

            val albumItems = albumsDeferred.await()?.collection.orEmpty()
                .filter { it.id > 0L && it.title.isNotBlank() }
                .map { it.toAlbumItem() }
            if (albumItems.isNotEmpty()) {
                summaries.add(SearchSummary(title = "SoundCloud • Альбомы", items = albumItems))
            }

            val playlistItems = playlistsDeferred.await()?.collection.orEmpty()
                .filter { it.id > 0L && it.title.isNotBlank() }
                .map { it.toPlaylistItem() }
            if (playlistItems.isNotEmpty()) {
                summaries.add(SearchSummary(title = "SoundCloud • Плейлисты", items = playlistItems))
            }

            SearchSummaryPage(summaries = summaries)
        }
    }

    suspend fun searchFiltered(
        query: String,
        filterValue: String,
        offset: Int = 0
    ): Result<ItemsPage> = runCatching {
        val limit = 25
        when (filterValue) {
            com.music.innertube.YouTube.SearchFilter.FILTER_ARTIST.value -> {
                val res = searchArtists(query, limit = limit, offset = offset).getOrThrow()
                val items: List<YTItem> = res.collection.map { it.toArtistItem() }
                val next = if (res.nextHref != null && items.isNotEmpty()) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            com.music.innertube.YouTube.SearchFilter.FILTER_ALBUM.value -> {
                val res = searchAlbums(query, limit = limit, offset = offset).getOrThrow()
                val items: List<YTItem> = res.collection.map { it.toAlbumItem() }
                val next = if (res.nextHref != null && items.isNotEmpty()) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            com.music.innertube.YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST.value,
            com.music.innertube.YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST.value -> {
                val res = searchPlaylists(query, limit = limit, offset = offset).getOrThrow()
                val items: List<YTItem> = res.collection.map { it.toPlaylistItem() }
                val next = if (res.nextHref != null && items.isNotEmpty()) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            else -> {
                val res = searchTracks(query, limit = limit, offset = offset).getOrThrow()
                val items: List<YTItem> = res.collection.map { it.toSongItem() }
                val next = if (res.nextHref != null && items.isNotEmpty()) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
        }
    }

    suspend fun getTrendingFeed(limit: Int = 16): Result<List<SongItem>> = runCatching {
        val collection = searchTracks(query = "top hits electronic hip hop", limit = limit, offset = 0).getOrThrow()
        collection.collection
            .filter { it.id > 0L && it.title.isNotBlank() }
            .map { it.toSongItem() }
    }

    suspend fun getTrack(trackId: Long): Result<SoundCloudTrack> = runCatching {
        trackCache[trackId]?.takeIf { !it.media?.transcodings.isNullOrEmpty() }?.let {
            return@runCatching it
        }
        val fetched: SoundCloudTrack = executeWithClientIdRetry { clientId ->
            apiHttpClient.get("$API_V2_BASE/tracks/$trackId") {
                parameter("client_id", clientId)
            }.body()
        }
        if (fetched.id > 0L) {
            trackCache[fetched.id] = fetched
        }
        fetched
    }

    /**
     * Получает прямую ссылку на поток трека SoundCloud (приоритет: progressive MP3, затем HLS .m3u8)
     * через CONTENT-прокси SoundCloud.
     */
    suspend fun resolveStream(trackId: Long): Result<SoundCloudResolvedStream> = runCatching {
        val track = getTrack(trackId).getOrThrow()
        val transcodings = track.media?.transcodings.orEmpty()
        if (transcodings.isEmpty()) {
            error("SoundCloud трек $trackId не содержит доступных потоков (transcodings)")
        }

        val ordered = transcodings
            .filter { !it.snipped && it.url.isNotBlank() }
            .ifEmpty { transcodings.filter { it.url.isNotBlank() } }
            .sortedWith(
                compareBy<SoundCloudTranscoding> {
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
        for (transcoding in ordered) {
            try {
                val streamResp: SoundCloudStreamResponse = executeWithClientIdRetry { clientId ->
                    contentHttpClient.get(transcoding.url) {
                        parameter("client_id", clientId)
                    }.body()
                }
                if (streamResp.url.isNotBlank()) {
                    val protocol = transcoding.format?.protocol?.lowercase().orEmpty()
                    val rawMime = transcoding.format?.mimeType?.lowercase().orEmpty()
                    val isHls = protocol == "hls" || streamResp.url.contains(".m3u8")
                    val mimeType = when {
                        isHls -> "application/x-mpegURL"
                        rawMime.contains("ogg") || rawMime.contains("opus") -> "audio/ogg"
                        else -> "audio/mpeg"
                    }
                    val bitrate = if (transcoding.quality == "hq") 256_000 else 128_000
                    val durationMs = track.durationMs.takeIf { it > 0L } ?: (transcoding.duration ?: 0L)
                    return@runCatching SoundCloudResolvedStream(
                        trackId = track.id,
                        streamUrl = streamResp.url,
                        protocol = protocol,
                        mimeType = mimeType,
                        isHls = isHls,
                        bitrate = bitrate,
                        durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1),
                        title = track.title,
                        artist = track.artistName(),
                        artworkUrl = track.highResArtwork()
                    )
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw (lastError ?: IllegalStateException("Не удалось получить поток для SoundCloud трека $trackId"))
    }

    /**
     * Формирует `YTPlayerUtils.PlaybackData` для `MusicService` и `DownloadUtil`.
     */
    suspend fun resolvePlaybackData(mediaId: String): Result<YTPlayerUtils.PlaybackData> = runCatching {
        val trackId = extractTrackId(mediaId)
            ?: error("Некорректный идентификатор трека SoundCloud: $mediaId")
        val resolved = resolveStream(trackId).getOrThrow()
        val artwork = resolved.artworkUrl ?: ""

        YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails = PlayerResponse.VideoDetails(
                videoId = mediaId,
                title = resolved.title,
                author = resolved.artist,
                channelId = "",
                lengthSeconds = resolved.durationSeconds.toString(),
                musicVideoType = null,
                viewCount = null,
                thumbnail = Thumbnails(
                    thumbnails = if (artwork.isNotEmpty()) {
                        listOf(
                            com.music.innertube.models.Thumbnail(
                                url = artwork,
                                width = 500,
                                height = 500
                            )
                        )
                    } else {
                        emptyList()
                    }
                )
            ),
            playbackTracking = null,
            format = PlayerResponse.StreamingData.Format(
                itag = if (resolved.isHls) 9201 else 9200,
                url = resolved.streamUrl,
                mimeType = resolved.mimeType,
                bitrate = resolved.bitrate,
                width = null,
                height = null,
                contentLength = null,
                quality = if (resolved.isHls) "SoundCloud HLS" else "SoundCloud Progressive MP3",
                fps = null,
                qualityLabel = null,
                averageBitrate = resolved.bitrate,
                audioQuality = "AUDIO_QUALITY_MEDIUM",
                approxDurationMs = (resolved.durationSeconds * 1000L).toString(),
                audioSampleRate = 44100,
                audioChannels = 2,
                loudnessDb = null,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
                audioTrack = null
            ),
            streamUrl = resolved.streamUrl,
            streamExpiresInSeconds = 1800,
            headers = mapOf(
                HttpHeaders.UserAgent to USER_AGENT,
                "Origin" to WEB_BASE,
                "Referer" to "$WEB_BASE/"
            )
        )
    }

    fun SoundCloudTrack.toSongItem(): SongItem {
        val mediaId = "$TRACK_ID_PREFIX$id"
        val durationSec = ((durationMs.takeIf { it > 0 } ?: (fullDurationMs ?: 0L)) / 1000L).toInt().takeIf { it > 0 }
        val coverUrl = highResArtwork() ?: "https://a-v2.sndcdn.com/assets/images/sc-icons/ios-a62dfc8fe7.png"
        val artistNameStr = artistName()
        return SongItem(
            id = mediaId,
            title = title,
            artists = listOf(
                Artist(
                    name = artistNameStr,
                    id = user?.id?.takeIf { it > 0L }?.let { "sc_artist_$it" }
                )
            ),
            album = publisherMetadata?.albumTitle?.takeIf { it.isNotBlank() }?.let {
                Album(name = it, id = "sc_album_$id")
            } ?: genre?.takeIf { it.isNotBlank() }?.let {
                Album(name = "SoundCloud • $it", id = "sc_genre_$id")
            },
            duration = durationSec,
            thumbnail = coverUrl,
            explicit = publisherMetadata?.explicit == true,
            endpoint = WatchEndpoint(videoId = mediaId)
        )
    }

    fun SoundCloudPlaylist.toAlbumItem(): AlbumItem {
        val coverUrl = highResArtwork() ?: "https://a-v2.sndcdn.com/assets/images/sc-icons/ios-a62dfc8fe7.png"
        val yearVal = releaseDate?.take(4)?.toIntOrNull() ?: displayDate?.take(4)?.toIntOrNull()
        val idStr = "sc_album_$id"
        return AlbumItem(
            browseId = idStr,
            playlistId = idStr,
            title = title,
            artists = user?.username?.takeIf { it.isNotBlank() }?.let {
                listOf(Artist(name = it, id = "sc_artist_${user.id}"))
            },
            year = yearVal,
            thumbnail = coverUrl,
            explicit = false
        )
    }

    fun SoundCloudPlaylist.toPlaylistItem(): PlaylistItem {
        val coverUrl = highResArtwork() ?: "https://a-v2.sndcdn.com/assets/images/sc-icons/ios-a62dfc8fe7.png"
        val firstTrackId = tracks.firstOrNull { it.id > 0L }?.let { "$TRACK_ID_PREFIX${it.id}" }
        return PlaylistItem(
            id = "sc_playlist_$id",
            title = title,
            author = user?.username?.takeIf { it.isNotBlank() }?.let {
                Artist(name = it, id = "sc_artist_${user.id}")
            },
            songCountText = if (trackCount > 0) "$trackCount треков" else null,
            thumbnail = coverUrl,
            playEndpoint = firstTrackId?.let { WatchEndpoint(videoId = it) },
            shuffleEndpoint = firstTrackId?.let { WatchEndpoint(videoId = it) },
            radioEndpoint = firstTrackId?.let { WatchEndpoint(videoId = it) }
        )
    }

    fun SoundCloudUser.toArtistItem(): ArtistItem {
        val coverUrl = highResAvatar() ?: "https://a-v2.sndcdn.com/assets/images/sc-icons/ios-a62dfc8fe7.png"
        return ArtistItem(
            id = "sc_artist_$id",
            title = fullName?.takeIf { it.isNotBlank() }?.let { "$username ($it)" } ?: username,
            thumbnail = coverUrl,
            shuffleEndpoint = null,
            radioEndpoint = null
        )
    }

    private suspend fun <T> executeWithClientIdRetry(block: suspend (String) -> T): T {
        val currentId = getClientId(forceRefresh = false)
        return try {
            block(currentId)
        } catch (firstErr: Throwable) {
            val refreshedId = getClientId(forceRefresh = true)
            try {
                block(refreshedId)
            } catch (secondErr: Throwable) {
                for (fallbackId in FALLBACK_CLIENT_IDS) {
                    if (fallbackId == currentId || fallbackId == refreshedId) continue
                    try {
                        val res = block(fallbackId)
                        cachedClientId = fallbackId
                        cachedClientIdTimeMs = System.currentTimeMillis()
                        return res
                    } catch (_: Throwable) {
                    }
                }
                throw secondErr
            }
        }
    }
}
