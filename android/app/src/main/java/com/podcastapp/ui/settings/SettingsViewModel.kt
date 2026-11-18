package com.podcastapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.SettingsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val repo = ServiceLocator.libraryRepository
    private val auth = ServiceLocator.authRepository

    private val _state = MutableStateFlow(SettingsDto())
    val state: StateFlow<SettingsDto> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun load() {
        viewModelScope.launch {
            runCatching { repo.settings() }.onSuccess { _state.value = it }
        }
    }

    fun saveSettings(next: SettingsDto) {
        _state.value = next
        viewModelScope.launch {
            runCatching { repo.saveSettings(next) }
                .onSuccess { _state.value = it }
                .onFailure { _message.value = it.message }
        }
    }

    fun updateProfile(name: String) {
        viewModelScope.launch {
            runCatching { auth.updateProfile(name) }
                .onSuccess { _message.value = "昵称已更新" }
                .onFailure { _message.value = it.message }
        }
    }

    private val _changingPassword = MutableStateFlow(false)
    val changingPassword = _changingPassword.asStateFlow()

    fun changePassword(current: String, next: String, onSuccess: () -> Unit) {
        if (_changingPassword.value) return
        if (current.isEmpty() || next.length < 8) {
            _message.value = "请填写当前密码，新密码至少 8 位"
            return
        }
        _changingPassword.value = true
        viewModelScope.launch {
            try {
                auth.changePassword(current, next)
                onSuccess()
                _message.value = "密码已更新"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "密码更新失败，请重试"
            } finally {
                _changingPassword.value = false
            }
        }
    }

}
