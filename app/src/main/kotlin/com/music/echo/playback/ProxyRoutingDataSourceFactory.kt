package com.music.echo.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.echo.proxy.ProxyChannel
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.innertube.models.IpVersion
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Dynamic Media3 [DataSource.Factory] for `:playback` (`MusicService` and `DownloadUtil`) that
 * routes every audio stream and track download through the **CONTENT** proxy profile assigned
 * to the track's source ([ProxyServiceSource.YOUTUBE_MUSIC], [ProxyServiceSource.SOUNDCLOUD],
 * or [ProxyServiceSource.SPOTIFY]).
 *
 * When a Spotify DRM track (`sp_<id>`) is resolved via auto-matching (YouTube Music / SoundCloud):
 * - If a CONTENT proxy profile is assigned to Spotify, the stream is routed through Spotify's CONTENT proxy.
 * - If Spotify's CONTENT proxy is set to "Direct (None)", it checks the underlying CDN host
 *   (`googlevideo.com` / `sndcdn.com`) and uses that service's CONTENT proxy if configured.
 */
@UnstableApi
class ProxyRoutingDataSourceFactory(
  private val ipVersionProvider: () -> IpVersion = { IpVersion.AUTO }
) : DataSource.Factory {

  private val connectionPool = ConnectionPool(15, 10, TimeUnit.MINUTES)

  private val delegatesBySource =
    ConcurrentHashMap<ProxyServiceSource, OkHttpDataSource.Factory>()

  private fun factoryFor(source: ProxyServiceSource): OkHttpDataSource.Factory {
    return delegatesBySource.getOrPut(source) {
      val clientBuilder =
        OkHttpClient.Builder()
          .connectionPool(connectionPool)
          .connectTimeout(30, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .dns(
            object : Dns {
              override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return when (ipVersionProvider()) {
                  IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                  IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                  IpVersion.AUTO -> addresses
                }
              }
            }
          )
      ProxyRouter.configureOkHttpBuilder(clientBuilder, source, ProxyChannel.CONTENT)
      OkHttpDataSource.Factory(clientBuilder.build())
    }
  }

  override fun createDataSource(): DataSource {
    return RoutingDataSource()
  }

  private inner class RoutingDataSource : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDelegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
      transferListeners.add(transferListener)
      activeDelegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
      val primarySource =
        ProxyRouter.resolveSourceForRequest(
          urlOrHost = dataSpec.uri.toString(),
          mediaId = dataSpec.key
        )
      val effectiveSource =
        if (
          primarySource == ProxyServiceSource.SPOTIFY &&
            ProxyRouter.getProfileFor(ProxyServiceSource.SPOTIFY, ProxyChannel.CONTENT) == null
        ) {
          ProxyRouter.resolveSourceForRequest(
            urlOrHost = dataSpec.uri.toString(),
            mediaId = null,
            fallbackSource = ProxyServiceSource.SPOTIFY
          )
        } else {
          primarySource
        }

      val delegate = factoryFor(effectiveSource).createDataSource()
      transferListeners.forEach { delegate.addTransferListener(it) }
      activeDelegate = delegate
      return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val delegate = activeDelegate ?: return androidx.media3.common.C.RESULT_END_OF_INPUT
      return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = activeDelegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
      activeDelegate?.responseHeaders ?: emptyMap()

    override fun close() {
      try {
        activeDelegate?.close()
      } finally {
        activeDelegate = null
      }
    }
  }
}
