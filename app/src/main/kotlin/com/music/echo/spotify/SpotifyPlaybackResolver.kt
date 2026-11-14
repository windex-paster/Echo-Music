package com.music.echo.spotify

import com.music.echo.soundcloud.SoundCloud
import com.music.echo.soundcloud.SoundCloud.toSongItem
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echo.music.iad1tya.spotify.SpotifyMapper
import echo.music.iad1tya.spotify.models.SpotifyTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class SpotifyPlaybackResolver @Inject constructor() {
    private val trackCache = object : LinkedHashMap<String, SongItem>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongItem>?): Boolean = size > 1000
    }
    private val resolutionSemaphore = Semaphore(3)

    suspend fun resolveTrack(
        track: SpotifyTrack,
        hideExplicit: Boolean = false
    ): SongItem? = try {
        resolutionSemaphore.withPermit {
            SpotifyCatalogBridge.cacheTrack(track)
            val cachedSong = synchronized(trackCache) { trackCache[track.id] }
            if (cachedSong != null) {
                return@withPermit cachedSong
            }

            val artistName = track.artists.firstOrNull()?.name.orEmpty()
            val query = "${track.name} $artistName".trim()
            val precomputed = SpotifyMapper.precompute(
                title = track.name,
                artist = artistName,
                durationMs = track.durationMs
            )

            // 1. Поиск лучшего совпадения в YouTube Music
            val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val matchedYtSong = searchResult?.items
                ?.filterIsInstance<SongItem>()
                ?.filter { !hideExplicit || !it.explicit }
                ?.maxByOrNull { candidate ->
                    SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artists.firstOrNull()?.name.orEmpty(),
                        candidateDurationSec = candidate.duration
                    )
                }

            if (matchedYtSong != null) {
                synchronized(trackCache) { trackCache[track.id] = matchedYtSong }
                return@withPermit matchedYtSong
            }

            // 2. Резервный автоподбор потока через SoundCloud
            val scTracks = SoundCloud.searchTracks(query, limit = 8, offset = 0).getOrNull()?.collection.orEmpty()
            val matchedScSong = scTracks
                .filter { !hideExplicit || it.publisherMetadata?.explicit != true }
                .maxByOrNull { candidate ->
                    SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artistName(),
                        candidateDurationSec = (candidate.durationMs / 1000L).toInt().takeIf { it > 0 }
                    )
                }?.toSongItem()

            if (matchedScSong != null) {
                synchronized(trackCache) { trackCache[track.id] = matchedScSong }
                return@withPermit matchedScSong
            }

            null
        }
    } catch (e: Exception) {
        null
    }

    fun getCached(trackId: String): SongItem? = synchronized(trackCache) { trackCache[trackId] }

    suspend fun resolveAndFilter(
        tracks: List<SpotifyTrack>,
        hideExplicit: Boolean
    ): List<SongItem> = coroutineScope {
        tracks
            .filter { !hideExplicit || !it.explicit }
            .map { track ->
                async {
                    resolveTrack(track = track, hideExplicit = hideExplicit)
                }
            }.awaitAll().filterNotNull()
    }
}
