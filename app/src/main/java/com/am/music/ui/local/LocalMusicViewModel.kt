package com.am.music.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    /** 用户主动下载的曲目，永远排在本机音乐前面。 */
    val downloads: List<Song> = emptyList(),
    val downloadBytes: Long = 0L,
)

class LocalMusicViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalUiState())
    val uiState: StateFlow<LocalUiState> = _uiState.asStateFlow()

    init {
        // 下载记录是活数据：下完一首、删掉一档，本地页立刻跟着变，不用手动刷新
        viewModelScope.launch {
            container.downloadsRepository.observeAll().collect { rows ->
                _uiState.update {
                    it.copy(
                        downloads = rows.map(container.downloadsRepository::songOf),
                        downloadBytes = rows.sumOf { row -> row.bytes },
                    )
                }
            }
        }
    }

    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val songs = container.localMusicRepository.loadSongs()
            _uiState.update { it.copy(songs = songs, isLoading = false) }
        }
    }
}