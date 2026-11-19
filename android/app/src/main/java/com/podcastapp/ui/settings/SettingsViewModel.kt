package com.podcastapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.remote.SettingsDto
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 纯本地偏好：偏好持久化在 LocalPodcastLibrary，读写都不经过自建服务器。 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val library = LocalPodcastLibrary.get(app.applicationContext)

    val state: StateFlow<SettingsDto> = library.state
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsDto())

    /** 本地设置直接来自单例状态，无需网络加载。 */
    fun load() = Unit

    fun saveSettings(next: SettingsDto) {
        library.saveSettings(next)
    }
}
