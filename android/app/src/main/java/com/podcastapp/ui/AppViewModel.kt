package com.podcastapp.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToastEvent(val message: String, val id: Long = System.nanoTime())

/** 轻量 UI 提示容器：只承载一次性 toast，不持有会话、服务器或账号状态。 */
class AppViewModel : ViewModel() {

    private val _toast = MutableStateFlow<ToastEvent?>(null)
    val toast: StateFlow<ToastEvent?> = _toast.asStateFlow()

    fun showToast(message: String) {
        _toast.value = ToastEvent(message)
    }

    fun consumeToast() {
        _toast.value = null
    }
}
