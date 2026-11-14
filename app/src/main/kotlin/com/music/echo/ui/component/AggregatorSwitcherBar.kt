package com.music.echo.ui.component

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.echo.proxy.ProxyChannel
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.echo.soundcloud.SoundCloud
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.InnerTubeCookieKey
import echo.music.iad1tya.constants.SpotifySpDcKey
import echo.music.iad1tya.utils.rememberPreference

@Composable
fun AggregatorSwitcherBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    onSourceChanged: ((ProxyServiceSource) -> Unit)? = null,
    onOpenProxySettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activeSource by ProxyRouter.activeSource.collectAsState()
    val routingConfig by ProxyRouter.routingConfig.collectAsState()
    val profiles by ProxyRouter.profiles.collectAsState()

    val (ytCookie) = rememberPreference(InnerTubeCookieKey, defaultValue = "")
    val (spotifySpDc) = rememberPreference(SpotifySpDcKey, defaultValue = "")

    Column(modifier = modifier.fillMaxWidth()) {
        val coroutineScope = rememberCoroutineScope()
    Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Агрегаторы источников",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (onOpenProxySettings != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onOpenProxySettings() }
                        .testTag("aggregator_open_proxy_settings")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.wifi_proxy),
                            contentDescription = "Настройки прокси",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Прокси (${profiles.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProxyServiceSource.entries.forEach { source ->
                val isSelected = activeSource == source
                val serviceRouting = when (source) { ProxyServiceSource.YOUTUBE_MUSIC -> routingConfig.youtubeMusic; ProxyServiceSource.SOUNDCLOUD -> routingConfig.soundCloud; ProxyServiceSource.SPOTIFY -> routingConfig.spotify }
                val apiProfile = profiles.firstOrNull { it.id == serviceRouting.apiProfileId }
                val contentProfile = profiles.firstOrNull { it.id == serviceRouting.contentProfileId }

                val (statusText, isAuthorizedOrReady) = when (source) {
                    ProxyServiceSource.YOUTUBE_MUSIC -> {
                        if (ytCookie.isNotBlank()) "Авторизован" to true
                        else "Готов (публичный)" to true
                    }
                    ProxyServiceSource.SOUNDCLOUD -> {
                        if (!SoundCloud.oauthToken.isNullOrBlank()) "OAuth активен" to true
                        else "Готов (API v2)" to true
                    }
                    ProxyServiceSource.SPOTIFY -> {
                        if (spotifySpDc.isNotBlank()) "Авторизован (sp_dc)" to true
                        else "Готов (Web API + DRM-мост)" to true
                    }
                }

                AggregatorCard(
                    source = source,
                    isSelected = isSelected,
                    statusText = statusText,
                    isAuthorizedOrReady = isAuthorizedOrReady,
                    apiProxyName = apiProfile?.name,
                    contentProxyName = contentProfile?.name,
                    onClick = {
                        coroutineScope.launch { ProxyRouter.setActiveSource(context, source) }
                        onSourceChanged?.invoke(source)
                    }
                )
            }
        }
    }
}

@Composable
private fun AggregatorCard(
    source: ProxyServiceSource,
    isSelected: Boolean,
    statusText: String,
    isAuthorizedOrReady: Boolean,
    apiProxyName: String?,
    contentProxyName: String?,
    onClick: () -> Unit
) {
    val accentColor = when (source) {
        ProxyServiceSource.YOUTUBE_MUSIC -> Color(0xFFFF0033)
        ProxyServiceSource.SOUNDCLOUD -> Color(0xFFFF5500)
        ProxyServiceSource.SPOTIFY -> Color(0xFF1DB954)
    }

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 220),
        label = "aggregatorCardBg"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            accentColor.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "aggregatorCardBorder"
    )

    val iconRes = when (source) {
        ProxyServiceSource.YOUTUBE_MUSIC -> R.drawable.music_note
        ProxyServiceSource.SOUNDCLOUD -> R.drawable.cloud
        ProxyServiceSource.SPOTIFY -> R.drawable.library_music
    }

    Card(
        modifier = Modifier
            .width(218.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("aggregator_card_${source.name.lowercase()}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = source.displayName,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = source.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accentColor.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = "Активен",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAuthorizedOrReady) Color(0xFF2EBD59) else Color(0xFFF59E0B)
                        )
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val proxySummary = when {
                apiProxyName == null && contentProxyName == null -> "Прокси: Напрямую"
                apiProxyName != null && apiProxyName == contentProxyName -> "Прокси: $apiProxyName"
                else -> "API: ${apiProxyName ?: "Нет"} • Аудио: ${contentProxyName ?: "Нет"}"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = proxySummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (apiProxyName != null || contentProxyName != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
