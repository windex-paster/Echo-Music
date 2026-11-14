package com.music.echo.proxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import kotlinx.serialization.Serializable
import okhttp3.Credentials

@Serializable
enum class ProxyProtocol(val displayName: String) {
  SOCKS5("SOCKS5"),
  HTTP("HTTP / HTTPS");

  fun toJavaProxyType(): Proxy.Type =
    when (this) {
      SOCKS5 -> Proxy.Type.SOCKS
      HTTP -> Proxy.Type.HTTP
    }
}

@Serializable
enum class ProxyServiceSource(val id: String, val displayName: String) {
  YOUTUBE_MUSIC("youtube_music", "YouTube Music"),
  SOUNDCLOUD("soundcloud", "SoundCloud"),
  SPOTIFY("spotify", "Spotify");

  companion object {
    fun fromId(id: String?): ProxyServiceSource =
      entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
        ?: YOUTUBE_MUSIC
  }
}

@Serializable
enum class ProxyChannel(val id: String, val displayName: String) {
  API("api", "Вход и API сервиса"),
  CONTENT("content", "Получение контента (аудиопоток)")
}

@Serializable
data class ProxyProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
  val host: String,
  val port: Int,
  val username: String = "",
  val password: String = ""
) {
  val hasAuthentication: Boolean
    get() = username.isNotBlank() || password.isNotBlank()

  val endpointLabel: String
    get() = "${protocol.displayName} • $host:$port"

  fun toJavaProxy(): Proxy {
    val cleanHost = host.trim()
    val safePort = port.coerceIn(1, 65535)
    return Proxy(protocol.toJavaProxyType(), InetSocketAddress.createUnresolved(cleanHost, safePort))
  }

  fun toResolvedJavaProxy(): Proxy {
    val cleanHost = host.trim()
    val safePort = port.coerceIn(1, 65535)
    return Proxy(protocol.toJavaProxyType(), InetSocketAddress(cleanHost, safePort))
  }

  fun basicAuthHeader(): String? =
    if (hasAuthentication) Credentials.basic(username, password) else null
}

@Serializable
data class ServiceProxyRouting(
  val apiProfileId: String? = null,
  val contentProfileId: String? = null
) {
  fun profileIdFor(channel: ProxyChannel): String? =
    when (channel) {
      ProxyChannel.API -> apiProfileId?.takeIf { it.isNotBlank() }
      ProxyChannel.CONTENT -> contentProfileId?.takeIf { it.isNotBlank() }
    }

  fun withChannel(channel: ProxyChannel, profileId: String?): ServiceProxyRouting {
    val normalized = profileId?.takeIf { it.isNotBlank() }
    return when (channel) {
      ProxyChannel.API -> copy(apiProfileId = normalized)
      ProxyChannel.CONTENT -> copy(contentProfileId = normalized)
    }
  }
}

@Serializable
data class ProxyRoutingConfig(
  val youtubeMusic: ServiceProxyRouting = ServiceProxyRouting(),
  val soundCloud: ServiceProxyRouting = ServiceProxyRouting(),
  val spotify: ServiceProxyRouting = ServiceProxyRouting()
) {
  fun routingFor(source: ProxyServiceSource): ServiceProxyRouting =
    when (source) {
      ProxyServiceSource.YOUTUBE_MUSIC -> youtubeMusic
      ProxyServiceSource.SOUNDCLOUD -> soundCloud
      ProxyServiceSource.SPOTIFY -> spotify
    }

  fun profileIdFor(source: ProxyServiceSource, channel: ProxyChannel): String? =
    routingFor(source).profileIdFor(channel)

  fun withRouting(
    source: ProxyServiceSource,
    channel: ProxyChannel,
    profileId: String?
  ): ProxyRoutingConfig =
    when (source) {
      ProxyServiceSource.YOUTUBE_MUSIC ->
        copy(youtubeMusic = youtubeMusic.withChannel(channel, profileId))
      ProxyServiceSource.SOUNDCLOUD ->
        copy(soundCloud = soundCloud.withChannel(channel, profileId))
      ProxyServiceSource.SPOTIFY ->
        copy(spotify = spotify.withChannel(channel, profileId))
    }

  fun removeProfileReferences(deletedProfileId: String): ProxyRoutingConfig =
    copy(
      youtubeMusic =
        ServiceProxyRouting(
          apiProfileId = youtubeMusic.apiProfileId?.takeUnless { it == deletedProfileId },
          contentProfileId = youtubeMusic.contentProfileId?.takeUnless { it == deletedProfileId }
        ),
      soundCloud =
        ServiceProxyRouting(
          apiProfileId = soundCloud.apiProfileId?.takeUnless { it == deletedProfileId },
          contentProfileId = soundCloud.contentProfileId?.takeUnless { it == deletedProfileId }
        ),
      spotify =
        ServiceProxyRouting(
          apiProfileId = spotify.apiProfileId?.takeUnless { it == deletedProfileId },
          contentProfileId = spotify.contentProfileId?.takeUnless { it == deletedProfileId }
        )
    )
}

sealed interface ProxyTestStatus {
  data object Idle : ProxyTestStatus
  data object Testing : ProxyTestStatus
  data class Success(
    val pingMs: Long,
    val statusCode: Int,
    val statusMessage: String
  ) : ProxyTestStatus
  data class Failure(
    val errorMessage: String,
    val pingMs: Long? = null
  ) : ProxyTestStatus
}
