package com.music.echo.spotify

import android.net.ConnectivityManager
import com.music.echo.proxy.ProxyChannel
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.echo.soundcloud.SoundCloud
import com.music.innertube.YouTube
import com.music.innertube.models.Album
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.Thumbnail
import com.music.innertube.models.Thumbnails
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.music.innertube.models.response.PlayerResponse
import com.music.innertube.pages.SearchSummary
import com.music.innertube.pages.SearchSummaryPage
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.models.ItemsPage
import echo.music.iad1tya.spotify.Spotify
import echo.music.iad1tya.spotify.SpotifyAuth
import echo.music.iad1tya.spotify.SpotifyMapper
import echo.music.iad1tya.spotify.models.SpotifyAlbum
import echo.music.iad1tya.spotify.models.SpotifyArtist
import echo.music.iad1tya.spotify.models.SpotifyHomeFeedItem
import echo.music.iad1tya.spotify.models.SpotifyPlaylist
import echo.music.iad1tya.spotify.models.SpotifySimpleAlbum
import echo.music.iad1tya.spotify.models.SpotifySimpleArtist
import echo.music.iad1tya.spotify.models.SpotifyTrack
import echo.music.iad1tya.utils.YTPlayerUtils
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Мост интеграции Spotify:
 * 1. Выполняет авторизацию, поиск и получение каталога через `ProxyServiceSource.SPOTIFY` + `ProxyChannel.API`.
 * 2. Выполняет разрешение и воспроизведение треков (`sp_<id>`):
 *    - для треков с Widevine DRM автоматически подбирает полноразмерный поток без DRM через YouTube Music
 *      или SoundCloud (с оценкой совпадения через `SpotifyMapper.matchScorePrecomputed`),
 *    - при недоступности внешних источников использует прямой аудиопоток Spotify CDN (`p.scdn.co`),
 *    - весь аудиопоток маршрутизируется через `ProxyServiceSource.SPOTIFY` + `ProxyChannel.CONTENT`.
 */
