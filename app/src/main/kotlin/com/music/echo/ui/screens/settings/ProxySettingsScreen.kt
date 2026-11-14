package echo.music.iad1tya.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.echo.proxy.ProxyChannel
import com.music.echo.proxy.ProxyProfile
import com.music.echo.proxy.ProxyProtocol
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.echo.proxy.ProxyTestStatus
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.utils.backToMain
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior? = null
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scrollState = rememberScrollState()

  val profiles by ProxyRouter.profiles.collectAsState()
  val routingConfig by ProxyRouter.routingConfig.collectAsState()

  val profileTestStates = remember { mutableStateMapOf<String, ProxyTestStatus>() }
  var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
  var isCreatingNewProfile by rememberSaveable { mutableStateOf(false) }
  var profilePendingDelete by remember { mutableStateOf<ProxyProfile?>(null) }

  if (isCreatingNewProfile || editingProfile != null) {
    ProxyProfileEditorDialog(
      initialProfile = editingProfile,
      onDismiss = {
        isCreatingNewProfile = false
        editingProfile = null
      },
      onSave = { savedProfile ->
        scope.launch {
          ProxyRouter.saveProfile(context, savedProfile)
          isCreatingNewProfile = false
          editingProfile = null
        }
      }
    )
  }

  profilePendingDelete?.let { target ->
    AlertDialog(
      onDismissRequest = { profilePendingDelete = null },
      title = { Text("Удалить прокси-профиль?") },
      text = {
        Text(
          "Профиль «${target.name}» (${target.host}:${target.port}) будет удалён. Сервисы, использовавшие его, переключатся на прямое соединение."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            scope.launch {
              ProxyRouter.deleteProfile(context, target.id)
              profileTestStates.remove(target.id)
              profilePendingDelete = null
            }
          }
        ) {
          Text("Удалить", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { profilePendingDelete = null }) {
          Text("Отмена")
        }
      }
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .windowInsetsPadding(
          LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
        )
        .verticalScroll(scrollState)
        .padding(horizontal = 16.dp)
  ) {
    Spacer(
      Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    )
    Spacer(modifier = Modifier.height(64.dp))

    // Header banner
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors =
        CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
      elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Box(
          modifier =
            Modifier.size(44.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            painter = painterResource(R.drawable.wifi_proxy),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp)
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Менеджер прокси и маршрутизация",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "Раздельная настройка прокси для входа/API и потокового аудио (YouTube Music, SoundCloud, Spotify)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Section 1: Proxy Profiles Manager
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column {
        Text(
          text = "ПРОФИЛИ ПРОКСИ",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "SOCKS5 и HTTP/HTTPS серверы",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
      }

      FilledTonalButton(
        onClick = { isCreatingNewProfile = true },
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
      ) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = "Добавить профиль",
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("Добавить")
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    if (profiles.isEmpty()) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
          ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
          )
          Text(
            text = "Нет сохранённых прокси-профилей",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "Добавьте SOCKS5 или HTTP прокси (например, «Великобритания 1»), чтобы назначать их для API и аудиопотока каждого музыкального сервиса.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = { isCreatingNewProfile = true },
            shape = RoundedCornerShape(16.dp)
          ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Создать первый профиль")
          }
        }
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
          val testStatus = profileTestStates[profile.id] ?: ProxyTestStatus.Idle
          ProxyProfileItemCard(
            profile = profile,
            testStatus = testStatus,
            onTestClick = {
              profileTestStates[profile.id] = ProxyTestStatus.Testing
              scope.launch {
                profileTestStates[profile.id] = ProxyRouter.testProxyConnection(profile)
              }
            },
            onEditClick = { editingProfile = profile },
            onDeleteClick = { profilePendingDelete = profile }
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Section 2: Per-Service Dual Routing (Murglar-style)
    Text(
      text = "МАРШРУТИЗАЦИЯ ПО СЕРВИСАМ",
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp)
    )
    Text(
      text = "Независимый выбор прокси для API (вход, поиск, метаданные, обложки) и для получения аудиопотока",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )

    Spacer(modifier = Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ProxyServiceSource.entries.forEach { source ->
        val serviceRouting = routingConfig.routingFor(source)
        ServiceProxyRoutingCard(
          source = source,
          profiles = profiles,
          selectedApiProfileId = serviceRouting.apiProfileId,
          selectedContentProfileId = serviceRouting.contentProfileId,
          onApiProfileSelected = { profileId ->
            scope.launch {
              ProxyRouter.setServiceProxy(context, source, ProxyChannel.API, profileId)
            }
          },
          onContentProfileSelected = { profileId ->
            scope.launch {
              ProxyRouter.setServiceProxy(context, source, ProxyChannel.CONTENT, profileId)
            }
          }
        )
      }
    }

    Spacer(modifier = Modifier.height(32.dp))
    Spacer(
      Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
      )
    )
  }

  TopAppBar(
    title = { Text("Прокси и маршрутизация") },
    navigationIcon = {
      IconButton(
        onClick = navController::navigateUp,
        onLongClick = navController::backToMain
      ) {
        Icon(
          painterResource(R.drawable.arrow_back),
          contentDescription = "Назад"
        )
      }
    }
  )
}

