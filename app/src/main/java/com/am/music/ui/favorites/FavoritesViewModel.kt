package com.am.music.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
)

class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 本地曲库用来「刷新」本地曲目的 uri / 封面（文件可能被移动或重新扫描过）；
            // 在线曲目则直接由库里保存的元数据还原，不再依赖本地曲库。
            val library = container.localMusicRepository.loadSongs().associateBy { it.key }
            container.favoritesRepository.observeSongs(library).collect { songs ->
                _uiState.value = FavoritesUiState(songs = songs, isLoading = false)
            }
        }
    }
}