object SpotifyCatalogBridge {
    const val TRACK_ID_PREFIX = "sp_"
    private const val EMBED_BASE = "https://open.spotify.com/embed/track"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val nextDataRegex =
        Regex("""<script id="__NEXT_DATA__" type="application/json">(.+?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val audioPreviewRegex =
        Regex(""""audioPreview"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""")

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val tokenMutex = Mutex()
    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    private val trackCache = ConcurrentHashMap<String, SpotifyTrack>()

    /**
     * Клиент API-канала Spotify (для Embed и вспомогательных запросов).
     */
    private val apiHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SpotifyCatalogBridge.json)
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
            }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                    ProxyRouter.configureOkHttpBuilder(
                        builder = this,
                        source = ProxyServiceSource.SPOTIFY,
                        channel = ProxyChannel.API
                    )
                }
            }
        }
    }

    /**
     * Клиент CONTENT-канала Spotify (для обращения к аудио-CDN Spotify).
     */
    val contentHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(this@SpotifyCatalogBridge.json)
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
                    ProxyRouter.configureOkHttpBuilder(
                        builder = this,
                        source = ProxyServiceSource.SPOTIFY,
                        channel = ProxyChannel.CONTENT
                    )
                }
            }
        }
    }

    fun isSpotifyMediaId(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank()) return false
        return mediaId.startsWith(TRACK_ID_PREFIX) ||
            mediaId.startsWith("spotify_") ||
            mediaId.startsWith("spotify:track:")
    }

    fun extractSpotifyTrackId(mediaId: String): String {
        return mediaId
            .removePrefix(TRACK_ID_PREFIX)
            .removePrefix("spotify_track_")
            .removePrefix("spotify_")
            .removePrefix("spotify:track:")
            .trim()
    }

    fun cacheTrack(track: SpotifyTrack) {
        if (track.id.isNotBlank()) {
            trackCache[track.id] = track
        }
    }

    /**
     * Гарантирует наличие актуального `Spotify.accessToken` через API-прокси Spotify.
     */
    suspend fun ensureAccessToken(
        spDc: String = "",
        spKey: String = "",
        forceRefresh: Boolean = false
    ): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = Spotify.accessToken
        if (!forceRefresh && !existing.isNullOrBlank() && (tokenExpiresAtMs == 0L || now < tokenExpiresAtMs)) {
            return@withLock existing
        }

        if (spDc.isNotBlank()) {
            val authRes = SpotifyAuth.fetchAccessToken(spDc = spDc, spKey = spKey).getOrNull()
            if (authRes != null && authRes.accessToken.isNotBlank()) {
                Spotify.accessToken = authRes.accessToken
                tokenExpiresAtMs = authRes.accessTokenExpirationTimestampMs.takeIf { it > now }
                    ?: (now + 3_000_000L)
                return@withLock authRes.accessToken
            }
        }

        val anonRes = SpotifyAuth.fetchAnonymousAccessToken().getOrNull()
        if (anonRes != null && anonRes.accessToken.isNotBlank()) {
            Spotify.accessToken = anonRes.accessToken
            tokenExpiresAtMs = anonRes.accessTokenExpirationTimestampMs.takeIf { it > now }
                ?: (now + 1_800_000L)
            return@withLock anonRes.accessToken
        }

        Spotify.accessToken
    }

    /**
     * Сводный поиск по Spotify (Треки, Артисты, Альбомы, Плейлисты) через API-прокси.
     */
    suspend fun searchSummary(
        query: String,
        spDc: String = "",
        spKey: String = ""
    ): Result<SearchSummaryPage> = runCatching {
        ensureAccessToken(spDc = spDc, spKey = spKey)
        val result = try {
            Spotify.search(
                query = query,
                types = listOf("track", "album", "artist", "playlist"),
                limit = 20,
                offset = 0
            ).getOrThrow()
        } catch (e: Spotify.SpotifyException) {
            if (e.statusCode == 401) {
                ensureAccessToken(spDc = spDc, spKey = spKey, forceRefresh = true)
                Spotify.search(
                    query = query,
                    types = listOf("track", "album", "artist", "playlist"),
                    limit = 20,
                    offset = 0
                ).getOrThrow()
            } else {
                throw e
            }
        }

        val summaries = mutableListOf<SearchSummary>()

        val tracks = result.tracks?.items.orEmpty()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .onEach { cacheTrack(it) }
            .map { it.toSongItem() }
        if (tracks.isNotEmpty()) {
            summaries.add(SearchSummary(title = "Spotify • Треки", items = tracks))
        }

        val artists = result.artists?.items.orEmpty()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { it.toArtistItem() }
        if (artists.isNotEmpty()) {
            summaries.add(SearchSummary(title = "Spotify • Артисты", items = artists))
        }

        val albums = result.albums?.items.orEmpty()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { it.toAlbumItem() }
        if (albums.isNotEmpty()) {
            summaries.add(SearchSummary(title = "Spotify • Альбомы", items = albums))
        }

        val playlists = result.playlists?.items.orEmpty()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { it.toPlaylistItem() }
        if (playlists.isNotEmpty()) {
            summaries.add(SearchSummary(title = "Spotify • Плейлисты", items = playlists))
        }

        SearchSummaryPage(summaries = summaries)
    }

    /**
     * Фильтрованный поиск по Spotify с пагинацией.
     */
    suspend fun searchFiltered(
        query: String,
        filterValue: String,
        offset: Int = 0,
        spDc: String = "",
        spKey: String = ""
    ): Result<ItemsPage> = runCatching {
        ensureAccessToken(spDc = spDc, spKey = spKey)
        val limit = 25
        val searchResult = try {
            Spotify.search(
                query = query,
                types = listOf("track", "album", "artist", "playlist"),
                limit = limit,
                offset = offset
            ).getOrThrow()
        } catch (e: Spotify.SpotifyException) {
            if (e.statusCode == 401) {
                ensureAccessToken(spDc = spDc, spKey = spKey, forceRefresh = true)
                Spotify.search(
                    query = query,
                    types = listOf("track", "album", "artist", "playlist"),
                    limit = limit,
                    offset = offset
                ).getOrThrow()
            } else {
                throw e
            }
        }

        when (filterValue) {
            YouTube.SearchFilter.FILTER_ARTIST.value -> {
                val paging = searchResult.artists
                val items: List<YTItem> = paging?.items.orEmpty().map { it.toArtistItem() }
                val next = if (items.size >= limit) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            YouTube.SearchFilter.FILTER_ALBUM.value -> {
                val paging = searchResult.albums
                val items: List<YTItem> = paging?.items.orEmpty().map { it.toAlbumItem() }
                val next = if (items.size >= limit) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST.value,
            YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST.value -> {
                val paging = searchResult.playlists
                val items: List<YTItem> = paging?.items.orEmpty().map { it.toPlaylistItem() }
                val next = if (items.size >= limit) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
            else -> {
                val paging = searchResult.tracks
                val items: List<YTItem> = paging?.items.orEmpty()
                    .onEach { cacheTrack(it) }
                    .map { it.toSongItem() }
                val next = if (items.size >= limit) (offset + items.size).toString() else null
                ItemsPage(items = items, continuation = next)
            }
        }
    }

    /**
     * Загружает подборку треков Spotify для главного экрана (`HomeScreen`) через API-прокси.
     */
    suspend fun getDiscoveryFeed(
        spDc: String = "",
        spKey: String = "",
        limit: Int = 16
    ): Result<List<SongItem>> = runCatching {
        ensureAccessToken(spDc = spDc, spKey = spKey)
        if (spDc.isNotBlank()) {
            val topTracks = Spotify.topTracks(limit = limit).getOrNull()?.items.orEmpty()
            if (topTracks.isNotEmpty()) {
                topTracks.forEach { cacheTrack(it) }
                return@runCatching topTracks.map { it.toSongItem() }
            }
            val liked = Spotify.likedSongs(limit = limit).getOrNull()?.items?.map { it.track }.orEmpty()
            if (liked.isNotEmpty()) {
                liked.forEach { cacheTrack(it) }
                return@runCatching liked.map { it.toSongItem() }
            }
        }

        val searchTracks = Spotify.search(
            query = "Global Top 50 hits",
            types = listOf("track"),
            limit = limit,
            offset = 0
        ).getOrThrow().tracks?.items.orEmpty()

        searchTracks.forEach { cacheTrack(it) }
        searchTracks.map { it.toSongItem() }
    }

    /**
     * Получает метаданные трека Spotify (из кэша или через Spotify Embed по API-прокси).
     */
    suspend fun getOrFetchTrackMetadata(
        spotifyId: String,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        fallbackDurationMs: Int? = null
    ): SpotifyTrack {
        trackCache[spotifyId]?.let { return it }

        val embedTrack = fetchTrackFromEmbed(spotifyId).getOrNull()
        if (embedTrack != null && embedTrack.name.isNotBlank()) {
            cacheTrack(embedTrack)
            return embedTrack
        }

        return SpotifyTrack(
            id = spotifyId,
            name = fallbackTitle?.takeIf { it.isNotBlank() } ?: "Spotify Track",
            artists = listOf(
                SpotifySimpleArtist(
                    id = null,
                    name = fallbackArtist?.takeIf { it.isNotBlank() } ?: ""
                )
            ),
            durationMs = fallbackDurationMs ?: 0
        )
    }

    private suspend fun fetchTrackFromEmbed(spotifyId: String): Result<SpotifyTrack> = runCatching {
        val cleanId = extractSpotifyTrackId(spotifyId)
        val html = apiHttpClient.get("$EMBED_BASE/$cleanId") {
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
                val artistsList = entity.arr("artists")?.mapNotNull { artistElem ->
                    val obj = artistElem.jsonObject
                    val name = obj.str("name") ?: return@mapNotNull null
                    val uri = obj.str("uri")
                    SpotifySimpleArtist(
                        id = uri?.substringAfterLast(":"),
                        name = name,
                        uri = uri
                    )
                }.orEmpty()
                val durationMs = entity.int("duration") ?: entity.int("duration_ms") ?: 0
                val previewUrl = entity.obj("audioPreview")?.str("url")
                val coverUrl = entity.obj("visualIdentity")?.obj("image")
                    ?.arr("0")?.firstOrNull()?.jsonObject?.str("url")
                    ?: entity.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")

                val track = SpotifyTrack(
                    id = cleanId,
                    name = title,
                    artists = artistsList,
                    album = coverUrl?.let {
                        SpotifySimpleAlbum(
                            id = cleanId,
                            name = title,
                            images = listOf(echo.music.iad1tya.spotify.models.SpotifyImage(url = it, width = 640, height = 640))
                        )
                    },
                    durationMs = durationMs,
                    previewUrl = previewUrl,
                    uri = "spotify:track:$cleanId"
                )
                return@runCatching track
            }
        }

        val fallbackPreview = audioPreviewRegex.find(html)?.groupValues?.getOrNull(1)
        SpotifyTrack(
            id = cleanId,
            name = "",
            previewUrl = fallbackPreview,
            uri = "spotify:track:$cleanId"
        )
    }

    /**
     * Разрешает аудиопоток для трека Spotify (`sp_<id>`):
     * 1. Поскольку оригинальные полноразмерные потоки Spotify защищены Widevine DRM,
     *    выполняется интеллектуальный автоподбор полного потока без DRM через YouTube Music
     *    или SoundCloud с оценкой точности совпадения (`SpotifyMapper.matchScorePrecomputed`).
     * 2. Если внешние каталоги недоступны, используется прямой CDN-поток Spotify (`previewUrl` на `p.scdn.co`).
     * 3. Полученный поток воспроизводится через `ProxyRoutingDataSourceFactory` с привязкой к
     *    контент-прокси Spotify (`ProxyServiceSource.SPOTIFY`, `ProxyChannel.CONTENT`).
     */
    suspend fun resolvePlaybackData(
        mediaId: String,
        knownTitle: String? = null,
        knownArtist: String? = null,
        knownDurationMs: Long? = null,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        connectivityManager: ConnectivityManager? = null
    ): Result<YTPlayerUtils.PlaybackData> = runCatching {
        val spotifyId = extractSpotifyTrackId(mediaId)
        val track = getOrFetchTrackMetadata(
            spotifyId = spotifyId,
            fallbackTitle = knownTitle,
            fallbackArtist = knownArtist,
            fallbackDurationMs = knownDurationMs?.toInt()
        )

        val effectiveTitle = track.name.takeIf { it.isNotBlank() && it != "Spotify Track" }
            ?: knownTitle?.takeIf { it.isNotBlank() }
            ?: error("Не удалось определить название трека Spotify ($spotifyId)")
        val effectiveArtist = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: knownArtist?.takeIf { it.isNotBlank() }
            ?: ""
        val effectiveDurationMs = track.durationMs.takeIf { it > 0 }
            ?: knownDurationMs?.toInt()
            ?: 0

        val searchQuery = if (effectiveArtist.isNotBlank()) {
            "$effectiveArtist $effectiveTitle"
        } else {
            effectiveTitle
        }
        val precomputed = SpotifyMapper.precompute(
            title = effectiveTitle,
            artist = effectiveArtist,
            durationMs = effectiveDurationMs
        )

        // Шаг 1: Автоподбор полноразмерного потока без DRM через YouTube Music
        val ytCandidateResult = runCatching {
            val ytSearch = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val bestYtSong = ytSearch?.items
                ?.filterIsInstance<SongItem>()
                ?.maxByOrNull { song ->
                    SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = song.title,
                        candidateArtist = song.artists.firstOrNull()?.name.orEmpty(),
                        candidateDurationSec = song.duration
                    )
                }

            if (bestYtSong != null && connectivityManager != null) {
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = bestYtSong.id,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager
                ).getOrNull()
            } else {
                null
            }
        }.getOrNull()

        if (ytCandidateResult != null) {
            return@runCatching ytCandidateResult
        }

        // Шаг 2: Автоподбор полноразмерного потока без DRM через SoundCloud (progressive MP3 / HLS)
        val scCandidateResult = runCatching {
            val scTracks = SoundCloud.searchTracks(searchQuery, limit = 10, offset = 0).getOrNull()?.collection.orEmpty()
            val bestScTrack = scTracks
                .filter { it.id > 0L && it.title.isNotBlank() }
                .maxByOrNull { scTrack ->
                    SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = scTrack.title,
                        candidateArtist = scTrack.artistName(),
                        candidateDurationSec = (scTrack.durationMs / 1000L).toInt().takeIf { it > 0 }
                    )
                }

            if (bestScTrack != null) {
                SoundCloud.resolvePlaybackData("${SoundCloud.TRACK_ID_PREFIX}${bestScTrack.id}").getOrNull()
            } else {
                null
            }
        }.getOrNull()

        if (scCandidateResult != null) {
            return@runCatching scCandidateResult
        }

        // Шаг 3: Резервный прямой поток Spotify CDN (p.scdn.co/mp3-preview/...) через CONTENT-прокси Spotify
        val directPreviewUrl = track.previewUrl?.takeIf { it.isNotBlank() }
            ?: fetchTrackFromEmbed(spotifyId).getOrNull()?.previewUrl?.takeIf { it.isNotBlank() }
            ?: error("Не удалось подобрать поток для трека Spotify '$searchQuery' ни через YouTube Music / SoundCloud, ни через CDN Spotify")

        val coverUrl = track.album?.images?.firstOrNull()?.url.orEmpty()
        val durationSec = (effectiveDurationMs / 1000).coerceAtLeast(30)

        YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails = PlayerResponse.VideoDetails(
                videoId = mediaId,
                title = effectiveTitle,
                author = effectiveArtist,
                channelId = "",
                lengthSeconds = durationSec.toString(),
                musicVideoType = null,
                viewCount = null,
                thumbnail = Thumbnails(
                    thumbnails = if (coverUrl.isNotEmpty()) {
                        listOf(Thumbnail(url = coverUrl, width = 640, height = 640))
                    } else {
                        emptyList()
                    }
                )
            ),
            playbackTracking = null,
            format = PlayerResponse.StreamingData.Format(
                itag = 9300,
                url = directPreviewUrl,
                mimeType = "audio/mpeg",
                bitrate = 160_000,
                width = null,
                height = null,
                contentLength = null,
                quality = "Spotify CDN Stream",
                fps = null,
                qualityLabel = null,
                averageBitrate = 160_000,
                audioQuality = "AUDIO_QUALITY_MEDIUM",
                approxDurationMs = (durationSec * 1000L).toString(),
                audioSampleRate = 44100,
                audioChannels = 2,
                loudnessDb = null,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
                audioTrack = null
            ),
            streamUrl = directPreviewUrl,
            streamExpiresInSeconds = 3600,
            headers = mapOf(
                HttpHeaders.UserAgent to USER_AGENT,
                "Origin" to "https://open.spotify.com",
                "Referer" to "https://open.spotify.com/"
            )
        )
    }

    fun SpotifyTrack.toSongItem(): SongItem {
        val mediaId = "$TRACK_ID_PREFIX$id"
        val coverUrl = album?.images
            ?.sortedByDescending { it.width ?: 0 }
            ?.firstOrNull()?.url
            ?: "https://open.spotifycdn.com/cdn/images/favicon32.b64ecc03.png"
        val durationSec = (durationMs / 1000).takeIf { it > 0 }
        return SongItem(
            id = mediaId,
            title = name,
            artists = artists.map { artist ->
                Artist(
                    name = artist.name,
                    id = artist.id?.takeIf { it.isNotBlank() }?.let { "sp_artist_$it" }
                )
            }.ifEmpty {
                listOf(Artist(name = "Spotify Artist", id = null))
            },
            album = album?.takeIf { it.name.isNotBlank() }?.let {
                Album(
                    name = it.name,
                    id = "sp_album_${it.id}"
                )
            },
            duration = durationSec,
            thumbnail = coverUrl,
            explicit = explicit,
            endpoint = WatchEndpoint(videoId = mediaId)
        )
    }

    fun SpotifyAlbum.toAlbumItem(): AlbumItem {
        val coverUrl = images.sortedByDescending { it.width ?: 0 }.firstOrNull()?.url
            ?: "https://open.spotifycdn.com/cdn/images/favicon32.b64ecc03.png"
        val idStr = "sp_album_$id"
        return AlbumItem(
            browseId = idStr,
            playlistId = idStr,
            title = name,
            artists = artists.map {
                Artist(name = it.name, id = it.id?.let { aid -> "sp_artist_$aid" })
            },
            year = releaseDate?.take(4)?.toIntOrNull(),
            thumbnail = coverUrl,
            explicit = false
        )
    }

    fun SpotifyPlaylist.toPlaylistItem(): PlaylistItem {
        val coverUrl = images.sortedByDescending { it.width ?: 0 }.firstOrNull()?.url
            ?: "https://open.spotifycdn.com/cdn/images/favicon32.b64ecc03.png"
        val count = tracks?.total ?: 0
        return PlaylistItem(
            id = "sp_playlist_$id",
            title = name,
            author = owner?.displayName?.takeIf { it.isNotBlank() }?.let {
                Artist(name = it, id = owner.id.takeIf { oid -> oid.isNotBlank() }?.let { oid -> "sp_user_$oid" })
            },
            songCountText = if (count > 0) "$count треков" else null,
            thumbnail = coverUrl,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null
        )
    }

    fun SpotifyArtist.toArtistItem(): ArtistItem {
        val coverUrl = images.sortedByDescending { it.width ?: 0 }.firstOrNull()?.url
            ?: "https://open.spotifycdn.com/cdn/images/favicon32.b64ecc03.png"
        return ArtistItem(
            id = "sp_artist_$id",
            title = name,
            thumbnail = coverUrl,
            shuffleEndpoint = null,
            radioEndpoint = null
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
