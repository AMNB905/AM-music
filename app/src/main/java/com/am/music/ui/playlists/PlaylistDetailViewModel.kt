package com.am.music.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val name: String = "",
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
)

class PlaylistDetailViewModel(
    private val container: AppContainer,
    private val playlistId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val library = container.localMusicRepository.loadSongs().associateBy { it.key }
            launch {
                container.playlistRepository.observePlaylist(playlistId).collect { entity ->
                    _uiState.update { it.copy(name = entity?.name ?: "") }
                }
            }
            container.playlistRepository.observeSongs(playlistId, library).collect { songs ->
                // 歌单里同一首只能一行：重复 key 会让列表与拖动重排都崩（key 必须唯一）
                _uiState.update { it.copy(songs = songs.distinctBy { it.key }, isLoading = false) }
            }
        }
    }

    fun removeSong(songId: String) {
        viewModelScope.launch { container.playlistRepository.removeSong(playlistId, songId) }
    }

    fun moveSong(from: Int, to: Int) {
        _uiState.update { state ->
            val songs = state.songs
            if (from !in songs.indices || to !in 0..songs.size) return@update state
            val reordered = songs.toMutableList().apply {
                val moved = removeAt(from)
                add(to.coerceIn(0, size), moved)
            }
            state.copy(songs = reordered)
        }
    }

    fun commitOrder() {
        val songIds = _uiState.value.songs.map { it.key }
        viewModelScope.launch { container.playlistRepository.reorder(playlistId, songIds) }
    }
}
