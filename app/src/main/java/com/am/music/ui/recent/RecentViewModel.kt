package com.am.music.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecentUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
)

class RecentViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentUiState())
    val uiState: StateFlow<RecentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val library = container.localMusicRepository.loadSongs().associateBy { it.key }
            container.recentlyPlayedRepository.observeSongs(library).collect { songs ->
                _uiState.value = RecentUiState(songs = songs, isLoading = false)
            }
        }
    }

    fun clear() {
        viewModelScope.launch { container.recentlyPlayedRepository.clear() }
    }
}
