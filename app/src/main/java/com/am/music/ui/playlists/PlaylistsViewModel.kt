package com.am.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.db.PlaylistWithCount
import com.am.music.data.online.LinkResolver
import com.am.music.data.online.PlaylistLinkParser
import com.am.music.data.online.PlaylistLinkResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<PlaylistWithCount> = emptyList(),
    val favoriteCount: Int = 0,
    val recentCount: Int = 0,
)

/**
 * 导入进度。
 *
 * 三步都要有话说：认链接 → 取歌单 → 写库。用户一贯讨厌「点了没反应」，
 * 742 首的歌单取一次要几秒，只给一个转圈是不够的。
 */
data class ImportState(
    val running: Boolean = false,
    val step: String = "",
    val error: String? = null,
    val done: String? = null,
) {
    val active: Boolean get() = running || error != null || done != null
}

class PlaylistsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                container.playlistRepository.observePlaylists(),
                container.favoritesRepository.favoriteIds,
                container.recentlyPlayedRepository.count,
            ) { playlists, favoriteIds, recentCount ->
                PlaylistsUiState(
                    playlists = playlists,
                    favoriteCount = favoriteIds.size,
                    recentCount = recentCount,
                )
            }.collect { _uiState.value = it }
        }
    }

    fun create(name: String) {
        viewModelScope.launch { container.playlistRepository.create(name) }
    }

    fun rename(playlistId: Long, name: String) {
        viewModelScope.launch { container.playlistRepository.rename(playlistId, name) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { container.playlistRepository.delete(playlistId) }
    }

    /**
     * 从一段分享文案导入歌单。
     *
     * 整段文案直接进来也行（分享文案里混着一堆表情和推广），[PlaylistLinkParser] 只挑第一条链接。
     * 二维码识别出来的也是这里进——QQ 卡片二维码里是短链，先展开再解析。
     */
    fun importFromLink(raw: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            when (val parsed = PlaylistLinkParser.parse(raw)) {
                is PlaylistLinkResult.Failed ->
                    _importState.value = ImportState(error = parsed.message)

                is PlaylistLinkResult.Ok -> loadAndSave(parsed.platform, parsed.id)

                is PlaylistLinkResult.ShortLink -> {
                    _importState.value = ImportState(running = true, step = "链接是短链，正在展开…")
                    val finalUrl = runCatching { LinkResolver.resolve(parsed.url) }.getOrNull()
                    if (finalUrl == null) {
                        _importState.value = ImportState(
                            error = "短链没能展开（网络不通，或者链接已失效）。把歌单打开后重新「复制链接」再试。",
                        )
                        return@launch
                    }
                    when (val again = PlaylistLinkParser.parse(finalUrl)) {
                        is PlaylistLinkResult.Ok -> loadAndSave(again.platform, again.id)
                        else -> _importState.value = ImportState(
                            error = "短链展开后认不出歌单。请在歌单页点「分享 → 复制链接」，用那条完整链接。",
                        )
                    }
                }
            }
        }
    }

    /** 取歌单 → 写本地。三个可能失败的地方各自有话说。 */
    private suspend fun loadAndSave(platform: com.am.music.data.model.OnlinePlatform, playlistId: String) {
        _importState.value = ImportState(running = true, step = "正在读取${platform.label}歌单…")

        val bundle = runCatching {
            container.onlineMusicRepository.playlistBundle(playlistId, platform)
        }.getOrNull()

        if (bundle == null) {
            _importState.value = ImportState(
                error = "没连上服务器。检查网络后再试一次（这条链接本身没问题）。",
            )
            return
        }
        if (bundle.songs.isEmpty()) {
            _importState.value = ImportState(
                error = "这个歌单取不到曲目：可能设了私密、全部下架，或者这会儿被限流。过几分钟再试。",
            )
            return
        }

        val name = uniqueName(bundle.name ?: "导入的歌单")
        _importState.value = ImportState(
            running = true,
            step = "收到「$name」${bundle.songs.size} 首，正在写入本地…",
        )

        val written = runCatching {
            container.playlistRepository.createWithSongs(name, bundle.songs)
        }

        _importState.value = if (written.isSuccess) {
            // 上游偶发只给一部分（QQ 详情接口是固定页），数量对不上时必须在结果里说清楚，
            // 否则用户会以为「导入少了」是我们的问题
            val short = (bundle.trackCount - bundle.songs.size).takeIf { it > 0 }
            ImportState(
                done = buildString {
                    append("已导入「").append(name).append("」")
                        .append(bundle.songs.size).append(" 首")
                    if (short != null) {
                        append("\n歌单标称 ").append(bundle.trackCount)
                            .append(" 首，上游只返回了 ").append(bundle.songs.size)
                            .append(" 首（多出来的那 ")
                            .append(short).append(" 首接口没给）")
                    }
                },
            )
        } else {
            ImportState(error = "写入本地失败（存储空间不足？）。已回滚，没有留下半个歌单。")
        }
    }

    /** 取数阶段可以取消；写库是单个事务，走到那一步就让它写完。 */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState()
    }

    fun dismissImport() {
        _importState.value = ImportState()
    }

    /** 同名歌单会在列表里分不清，追加「 (2)」「 (3)」。 */
    private fun uniqueName(base: String): String {
        val existing = _uiState.value.playlists.map { it.name }.toSet()
        if (base !in existing) return base
        var index = 2
        while ("$base ($index)" in existing) index++
        return "$base ($index)"
    }
}
