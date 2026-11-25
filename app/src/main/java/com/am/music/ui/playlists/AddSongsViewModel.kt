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

data class AddSongsUiState(
    val songs: List<Song> = emptyList(),
    val addedIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)

class AddSongsViewModel(
    private val container: AppContainer,
    private val playlistId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddSongsUiState())
    val uiState: StateFlow<AddSongsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val library = container.localMusicRepository.loadSongs()
            _uiState.update { it.copy(songs = library, isLoading = false) }
            container.playlistRepository.observeSongIds(playlistId).collect { keys ->
                _uiState.update { it.copy(addedIds = keys.toSet()) }
            }
        }
    }

    fun toggle(song: Song) {
        viewModelScope.launch {
            if (song.key in _uiState.value.addedIds) {
                container.playlistRepository.removeSong(playlistId, song.key)
            } else {
                container.playlistRepository.addSong(playlistId, song)
            }
        }
    }
}
