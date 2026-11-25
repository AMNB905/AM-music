package com.am.music.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.db.PlaylistWithCount
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SongActionsState(
    val isFavorite: Boolean = false,
    val playlists: List<PlaylistWithCount> = emptyList(),
    val memberPlaylistIds: Set<Long> = emptySet(),
)

class SongActionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SongActionsState())
    val state: StateFlow<SongActionsState> = _state.asStateFlow()

    fun open(songKey: String) {
        viewModelScope.launch {
            val isFavorite = container.favoritesRepository.isFavorite(songKey)
            val playlists = container.playlistRepository.observePlaylists().first()
            val memberIds = container.playlistRepository.playlistIdsFor(songKey).toSet()
            _state.value = SongActionsState(isFavorite, playlists, memberIds)
        }
    }

    /** 收藏时连曲目信息一起存（在线曲目因此不会「收藏了但找不到」）。 */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val nowFavorite = container.favoritesRepository.toggle(song)
            _state.update { it.copy(isFavorite = nowFavorite) }
        }
    }

    fun toggleMembership(song: Song, playlistId: Long) {
        viewModelScope.launch {
            val isMember = playlistId in _state.value.memberPlaylistIds
            if (isMember) {
                container.playlistRepository.removeSong(playlistId, song.key)
            } else {
                container.playlistRepository.addSong(playlistId, song)
            }
            _state.update {
                it.copy(
                    memberPlaylistIds = if (isMember) {
                        it.memberPlaylistIds - playlistId
                    } else {
                        it.memberPlaylistIds + playlistId
                    }
                )
            }
        }
    }
}
