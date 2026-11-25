package com.am.music.ui.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.RankingBoard
import com.am.music.data.model.RankingBoards
import com.am.music.data.model.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 排行榜：平台（QQ / 网易云）→ 榜单。
 *
 * 取数口径在仓库层：每个进程每个榜单只真正拉一次，之后一律读磁盘快照；
 * 只有用户点「刷新」（[refresh]）才强制重拉。
 */
class RankingViewModel(private val container: AppContainer) : ViewModel() {

    private val _platform = MutableStateFlow(OnlinePlatform.QQ)
    val platform: StateFlow<OnlinePlatform> = _platform.asStateFlow()

    private val _boards = MutableStateFlow(RankingBoards.QQ)
    val boards: StateFlow<List<RankingBoard>> = _boards.asStateFlow()

    private val _selected = MutableStateFlow(RankingBoards.QQ.first())
    val selected: StateFlow<RankingBoard> = _selected.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 本次进程里的榜单结果：切榜来回不重复请求。 */
    private val cache = ConcurrentHashMap<String, List<Song>>()

    init {
        open(RankingBoards.QQ.first())
    }

    fun switchPlatform(platform: OnlinePlatform) {
        if (platform == _platform.value) return
        val boards = RankingBoards.of(platform)
        _platform.value = platform
        _boards.value = boards
        open(boards.first())
    }

    fun open(board: RankingBoard, forceRefresh: Boolean = false) {
        _selected.value = board
        if (!forceRefresh) {
            val cached = cache[board.key]
            if (!cached.isNullOrEmpty()) {
                _songs.value = cached
                _loading.value = false
                return
            }
        }
        _loading.value = true
        viewModelScope.launch {
            // 先读磁盘快照：冷启动也能先把上一份榜单画出来，不必对着空列表等接口
            if (!forceRefresh) {
                val snapshot = runCatching {
                    container.onlineMusicRepository.toplistSnapshot(
                        boardId = board.id,
                        platform = board.platform,
                    )
                }.getOrNull()
                if (_selected.value.key != board.key) return@launch
                if (!snapshot.isNullOrEmpty()) {
                    cache[board.key] = snapshot
                    _songs.value = snapshot
                    _loading.value = false
                } else {
                    _songs.value = emptyList()
                }
            } else {
                _songs.value = emptyList()
            }
            val songs = runCatching {
                container.onlineMusicRepository.toplistSongs(
                    boardId = board.id,
                    platform = board.platform,
                    forceRefresh = forceRefresh,
                )
            }.getOrDefault(emptyList())
            // 用户可能已经切到别的榜，只认当前选中
            if (_selected.value.key != board.key) return@launch
            if (songs.isNotEmpty()) cache[board.key] = songs
            // 拉空了也不能把快照上的内容抹掉，否则「有一份旧的」反而比「没有」更糟
            if (songs.isNotEmpty() || _songs.value.isEmpty()) _songs.value = songs
            _loading.value = false
        }
    }

    /** 用户手动刷新当前榜：绕过缓存重拉。 */
    fun refresh() {
        open(_selected.value, forceRefresh = true)
    }

    /** 榜单拉空（上游限流/失效）时手动重试。 */
    fun retry() {
        cache.remove(_selected.value.key)
        open(_selected.value, forceRefresh = true)
    }
}
