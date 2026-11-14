package com.music.echo.proxy

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.music.innertube.YouTube
import echo.music.iad1tya.constants.ProxyEnabledKey
import echo.music.iad1tya.constants.ProxyPasswordKey
import echo.music.iad1tya.constants.ProxyTypeKey
import echo.music.iad1tya.constants.ProxyUrlKey
import echo.music.iad1tya.constants.ProxyUsernameKey
import echo.music.iad1tya.extensions.toEnum
import echo.music.iad1tya.utils.dataStore
import java.io.IOException
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

val ProxyProfilesJsonKey = stringPreferencesKey("proxy_profiles_json")
val ProxyRoutingConfigJsonKey = stringPreferencesKey("proxy_routing_config_json")
val ActiveMusicSourceKey = stringPreferencesKey("active_music_aggregator_source")

/**
 * Central dynamic proxy manager and per-service / per-channel router.
 *
 * Supports independent proxy selection for:
 * - Services: YouTube Music, SoundCloud, Spotify
 * - Channels:
 *   1. API (login, search, metadata, playlists, cover art)
 *   2. CONTENT (audio stream resolution, ExoPlayer playback, track downloads)
 */
object ProxyRouter {
  private const val TAG = "ProxyRouter"
  private const val DEFAULT_TEST_URL = "https://www.google.com/generate_204"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
  }

  private var appContext: Context? = null
  private var routerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _profiles = MutableStateFlow<List<ProxyProfile>>(emptyList())
  val profiles: StateFlow<List<ProxyProfile>> = _profiles.asStateFlow()

  private val _routingConfig = MutableStateFlow(ProxyRoutingConfig())
  val routingConfig: StateFlow<ProxyRoutingConfig> = _routingConfig.asStateFlow()

  private val _activeSource = MutableStateFlow(ProxyServiceSource.YOUTUBE_MUSIC)
  val activeSource: StateFlow<ProxyServiceSource> = _activeSource.asStateFlow()

  private val coilOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(25, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .apply { configureAutoSourceOkHttpBuilder(this, ProxyChannel.API) }
      .build()
  }

  fun initialize(context: Context, scope: CoroutineScope? = null) {
    val applicationContext = context.applicationContext
    appContext = applicationContext
    if (scope != null) {
      routerScope = scope
    }

    installSocksAuthenticator()

    routerScope.launch(Dispatchers.IO) {
      val initialPrefs = runCatching { applicationContext.dataStore.data.first() }.getOrNull()
      if (initialPrefs != null) {
        migrateLegacyProxyIfNeeded(applicationContext, initialPrefs)
        applyPreferences(initialPrefs)
      }

      applicationContext.dataStore.data.collectLatest { prefs ->
        applyPreferences(prefs)
      }
    }
  }

  private suspend fun migrateLegacyProxyIfNeeded(context: Context, prefs: Preferences) {
    val existingProfilesRaw = prefs[ProxyProfilesJsonKey]
    if (!existingProfilesRaw.isNullOrBlank()) return

    val legacyEnabled = prefs[ProxyEnabledKey] == true
    val legacyUrl = prefs[ProxyUrlKey].orEmpty().trim()
    if (!legacyEnabled || legacyUrl.isBlank() || legacyUrl == "host:port") return

    val parts = legacyUrl.split(":")
    val host = parts.getOrNull(0)?.trim().orEmpty()
    val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1080
    if (host.isBlank()) return

    val legacyType = prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP)
    val protocol =
      if (legacyType == Proxy.Type.SOCKS) ProxyProtocol.SOCKS5 else ProxyProtocol.HTTP
    val username = prefs[ProxyUsernameKey].orEmpty().takeUnless { it == "username" }.orEmpty()
    val password = prefs[ProxyPasswordKey].orEmpty().takeUnless { it == "password" }.orEmpty()

    val migratedProfile =
      ProxyProfile(
        id = "legacy_migrated_proxy",
        name = "Основной прокси ($host)",
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = password
      )

    val migratedRouting =
      ProxyRoutingConfig(
        youtubeMusic =
          ServiceProxyRouting(
            apiProfileId = migratedProfile.id,
            contentProfileId = migratedProfile.id
          )
      )

    context.dataStore.edit { mutablePrefs ->
      mutablePrefs[ProxyProfilesJsonKey] = json.encodeToString(listOf(migratedProfile))
      mutablePrefs[ProxyRoutingConfigJsonKey] = json.encodeToString(migratedRouting)
    }
  }

  private fun applyPreferences(prefs: Preferences) {
    val parsedProfiles =
      prefs[ProxyProfilesJsonKey]
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
          runCatching { json.decodeFromString<List<ProxyProfile>>(raw) }.getOrNull()
        }
        ?: emptyList()

    val parsedRouting =
      prefs[ProxyRoutingConfigJsonKey]
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
          runCatching { json.decodeFromString<ProxyRoutingConfig>(raw) }.getOrNull()
        }
        ?: ProxyRoutingConfig()

    val parsedActiveSource = ProxyServiceSource.fromId(prefs[ActiveMusicSourceKey])

    _profiles.value = parsedProfiles
    _routingConfig.value = parsedRouting
    _activeSource.value = parsedActiveSource

    syncInnerTubeProxy()
  }

  /**
   * Synchronizes the `:innertube` singleton (`YouTube.proxy` and `YouTube.proxyAuth`) with the
   * profile assigned to YouTube Music API (or legacy fallback).
   */
  fun syncInnerTubeProxy() {
    try {
      val ytApiProfile = getProfileFor(ProxyServiceSource.YOUTUBE_MUSIC, ProxyChannel.API)
      if (ytApiProfile != null) {
        YouTube.proxy = ytApiProfile.toResolvedJavaProxy()
        YouTube.proxyAuth =
          if (ytApiProfile.protocol == ProxyProtocol.HTTP) {
            ytApiProfile.basicAuthHeader()
          } else {
            null
          }
      } else {
        YouTube.proxy = null
        YouTube.proxyAuth = null
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to sync YouTube proxy")
    }
  }

  /**
   * Installs a global Java [Authenticator] that matches SOCKS5 proxy authentication requests
   * against any configured [ProxyProfile] by host/port, allowing multiple SOCKS5 profiles with
   * different credentials to work concurrently across services and channels.
   */
  private fun installSocksAuthenticator() {
    try {
      Authenticator.setDefault(
        object : Authenticator() {
          override fun getPasswordAuthentication(): PasswordAuthentication? {
            val currentProfiles = _profiles.value
            if (currentProfiles.isEmpty()) return null

            val reqHost = requestingHost?.trim().orEmpty()
            val reqPort = requestingPort

            val matched =
              currentProfiles.firstOrNull { profile ->
                profile.hasAuthentication &&
                  profile.host.equals(reqHost, ignoreCase = true) &&
                  (reqPort <= 0 || profile.port == reqPort)
              }
                ?: currentProfiles.firstOrNull { profile ->
                  profile.hasAuthentication &&
                    profile.protocol == ProxyProtocol.SOCKS5 &&
                    profile.host.equals(reqHost, ignoreCase = true)
                }
                ?: currentProfiles.firstOrNull { profile ->
                  profile.hasAuthentication && profile.protocol == ProxyProtocol.SOCKS5
                }

            return matched?.let {
              PasswordAuthentication(it.username, it.password.toCharArray())
            }
          }
        }
      )
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Could not set default SOCKS Authenticator")
    }
  }

  fun getProfileById(profileId: String?): ProxyProfile? {
    if (profileId.isNullOrBlank()) return null
    return _profiles.value.firstOrNull { it.id == profileId }
  }

  fun getProfileFor(source: ProxyServiceSource, channel: ProxyChannel): ProxyProfile? {
    val profileId = _routingConfig.value.profileIdFor(source, channel) ?: return null
    return getProfileById(profileId)
  }

  fun resolveJavaProxy(source: ProxyServiceSource, channel: ProxyChannel): Proxy {
    val profile = getProfileFor(source, channel) ?: return Proxy.NO_PROXY
    return runCatching { profile.toResolvedJavaProxy() }.getOrElse { Proxy.NO_PROXY }
  }

  /**
   * Infers which music service a network request or media item belongs to based on its mediaId
   * prefix or target URI host.
   */
  fun resolveSourceForRequest(
    urlOrHost: String?,
    mediaId: String? = null,
    fallbackSource: ProxyServiceSource? = null
  ): ProxyServiceSource {
    val cleanMediaId = mediaId?.lowercase().orEmpty()
    if (cleanMediaId.startsWith("sc_") || cleanMediaId.startsWith("soundcloud_") || cleanMediaId.startsWith("soundcloud:")) {
      return ProxyServiceSource.SOUNDCLOUD
    }
    if (cleanMediaId.startsWith("sp_") || cleanMediaId.startsWith("spotify_") || cleanMediaId.startsWith("spotify:")) {
      return ProxyServiceSource.SPOTIFY
    }

    val cleanUrl = urlOrHost?.lowercase().orEmpty()
    return when {
      cleanUrl.contains("soundcloud.com") ||
        cleanUrl.contains("sndcdn.com") -> ProxyServiceSource.SOUNDCLOUD
      cleanUrl.contains("spotify.com") ||
        cleanUrl.contains("scdn.co") ||
        cleanUrl.contains("spotifycdn.com") -> ProxyServiceSource.SPOTIFY
      cleanUrl.contains("youtube.com") ||
        cleanUrl.contains("googlevideo.com") ||
        cleanUrl.contains("ytimg.com") ||
        cleanUrl.contains("ggpht.com") ||
        cleanUrl.contains("googleusercontent.com") ||
        cleanUrl.contains("youtubei.googleapis.com") -> ProxyServiceSource.YOUTUBE_MUSIC
      else -> fallbackSource ?: _activeSource.value
    }
  }

  /**
   * Configures an [OkHttpClient.Builder] to dynamically route all requests through the proxy
   * profile assigned to [source] and [channel]. Changes in settings take effect immediately
   * without recreating the client.
   */
  fun configureOkHttpBuilder(
    builder: OkHttpClient.Builder,
    source: ProxyServiceSource,
    channel: ProxyChannel
  ): OkHttpClient.Builder {
    return builder
      .proxySelector(
        object : ProxySelector() {
          override fun select(uri: URI?): List<Proxy> {
            val proxy = resolveJavaProxy(source, channel)
            return listOf(proxy)
          }

          override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            Timber.tag(TAG).w("Proxy connect failed for $source [$channel] uri=$uri sa=$sa: ${ioe?.message}")
          }
        }
      )
      .proxyAuthenticator { _, response ->
        val profile = getProfileFor(source, channel)
        val authHeader = profile?.basicAuthHeader()
        if (authHeader != null && response.request.header("Proxy-Authorization") == null) {
          response.request.newBuilder().header("Proxy-Authorization", authHeader).build()
        } else {
          null
        }
      }
      .addInterceptor { chain ->
        val profile = getProfileFor(source, channel)
        val authHeader =
          if (profile?.protocol == ProxyProtocol.HTTP) profile.basicAuthHeader() else null
        val req =
          if (authHeader != null && chain.request().header("Proxy-Authorization") == null) {
            chain.request().newBuilder().header("Proxy-Authorization", authHeader).build()
          } else {
            chain.request()
          }
        chain.proceed(req)
      }
  }

  /**
   * Configures an [OkHttpClient.Builder] that inspects each request's target URI and routes it
   * through the appropriate service's proxy profile for [channel]. Used by shared clients such
   * as Coil's image loader (`API` channel) or multi-source stream fetchers (`CONTENT` channel).
   */
  fun configureAutoSourceOkHttpBuilder(
    builder: OkHttpClient.Builder,
    channel: ProxyChannel
  ): OkHttpClient.Builder {
    return builder
      .proxySelector(
        object : ProxySelector() {
          override fun select(uri: URI?): List<Proxy> {
            val source = resolveSourceForRequest(uri?.host ?: uri?.toString())
            return listOf(resolveJavaProxy(source, channel))
          }

          override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            Timber.tag(TAG).w("Auto-source proxy connect failed [$channel] uri=$uri: ${ioe?.message}")
          }
        }
      )
      .proxyAuthenticator { route, response ->
        val routeAddress = route?.proxy?.address() as? InetSocketAddress
        val profileByRoute =
          if (routeAddress != null) {
            _profiles.value.firstOrNull {
              it.host.equals(routeAddress.hostString, ignoreCase = true) &&
                it.port == routeAddress.port
            }
          } else {
            null
          }
        val source = resolveSourceForRequest(response.request.url.host)
        val profile = profileByRoute ?: getProfileFor(source, channel)
        val authHeader = profile?.basicAuthHeader()
        if (authHeader != null && response.request.header("Proxy-Authorization") == null) {
          response.request.newBuilder().header("Proxy-Authorization", authHeader).build()
        } else {
          null
        }
      }
  }

  fun getCoilOkHttpClient(): OkHttpClient = coilOkHttpClient

  /**
   * Opens an [HttpURLConnection] routed through the proxy profile assigned to [source] and [channel].
   */
  fun openConnection(
    url: URL,
    source: ProxyServiceSource,
    channel: ProxyChannel
  ): HttpURLConnection {
    val profile = getProfileFor(source, channel)
    val proxy =
      if (profile != null) {
        runCatching { profile.toResolvedJavaProxy() }.getOrDefault(Proxy.NO_PROXY)
      } else {
        Proxy.NO_PROXY
      }
    val connection = url.openConnection(proxy) as HttpURLConnection
    if (profile != null && profile.protocol == ProxyProtocol.HTTP) {
      profile.basicAuthHeader()?.let { auth ->
        connection.setRequestProperty("Proxy-Authorization", auth)
      }
    }
    return connection
  }

  suspend fun saveProfile(context: Context, profile: ProxyProfile) {
    val cleanProfile =
      profile.copy(
        name = profile.name.trim().ifBlank { "${profile.protocol.displayName} ${profile.host}" },
        host = profile.host.trim(),
        port = profile.port.coerceIn(1, 65535),
        username = profile.username.trim(),
        password = profile.password
      )
    val updated =
      _profiles.value.toMutableList().apply {
        val existingIdx = indexOfFirst { it.id == cleanProfile.id }
        if (existingIdx >= 0) {
          set(existingIdx, cleanProfile)
        } else {
          add(cleanProfile)
        }
      }
    _profiles.value = updated
    syncInnerTubeProxy()

    context.dataStore.edit { prefs ->
      prefs[ProxyProfilesJsonKey] = json.encodeToString(updated)
    }
  }

  suspend fun deleteProfile(context: Context, profileId: String) {
    val updatedProfiles = _profiles.value.filterNot { it.id == profileId }
    val updatedRouting = _routingConfig.value.removeProfileReferences(profileId)
    _profiles.value = updatedProfiles
    _routingConfig.value = updatedRouting
    syncInnerTubeProxy()

    context.dataStore.edit { prefs ->
      prefs[ProxyProfilesJsonKey] = json.encodeToString(updatedProfiles)
      prefs[ProxyRoutingConfigJsonKey] = json.encodeToString(updatedRouting)
    }
  }

  suspend fun setServiceProxy(
    context: Context,
    source: ProxyServiceSource,
    channel: ProxyChannel,
    profileId: String?
  ) {
    val updatedRouting = _routingConfig.value.withRouting(source, channel, profileId)
    _routingConfig.value = updatedRouting
    syncInnerTubeProxy()

    context.dataStore.edit { prefs ->
      prefs[ProxyRoutingConfigJsonKey] = json.encodeToString(updatedRouting)
      if (source == ProxyServiceSource.YOUTUBE_MUSIC && channel == ProxyChannel.API) {
        val profile = getProfileById(profileId)
        prefs[ProxyEnabledKey] = (profile != null)
        if (profile != null) {
          prefs[ProxyUrlKey] = "${profile.host}:${profile.port}"
          prefs[ProxyTypeKey] = profile.protocol.toJavaProxyType().name
          prefs[ProxyUsernameKey] = profile.username
          prefs[ProxyPasswordKey] = profile.password
        }
      }
    }
  }

  suspend fun setActiveSource(context: Context, source: ProxyServiceSource) {
    _activeSource.value = source
    context.dataStore.edit { prefs ->
      prefs[ActiveMusicSourceKey] = source.id
    }
  }

  /**
   * Tests a [ProxyProfile] by performing a real HTTP request through the proxy and measuring
   * round-trip latency in milliseconds and HTTP status code.
   */
  suspend fun testProxyConnection(
    profile: ProxyProfile,
    targetUrl: String = DEFAULT_TEST_URL
  ): ProxyTestStatus =
    withContext(Dispatchers.IO) {
      val cleanHost = profile.host.trim()
      if (cleanHost.isBlank()) {
        return@withContext ProxyTestStatus.Failure("Укажите хост (IP или домен)")
      }
      if (profile.port !in 1..65535) {
        return@withContext ProxyTestStatus.Failure("Некорректный порт (1–65535)")
      }

      // Ensure SOCKS authenticator knows about this profile even if it hasn't been saved yet
      val tempProfiles =
        if (_profiles.value.any { it.id == profile.id }) _profiles.value
        else listOf(profile) + _profiles.value
      val previousProfiles = _profiles.value
      _profiles.value = tempProfiles

      val startMs = SystemClock.elapsedRealtime()
      try {
        val javaProxy = profile.toResolvedJavaProxy()
        val testClient =
          OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .proxy(javaProxy)
            .proxyAuthenticator { _, response ->
              val auth = profile.basicAuthHeader()
              if (auth != null && response.request.header("Proxy-Authorization") == null) {
                response.request.newBuilder().header("Proxy-Authorization", auth).build()
              } else {
                null
              }
            }
            .build()

        val requestBuilder =
          Request.Builder()
            .url(targetUrl)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Android 15; Mobile; rv:132.0) EchoMusic/1.4")

        if (profile.protocol == ProxyProtocol.HTTP) {
          profile.basicAuthHeader()?.let { auth ->
            requestBuilder.header("Proxy-Authorization", auth)
          }
        }

        testClient.newCall(requestBuilder.build()).execute().use { response ->
          val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
          val code = response.code
          if (code in 200..399) {
            ProxyTestStatus.Success(
              pingMs = elapsedMs,
              statusCode = code,
              statusMessage = "HTTP $code ${response.message.ifBlank { "OK" }}"
            )
          } else {
            ProxyTestStatus.Failure(
              errorMessage = "Код ответа: HTTP $code ${response.message}",
              pingMs = elapsedMs
            )
          }
        }
      } catch (e: Exception) {
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        val reason = e.localizedMessage ?: e.javaClass.simpleName ?: "Ошибка соединения"
        ProxyTestStatus.Failure(
          errorMessage = reason,
          pingMs = elapsedMs
        )
      } finally {
        _profiles.value = previousProfiles
      }
    }
}
