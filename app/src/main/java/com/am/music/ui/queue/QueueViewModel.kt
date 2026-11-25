package com.am.music.ui.queue

import androidx.lifecycle.ViewModel
import com.am.music.AppContainer
import com.am.music.playback.PlaybackState
import kotlinx.coroutines.flow.StateFlow

class QueueViewModel(private val container: AppContainer) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = container.playbackManager.state

    fun playAt(index: Int) = container.playbackManager.playAt(index)
    fun removeAt(index: Int) = container.playbackManager.removeFromQueue(index)
    fun clear() = container.playbackManager.clearQueue()
}