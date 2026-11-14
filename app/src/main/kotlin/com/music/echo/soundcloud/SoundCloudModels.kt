package com.music.echo.soundcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SoundCloudUser(
    @SerialName("id") val id: Long = 0L,
    @SerialName("username") val username: String = "",
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("followers_count") val followersCount: Int? = null,
    @SerialName("verified") val verified: Boolean = false
) {
    fun highResAvatar(): String? = SoundCloudArtworkUrl.toHighRes(avatarUrl)
}

@Serializable
data class SoundCloudTranscodingFormat(
    @SerialName("protocol") val protocol: String = "",
    @SerialName("mime_type") val mimeType: String = ""
)

@Serializable
data class SoundCloudTranscoding(
    @SerialName("url") val url: String = "",
    @SerialName("preset") val preset: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("snipped") val snipped: Boolean = false,
    @SerialName("format") val format: SoundCloudTranscodingFormat? = null,
    @SerialName("quality") val quality: String? = null
)

@Serializable
data class SoundCloudMedia(
    @SerialName("transcodings") val transcodings: List<SoundCloudTranscoding> = emptyList()
)

@Serializable
data class SoundCloudPublisherMetadata(
    @SerialName("artist") val artist: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("explicit") val explicit: Boolean? = null
)

@Serializable
data class SoundCloudTrack(
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
    @SerialName("user") val user: SoundCloudUser? = null,
    @SerialName("media") val media: SoundCloudMedia? = null,
    @SerialName("publisher_metadata") val publisherMetadata: SoundCloudPublisherMetadata? = null,
    @SerialName("streamable") val streamable: Boolean = true,
    @SerialName("policy") val policy: String? = null
) {
    fun highResArtwork(): String? =
        SoundCloudArtworkUrl.toHighRes(artworkUrl ?: user?.avatarUrl)

    fun artistName(): String =
        publisherMetadata?.artist?.takeIf { it.isNotBlank() }
            ?: user?.username?.takeIf { it.isNotBlank() }
            ?: "SoundCloud"
}

@Serializable
data class SoundCloudPlaylist(
    @SerialName("id") val id: Long = 0L,
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("is_album") val isAlbum: Boolean = false,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("display_date") val displayDate: String? = null,
    @SerialName("user") val user: SoundCloudUser? = null,
    @SerialName("tracks") val tracks: List<SoundCloudTrack> = emptyList()
) {
    fun highResArtwork(): String? =
        SoundCloudArtworkUrl.toHighRes(
            artworkUrl
                ?: tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
                ?: user?.avatarUrl
        )
}

@Serializable
data class SoundCloudSearchCollection<T>(
    @SerialName("collection") val collection: List<T> = emptyList(),
    @SerialName("next_href") val nextHref: String? = null,
    @SerialName("total_results") val totalResults: Int? = null
)

@Serializable
data class SoundCloudStreamResponse(
    @SerialName("url") val url: String = ""
)

data class SoundCloudResolvedStream(
    val trackId: Long,
    val streamUrl: String,
    val protocol: String,
    val mimeType: String,
    val isHls: Boolean,
    val bitrate: Int,
    val durationSeconds: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

object SoundCloudArtworkUrl {
    private val sizeRegex =
        Regex(
            "-(large|t300x300|crop|t67x67|badge|small|tiny|mini|t50x50|t200x200)\\.(jpg|png|jpeg)",
            RegexOption.IGNORE_CASE
        )

    /**
     * Заменяет стандартные миниатюры SoundCloud (-large.jpg, -t300x300.jpg и др.)
     * на обложки высокого разрешения -t500x500.jpg.
     */
    fun toHighRes(url: String?, targetSize: String = "t500x500"): String? {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (sizeRegex.containsMatchIn(clean)) {
            sizeRegex.replace(clean) { matchResult ->
                val ext = matchResult.groupValues.getOrElse(2) { "jpg" }
                "-$targetSize.$ext"
            }
        } else {
            clean
        }
    }
}
