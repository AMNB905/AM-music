package com.am.music.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.online.DiscoverFeed
import com.am.music.data.online.OnlinePlaylist
import com.am.music.ui.components.PlaybackPending
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 搜索结果按音源分组：一次搜索两个平台都返回，界面分区展示并标出来源。 */
data class SearchGroup(
    val platform: OnlinePlatform,
    val songs: List<Song>,
)

data class OnlineUiState(
    val platform: OnlinePlatform = OnlinePlatform.QQ,
    val query: String = "",
    val hotKeywords: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val playlists: List<OnlinePlaylist> = emptyList(),
    val dailySongs: List<Song> = emptyList(),
    val openedPlaylist: OnlinePlaylist? = null,
    /** 搜索结果按音源分组；[songs] 是它们的顺序展开，播放下标都以它为准。 */
    val searchGroups: List<SearchGroup> = emptyList(),
    val songs: List<Song> = emptyList(),
    val isSearching: Boolean = false,
    /** 已经有内容在显示、后台还在刷新（此时不该盖骨架屏）。 */
    val isRefreshing: Boolean = false,
    val isResolving: Boolean = false,
    val reachable: Boolean? = null,
    val message: String? = null,
)

class OnlineMusicViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.onlineMusicRepository

    /** 每个音源各自一份发现页数据：切回上一个音源时不重新等接口。 */
    private val discoverByPlatform = mutableMapOf<OnlinePlatform, DiscoverFeed>()

    /** 关键词 → 两个平台的结果。同一个词再搜、或切音源重看结果，都不必重新请求。 */
    private val searchByKeyword = mutableMapOf<String, List<SearchGroup>>()

    private val _uiState = MutableStateFlow(
        OnlineUiState(searchHistory = container.searchHistoryRepository.recent())
    )
    val uiState: StateFlow<OnlineUiState> = _uiState.asStateFlow()

    init {
        loadDiscover(OnlinePlatform.QQ)
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    /**
     * 切音源：**先渲染，不等待**。
     *
     * 顺序是「内存里这个音源的那一份 → 磁盘快照 → 后台刷新」，三档都不发请求，
     * 所以页签是按下去立刻换内容；网络刷新在后台悄悄进行，回来了再替换。
     */
    fun switchPlatform(platform: OnlinePlatform) {
        if (platform == _uiState.value.platform) return
        val cached = discoverByPlatform[platform]
        _uiState.update {
            it.copy(
                platform = platform,
                hotKeywords = cached?.hotKeywords ?: emptyList(),
                playlists = cached?.playlists ?: emptyList(),
                dailySongs = cached?.dailySongs ?: emptyList(),
                reachable = cached?.reachable,
                isRefreshing = true,
                message = null,
                // 搜索结果按新音源重排，不发新请求
                searchGroups = reorder(_uiState.value.searchGroups, platform),
                songs = flatten(reorder(_uiState.value.searchGroups, platform)),
            )
        }
        // 内存里没有这份，就先读盘（只读磁盘，不碰网络）
        if (cached == null) {
            viewModelScope.launch {
                val snapshot = runCatching { repository.discoverSnapshot(platform, DAILY_LIMIT) }.getOrNull()
                if (snapshot != null && _uiState.value.platform == platform && discoverByPlatform[platform] == null) {
                    applyDiscover(platform, snapshot, refreshing = true)
                }
            }
        }
        loadDiscover(platform, forceRefresh = false, showSkeleton = false)
    }

    fun search() {
        val keyword = _uiState.value.query.trim()
        if (keyword.isEmpty() || _uiState.value.isSearching) return
        val platform = _uiState.value.platform
        val history = container.searchHistoryRepository.push(keyword)
        val memo = searchByKeyword[keyword]
        if (memo != null) {
            // 搜过的词直接出结果（含两个音源），不再等一次网络
            _uiState.update {
                it.copy(
                    isSearching = false,
                    message = null,
                    openedPlaylist = null,
                    searchHistory = history,
                    searchGroups = reorder(memo, platform),
                    songs = flatten(reorder(memo, platform)),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isSearching = true,
                message = null,
                openedPlaylist = null,
                searchHistory = history,
                searchGroups = emptyList(),
                songs = emptyList(),
            )
        }
        viewModelScope.launch {
            val groups = searchBothPlatforms(keyword)
            searchByKeyword[keyword] = groups
            if (_uiState.value.query.trim() != keyword) return@launch
            val ordered = reorder(groups, _uiState.value.platform)
            val total = ordered.sumOf { it.songs.size }
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchGroups = ordered,
                    songs = flatten(ordered),
                    message = if (total == 0) "两个源都没找到「$keyword」，换个词" else null,
                )
            }
        }
    }

    /** 两个音源并行搜，谁都慢都不拖累另一个；一个为空也照常显示另一个的结果。 */
    private suspend fun searchBothPlatforms(keyword: String): List<SearchGroup> = coroutineScope {
        OnlinePlatform.entries.map { platform ->
            async { SearchGroup(platform, runCatching { repository.search(keyword, platform) }.getOrDefault(emptyList())) }
        }.awaitAll().filter { it.songs.isNotEmpty() }
    }

    /** 选中的音源排前面，另一源跟在后面；两个都没内容时保持空。 */
    private fun reorder(groups: List<SearchGroup>, platform: OnlinePlatform): List<SearchGroup> =
        groups.sortedBy { if (it.platform == platform) 0 else 1 }

    private fun flatten(groups: List<SearchGroup>): List<Song> = groups.flatMap { it.songs }

    fun clearSearchHistory() {
        _uiState.update { it.copy(searchHistory = container.searchHistoryRepository.clear()) }
    }

    fun removeSearchHistory(keyword: String) {
        _uiState.update { it.copy(searchHistory = container.searchHistoryRepository.remove(keyword)) }
    }

    fun searchKeyword(keyword: String) {
        _uiState.update { it.copy(query = keyword) }
        search()
    }

    /**
     * 打开歌单：**先给快照，再让后台刷新**。
     *
     * 大歌单（实测 481 首）一次要几秒，而「刚刚打开过」再点开应该立刻看到列表；
     * 抽屉里的骨架只在真的没有快照时出现。
     */
    fun openPlaylist(playlist: OnlinePlaylist) {
        _uiState.update {
            it.copy(
                openedPlaylist = playlist,
                songs = emptyList(),
                isSearching = true,
                message = null,
            )
        }
        viewModelScope.launch {
            val snapshot = runCatching {
                repository.playlistSongsSnapshot(playlist.id, playlist.platform)
            }.getOrNull()
            if (snapshot != null && _uiState.value.openedPlaylist?.id == playlist.id) {
                _uiState.update { it.copy(songs = snapshot, isSearching = false) }
            }
            val songs = runCatching {
                repository.playlistSongs(playlist.id, playlist.platform)
            }.getOrDefault(emptyList())
            if (_uiState.value.openedPlaylist?.id != playlist.id) return@launch
            _uiState.update {
                it.copy(
                    songs = songs.ifEmpty { snapshot.orEmpty() },
                    isSearching = false,
                    message = when {
                        songs.isEmpty() && snapshot == null -> "歌单暂时取不到歌曲"
                        songs.size == playlist.trackCount -> null
                        playlist.trackCount > 0 && songs.size < playlist.trackCount ->
                            "只拿到 ${songs.size} 首（标 ${playlist.trackCount} 首）"
                        else -> null
                    },
                )
            }
        }
    }

    fun closePlaylist() {
        _uiState.update { it.copy(openedPlaylist = null, songs = emptyList(), message = null) }
    }

    /** 用户手动刷新（推荐歌单那一行的「刷新」）：只有这里会强制打接口。 */
    fun refreshDiscover() {
        loadDiscover(_uiState.value.platform, forceRefresh = true, showSkeleton = false)
    }

    /**
     * 播放：先把点击的那一首解析出来立即出声，剩下的 30 首在后台按 6 路并发补进队列。
     * 之前是「整窗 60 首解析完再播」，4 路并发要跑 15 轮，等十几秒才出声音。
     */
    fun playAt(index: Int) {
        val state = _uiState.value
        if (index !in state.songs.indices || state.isResolving) return
        val song = state.songs[index]
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true, message = null) }
            PlaybackPending.set(song)
            val resolved = try {
                runCatching { repository.resolve(song) }.getOrNull()
            } finally {
                _uiState.update { it.copy(isResolving = false) }
                PlaybackPending.set(null)
            }
            if (resolved == null) {
                _uiState.update {
                    it.copy(message = "放不了：可能要 VIP 或没版权；离线只能放缓存/本地的歌")
                }
                return@launch
            }
            container.playbackManager.playSongs(listOf(resolved), 0)
            prefetchFrom(index)
        }
    }

    /** 首曲已在放，后台把后续曲目补进队列；用户切歌后立刻停止，避免脏队列。 */
    private fun prefetchFrom(index: Int) {
        val anchorKey = _uiState.value.songs.getOrNull(index)?.key ?: return
        viewModelScope.launch {
            val rest = _uiState.value.songs.drop(index + 1).take(PREFETCH_COUNT)
            for (chunk in rest.chunked(PREFETCH_PARALLELISM)) {
                if (container.playbackManager.state.value.currentSong?.key != anchorKey) return@launch
                val resolved = chunk.map { candidate ->
                    async { runCatching { repository.resolve(candidate) }.getOrNull() }
                }.awaitAll().filterNotNull()
                if (resolved.isEmpty()) continue
                if (container.playbackManager.state.value.currentSong?.key != anchorKey) return@launch
                container.playbackManager.appendSongs(resolved)
            }
        }
    }

    fun playAll() {
        if (_uiState.value.songs.isEmpty()) return
        playAt(0)
    }

    fun retry() {
        val state = _uiState.value
        val playlist = state.openedPlaylist
        when {
            playlist != null -> openPlaylist(playlist)
            state.query.isNotBlank() -> search()
            else -> loadDiscover(state.platform, forceRefresh = true)
        }
    }

    /**
     * 发现页取数：**先盘后网**。
     *
     * 1. 内存里这个音源的那一份（切回来的场景，零延迟）；
     * 2. 磁盘快照（冷启动场景，读几十 KB 的 JSON，几十毫秒）；
     * 3. 最后才发请求，而且每个进程只会真拉一次（接口层的「本进程已 fresh」）。
     *
     * 有内容时不显示骨架屏，只在顶部留一条细进度线，避免「明明有内容却闪一下空页」。
     */
    private fun loadDiscover(
        platform: OnlinePlatform,
        forceRefresh: Boolean = false,
        showSkeleton: Boolean = true,
    ) {
        val hasContent = discoverByPlatform[platform]?.isEmpty == false
        _uiState.update {
            it.copy(
                platform = platform,
                isSearching = showSkeleton && !hasContent,
                isRefreshing = hasContent || it.playlists.isNotEmpty(),
                message = null,
            )
        }
        viewModelScope.launch {
            if (!hasContent) {
                val snapshot = runCatching { repository.discoverSnapshot(platform, DAILY_LIMIT) }.getOrNull()
                if (snapshot != null && _uiState.value.platform == platform) {
                    applyDiscover(platform, snapshot, refreshing = true)
                }
            }
            val fresh = runCatching {
                repository.discover(platform, DAILY_LIMIT, forceRefresh)
            }.getOrNull()
            if (fresh == null || fresh.isEmpty) {
                _uiState.update {
                    if (it.platform == platform && it.playlists.isEmpty() && it.dailySongs.isEmpty()) {
                        it.copy(isSearching = false, isRefreshing = false, message = "音源暂时挂了，稍后再试")
                    } else {
                        it.copy(isSearching = false, isRefreshing = false)
                    }
                }
                return@launch
            }
            discoverByPlatform[platform] = fresh
            applyDiscover(platform, fresh, refreshing = false)
        }
    }

    private fun applyDiscover(platform: OnlinePlatform, feed: DiscoverFeed, refreshing: Boolean) {
        _uiState.update {
            if (it.platform != platform) return@update it
            it.copy(
                hotKeywords = feed.hotKeywords,
                playlists = feed.playlists,
                dailySongs = feed.dailySongs,
                reachable = feed.reachable ?: it.reachable,
                isSearching = false,
                isRefreshing = refreshing,
                message = if (feed.isEmpty) "音源暂时挂了，稍后再试" else null,
            )
        }
    }

    private companion object {
        /** 首曲播放后，后台继续补进队列的后续曲目数量。 */
        const val PREFETCH_COUNT = 30

        /** 解析并发：6 路时补队列比 4 路快约三分之一，又还没到互相拖慢的程度。 */
        const val PREFETCH_PARALLELISM = 6

        /** 每日推荐一次要 30 首，够铺 10 行三列封面。 */
        const val DAILY_LIMIT = 30
    }
}