@Composable
private fun ProxyProfileItemCard(
  profile: ProxyProfile,
  testStatus: ProxyTestStatus,
  onTestClick: () -> Unit,
  onEditClick: () -> Unit,
  onDeleteClick: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = profile.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.secondaryContainer
            ) {
              Text(
                text = profile.protocol.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text =
              buildString {
                append("${profile.host}:${profile.port}")
                if (profile.hasAuthentication) {
                  append(" • Логин: ${profile.username}")
                } else {
                  append(" • Без авторизации")
                }
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          M3IconButton(onClick = onEditClick) {
            Icon(
              imageVector = Icons.Rounded.Edit,
              contentDescription = "Редактировать профиль",
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          M3IconButton(onClick = onDeleteClick) {
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "Удалить профиль",
              tint = MaterialTheme.colorScheme.error
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        OutlinedButton(
          onClick = onTestClick,
          enabled = testStatus !is ProxyTestStatus.Testing,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          if (testStatus is ProxyTestStatus.Testing) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Проверка...")
          } else {
            Icon(
              imageVector = Icons.Rounded.NetworkCheck,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Проверить соединение")
          }
        }

        when (testStatus) {
          is ProxyTestStatus.Idle -> {}
          is ProxyTestStatus.Testing -> {}
          is ProxyTestStatus.Success -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${testStatus.pingMs} мс • ${testStatus.statusMessage}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }
          is ProxyTestStatus.Failure -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              modifier = Modifier.padding(start = 8.dp)
            ) {
              Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text =
                  buildString {
                    testStatus.pingMs?.let { append("${it} мс • ") }
                    append(testStatus.errorMessage)
                  },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceProxyRoutingCard(
  source: ProxyServiceSource,
  profiles: List<ProxyProfile>,
  selectedApiProfileId: String?,
  selectedContentProfileId: String?,
  onApiProfileSelected: (String?) -> Unit,
  onContentProfileSelected: (String?) -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier =
            Modifier.size(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.secondaryContainer),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            painter =
              painterResource(
                when (source) {
                  ProxyServiceSource.YOUTUBE_MUSIC -> R.drawable.library_music
                  ProxyServiceSource.SOUNDCLOUD -> R.drawable.cloud
                  ProxyServiceSource.SPOTIFY -> R.drawable.ic_spotify
                }
              ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp)
          )
        }
        Column {
          Text(
            text = source.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          val apiProfileName =
            profiles.firstOrNull { it.id == selectedApiProfileId }?.name ?: "Нет (напрямую)"
          val contentProfileName =
            profiles.firstOrNull { it.id == selectedContentProfileId }?.name ?: "Нет (напрямую)"
          Text(
            text = "API: $apiProfileName • Аудио: $contentProfileName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      ProxyProfileDropdownSelector(
        label = "Использовать прокси для входа и API сервиса",
        supportingText = "Авторизация, поиск, метаданные, плейлисты и обложки",
        profiles = profiles,
        selectedProfileId = selectedApiProfileId,
        onProfileSelected = onApiProfileSelected
      )

      ProxyProfileDropdownSelector(
        label = "Использовать прокси для получения контента",
        supportingText = "Потоковое аудио и скачивание треков в плеере (Media3 / ExoPlayer)",
        profiles = profiles,
        selectedProfileId = selectedContentProfileId,
        onProfileSelected = onContentProfileSelected
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyProfileDropdownSelector(
  label: String,
  supportingText: String,
  profiles: List<ProxyProfile>,
  selectedProfileId: String?,
  onProfileSelected: (String?) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
  val displayValue =
    selectedProfile?.let { "${it.name} (${it.protocol.displayName} • ${it.host}:${it.port})" }
      ?: "Нет (напрямую)"

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = !expanded },
      modifier = Modifier.fillMaxWidth()
    ) {
      OutlinedTextField(
        value = displayValue,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = {
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        },
        shape = RoundedCornerShape(16.dp),
        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        modifier =
          Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
      )

      ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
      ) {
        DropdownMenuItem(
          text = {
            Column {
              Text(
                text = "Нет (напрямую)",
                fontWeight =
                  if (selectedProfile == null) FontWeight.Bold else FontWeight.Normal
              )
              Text(
                text = "Прямое подключение без прокси",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          },
          onClick = {
            onProfileSelected(null)
            expanded = false
          }
        )

        profiles.forEach { profile ->
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  text = profile.name,
                  fontWeight =
                    if (profile.id == selectedProfileId) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                  text = "${profile.protocol.displayName} • ${profile.host}:${profile.port}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            },
            onClick = {
              onProfileSelected(profile.id)
              expanded = false
            }
          )
        }
      }
    }
    Text(
      text = supportingText,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
      modifier = Modifier.padding(horizontal = 4.dp)
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyProfileEditorDialog(
  initialProfile: ProxyProfile?,
  onDismiss: () -> Unit,
  onSave: (ProxyProfile) -> Unit
) {
  val scope = rememberCoroutineScope()

  var name by rememberSaveable { mutableStateOf(initialProfile?.name ?: "") }
  var protocol by rememberSaveable {
    mutableStateOf(initialProfile?.protocol ?: ProxyProtocol.SOCKS5)
  }
  var host by rememberSaveable { mutableStateOf(initialProfile?.host ?: "") }
  var portText by rememberSaveable {
    mutableStateOf(initialProfile?.port?.toString() ?: "1080")
  }
  var username by rememberSaveable { mutableStateOf(initialProfile?.username ?: "") }
  var password by rememberSaveable { mutableStateOf(initialProfile?.password ?: "") }
  var showPassword by rememberSaveable { mutableStateOf(false) }

  var testStatus by remember { mutableStateOf<ProxyTestStatus>(ProxyTestStatus.Idle) }
  var validationError by remember { mutableStateOf<String?>(null) }

  fun buildCandidateProfile(): ProxyProfile? {
    val cleanHost = host.trim()
    val parsedPort = portText.trim().toIntOrNull()
    if (cleanHost.isEmpty()) {
      validationError = "Введите хост (IP или домен)"
      return null
    }
    if (parsedPort == null || parsedPort !in 1..65535) {
      validationError = "Введите корректный порт (1–65535)"
      return null
    }
    validationError = null
    return ProxyProfile(
      id = initialProfile?.id ?: UUID.randomUUID().toString(),
      name = name.trim().ifEmpty { "${protocol.displayName} ($cleanHost)" },
      protocol = protocol,
      host = cleanHost,
      port = parsedPort,
      username = username.trim(),
      password = password
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (initialProfile == null) "Новый прокси-профиль" else "Редактировать профиль",
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Название профиля") },
          placeholder = { Text("Например: Великобритания 1") },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth()
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "Тип протокола",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ProxyProtocol.entries.forEach { item ->
              FilterChip(
                selected = (protocol == item),
                onClick = {
                  protocol = item
                  if (portText == "1080" && item == ProxyProtocol.HTTP) {
                    portText = "8080"
                  } else if (portText == "8080" && item == ProxyProtocol.SOCKS5) {
                    portText = "1080"
                  }
                },
                label = { Text(item.displayName) },
                modifier = Modifier.weight(1f)
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = host,
            onValueChange = {
              host = it
              validationError = null
            },
            label = { Text("Хост (IP / домен)") },
            placeholder = { Text("185.x.x.x") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(0.68f)
          )

          OutlinedTextField(
            value = portText,
            onValueChange = {
              portText = it.filter { ch -> ch.isDigit() }
              validationError = null
            },
            label = { Text("Порт") },
            placeholder = { Text("1080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(0.32f)
          )
        }

        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("Логин (необязательно)") },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("Пароль (необязательно)") },
          singleLine = true,
          visualTransformation =
            if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            TextButton(onClick = { showPassword = !showPassword }) {
              Text(if (showPassword) "Скрыть" else "Показать")
            }
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(visible = validationError != null) {
          Text(
            text = validationError.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
          )
        }

        FilledTonalButton(
          onClick = {
            val candidate = buildCandidateProfile() ?: return@FilledTonalButton
            testStatus = ProxyTestStatus.Testing
            scope.launch {
              testStatus = ProxyRouter.testProxyConnection(candidate)
            }
          },
          enabled = testStatus !is ProxyTestStatus.Testing,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          if (testStatus is ProxyTestStatus.Testing) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Проверка соединения...")
          } else {
            Icon(
              imageVector = Icons.Rounded.Speed,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Проверить соединение")
          }
        }

        when (val status = testStatus) {
          is ProxyTestStatus.Idle, is ProxyTestStatus.Testing -> {}
          is ProxyTestStatus.Success -> {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Rounded.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = "Соединение установлено: ${status.pingMs} мс (${status.statusMessage})",
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer
                )
              }
            }
          }
          is ProxyTestStatus.Failure -> {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Rounded.ErrorOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text =
                    buildString {
                      append("Ошибка: ${status.errorMessage}")
                      status.pingMs?.let { append(" (${it} мс)") }
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val candidate = buildCandidateProfile() ?: return@Button
          onSave(candidate)
        },
        shape = RoundedCornerShape(14.dp)
      ) {
        Text("Сохранить")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена")
      }
    }
  )
}
