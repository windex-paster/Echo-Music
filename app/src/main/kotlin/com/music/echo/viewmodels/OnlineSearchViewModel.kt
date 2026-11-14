package echo.music.iad1tya.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.echo.proxy.ProxyRouter
import com.music.echo.proxy.ProxyServiceSource
import com.music.echo.soundcloud.SoundCloud
import com.music.echo.spotify.SpotifyCatalogBridge
import com.music.innertube.YouTube
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.models.filterYoutubeShorts
import com.music.innertube.pages.SearchSummary
import com.music.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.HideExplicitKey
import echo.music.iad1tya.constants.HideVideoSongsKey
import echo.music.iad1tya.constants.HideYoutubeShortsKey
import echo.music.iad1tya.constants.SpotifySpDcKey
import echo.music.iad1tya.constants.SpotifySpKeyKey
import echo.music.iad1tya.models.ItemsPage
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.get
import echo.music.iad1tya.utils.reportException
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
  @ApplicationContext val context: Context,
  savedStateHandle: SavedStateHandle,
  val extensionManager: com.music.echo.extensions.ExtensionManager,
) : ViewModel() {
  val query =
    try {
      URLDecoder.decode(savedStateHandle.get<String>("query")!!, "UTF-8")
    } catch (e: IllegalArgumentException) {
      savedStateHandle.get<String>("query")!!
    }
  val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
  var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
  val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
  private var lastFetchedSource: ProxyServiceSource? = null

  init {
    viewModelScope.launch {
      combine(filter, ProxyRouter.activeSource) { currentFilter, activeSource ->
        currentFilter to activeSource
      }.collect { (currentFilter, activeSource) ->
        if (lastFetchedSource != activeSource) {
          lastFetchedSource = activeSource
          summaryPage = null
          viewStateMap.clear()
        }

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)

        when (activeSource) {
          ProxyServiceSource.SOUNDCLOUD -> {
            if (currentFilter == null) {
              if (summaryPage == null) {
                SoundCloud.searchSummary(query)
                  .onSuccess { scSummary ->
                    val filteredSummaries = scSummary.summaries.map { summary ->
                      summary.copy(
                        items = summary.items.filterExplicit(hideExplicit)
                      )
                    }
                    summaryPage = SearchSummaryPage(summaries = filteredSummaries)
                  }
                  .onFailure { reportException(it) }
              }
            } else {
              if (viewStateMap[currentFilter.value] == null) {
                SoundCloud.searchFiltered(query, currentFilter.value, offset = 0)
                  .onSuccess { page ->
                    viewStateMap[currentFilter.value] =
                      ItemsPage(
                        items = page.items.distinctBy { it.id }.filterExplicit(hideExplicit),
                        continuation = page.continuation
                      )
                  }
                  .onFailure { reportException(it) }
              }
            }
          }

          ProxyServiceSource.SPOTIFY -> {
            val spDc = context.dataStore.get(SpotifySpDcKey, "")
            val spKey = context.dataStore.get(SpotifySpKeyKey, "")
            if (currentFilter == null) {
              if (summaryPage == null) {
                SpotifyCatalogBridge.searchSummary(query = query, spDc = spDc, spKey = spKey)
                  .onSuccess { spSummary ->
                    val filteredSummaries = spSummary.summaries.map { summary ->
                      summary.copy(
                        items = summary.items.filterExplicit(hideExplicit)
                      )
                    }
                    summaryPage = SearchSummaryPage(summaries = filteredSummaries)
                  }
                  .onFailure { reportException(it) }
              }
            } else {
              if (viewStateMap[currentFilter.value] == null) {
                SpotifyCatalogBridge.searchFiltered(
                    query = query,
                    filterValue = currentFilter.value,
                    offset = 0,
                    spDc = spDc,
                    spKey = spKey
                  )
                  .onSuccess { page ->
                    viewStateMap[currentFilter.value] =
                      ItemsPage(
                        items = page.items.distinctBy { it.id }.filterExplicit(hideExplicit),
                        continuation = page.continuation
                      )
                  }
                  .onFailure { reportException(it) }
              }
            }
          }

          ProxyServiceSource.YOUTUBE_MUSIC -> {
            if (currentFilter == null) {
              if (summaryPage == null) {
                var addonItems: List<com.music.innertube.models.SongItem> = emptyList()
                try {
                  addonItems = extensionManager.search(query)
                } catch (_: Exception) {
                }

                YouTube.searchSummary(query)
                  .onSuccess {
                    val filtered =
                      it.filterExplicit(hideExplicit)
                        .filterVideoSongs(hideVideoSongs)
                        .filterYoutubeShorts(hideYoutubeShorts)

                    if (addonItems.isNotEmpty()) {
                      val modSummaries = filtered.summaries.toMutableList()
                      modSummaries.add(0, SearchSummary(title = "Addons", items = addonItems))
                      summaryPage = SearchSummaryPage(summaries = modSummaries)
                    } else {
                      summaryPage = filtered
                    }
                  }
                  .onFailure { reportException(it) }
              }
            } else {
              if (viewStateMap[currentFilter.value] == null) {
                YouTube.search(query, currentFilter)
                  .onSuccess { result ->
                    viewStateMap[currentFilter.value] =
                      ItemsPage(
                        result.items
                          .distinctBy { it.id }
                          .filterExplicit(hideExplicit)
                          .let { items ->
                            if (currentFilter.value == YouTube.SearchFilter.FILTER_VIDEO.value) items
                            else items.filterVideoSongs(hideVideoSongs)
                          }
                          .filterYoutubeShorts(hideYoutubeShorts),
                        result.continuation,
                      )
                  }
                  .onFailure { reportException(it) }
              }
            }
          }
        }
      }
    }
  }

  fun loadMore() {
    val filterVal = filter.value?.value
    val activeSource = ProxyRouter.activeSource.value
    viewModelScope.launch {
      if (filterVal == null) return@launch
      val viewState = viewStateMap[filterVal] ?: return@launch
      val continuation = viewState.continuation ?: return@launch

      val hideExplicit = context.dataStore.get(HideExplicitKey, false)
      val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
      val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)

      when (activeSource) {
        ProxyServiceSource.SOUNDCLOUD -> {
          val offset = continuation.toIntOrNull() ?: return@launch
          val nextPage = SoundCloud.searchFiltered(query, filterVal, offset = offset).getOrNull() ?: return@launch
          val newItems = nextPage.items.filterExplicit(hideExplicit)
          viewStateMap[filterVal] =
            ItemsPage(
              items = (viewState.items + newItems).distinctBy { it.id },
              continuation = nextPage.continuation
            )
        }

        ProxyServiceSource.SPOTIFY -> {
          val offset = continuation.toIntOrNull() ?: return@launch
          val spDc = context.dataStore.get(SpotifySpDcKey, "")
          val spKey = context.dataStore.get(SpotifySpKeyKey, "")
          val nextPage =
            SpotifyCatalogBridge.searchFiltered(
                query = query,
                filterValue = filterVal,
                offset = offset,
                spDc = spDc,
                spKey = spKey
              )
              .getOrNull() ?: return@launch
          val newItems = nextPage.items.filterExplicit(hideExplicit)
          viewStateMap[filterVal] =
            ItemsPage(
              items = (viewState.items + newItems).distinctBy { it.id },
              continuation = nextPage.continuation
            )
        }

        ProxyServiceSource.YOUTUBE_MUSIC -> {
          val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
          val newItems =
            searchResult.items
              .filterExplicit(hideExplicit)
              .let { items ->
                if (filterVal == YouTube.SearchFilter.FILTER_VIDEO.value) items
                else items.filterVideoSongs(hideVideoSongs)
              }
              .filterYoutubeShorts(hideYoutubeShorts)
          viewStateMap[filterVal] =
            ItemsPage((viewState.items + newItems).distinctBy { it.id }, searchResult.continuation)
        }
      }
    }
  }
}
