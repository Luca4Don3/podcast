package com.podcastapp.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时停止播放（Apple Podcasts 的睡眠定时器）。
 * 所有 Android 版本通用，不依赖任何高版本 API。
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    var onExpire: (() -> Unit)? = null

    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        _remainingSeconds.value = minutes * 60L
        job = scope.launch {
            while (_remainingSeconds.value > 0) {
                delay(1_000)
                _remainingSeconds.value -= 1
            }
            onExpire?.invoke()
            _remainingSeconds.value = 0
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingSeconds.value = 0
    }

    fun isActive(): Boolean = job?.isActive == true
}
