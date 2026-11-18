package com.podcastapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.ServerAddress
import com.podcastapp.data.serversetup.ServerSetupLink
import com.podcastapp.data.sameLogin
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.Session
import com.podcastapp.data.remote.ApiException
import com.podcastapp.data.remote.ServerHealthCheck
import com.podcastapp.data.repository.LogoutResult
import com.podcastapp.playback.PlaybackHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ToastEvent(val message: String, val id: Long = System.nanoTime())

/** 全局：会话状态、登录登出、服务器地址切换、错误提示。 */
class AppViewModel : ViewModel() {

    private val locator = ServiceLocator

    val session: StateFlow<Session?> = locator.sessionStore.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<ToastEvent?>(null)
    val toast: StateFlow<ToastEvent?> = _toast.asStateFlow()

    val defaultServerUrl: String get() = locator.defaultServerUrl
    private val _serverFeedback = MutableStateFlow<String?>(null)
    val serverFeedback: StateFlow<String?> = _serverFeedback.asStateFlow()

    init {
        viewModelScope.launch {
            locator.authExpired.collect { expired ->
                if (expired) {
                    locator.consumeAuthExpired()
                    showToast("登录已过期，请重新登录")
                }
            }
        }
    }

    fun showToast(message: String) {
        _toast.value = ToastEvent(message)
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun clearServerFeedback() { _serverFeedback.value = null }

    fun applyServer(value: String, onDone: (Boolean) -> Unit = {}) {
        serverAction(onDone) {
            val custom = ServerAddress.optional(value)
            val before = locator.sessionStore.current()
            val updated = locator.serverSetup.setAddress(custom)
            if (before.baseUrl != updated.baseUrl) PlaybackHolder.stop()
            showToast(when {
                updated.baseUrl.isBlank() -> "已清除自定义地址，当前未配置服务器"
                custom == null -> "已使用默认服务器"
                else -> "已保存自定义服务器地址"
            })
        }
    }

    internal fun importServer(link: ServerSetupLink, onDone: (Boolean) -> Unit = {}) {
        serverAction(onDone) {
            val before = locator.sessionStore.current()
            val updated = locator.serverSetup.signIn(link, locator.deviceId)
            if (!before.sameLogin(updated)) PlaybackHolder.stop()
            showToast("已导入服务器并登录")
        }
    }

    fun testServer(value: String) {
        serverAction {
            val base = ServerAddress.effective(ServerAddress.optional(value), defaultServerUrl)
            require(base.isNotBlank()) { "未配置默认服务器，请先填写测试地址" }
            ServerHealthCheck.check(base)
            _serverFeedback.value = "连接成功：播客服务正常"
        }
    }

    private fun serverAction(onDone: (Boolean) -> Unit = {}, block: suspend () -> Unit) {
        if (_busy.value) {
            onDone(false)
            return
        }
        _busy.value = true
        clearServerFeedback()
        viewModelScope.launch {
            var success = false
            try {
                block()
                success = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _serverFeedback.value = e.message ?: "服务器地址无效"
            } catch (e: IllegalStateException) {
                _serverFeedback.value = e.message ?: "服务器不可用"
            } catch (_: Exception) {
                _serverFeedback.value = "操作失败，请检查网络或稍后重试"
            } finally {
                _busy.value = false
            }
            onDone(success)
        }
    }

    fun login(email: String, password: String, onDone: (Boolean) -> Unit) {
        if (_busy.value || session.value?.baseUrl.isNullOrBlank()) return
        if (email.isBlank() || password.isBlank()) {
            showToast("请输入邮箱和密码")
            onDone(false)
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                locator.authRepository.login(email, password)
                showToast("欢迎回来")
                onDone(true)
            } catch (e: ApiException) {
                showToast(e.message)
                onDone(false)
            } finally {
                _busy.value = false
            }
        }
    }

    fun register(email: String, password: String, displayName: String, inviteCode: String, onDone: (Boolean) -> Unit) {
        if (_busy.value || session.value?.baseUrl.isNullOrBlank()) return
        if (email.isBlank() || password.length < 8) {
            showToast("请填写邮箱，密码至少 8 位")
            onDone(false)
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                locator.authRepository.register(email, password, displayName, inviteCode)
                showToast("注册成功")
                onDone(true)
            } catch (e: ApiException) {
                showToast(e.message)
                onDone(false)
            } finally {
                _busy.value = false
            }
        }
    }

    fun logout() {
        val expected = session.value?.takeIf { it.isLoggedIn } ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = locator.authRepository.logout(expected)
                val current = locator.sessionStore.current()
                if (current.baseUrl == expected.baseUrl && !current.isLoggedIn) {
                    when (result) {
                        LogoutResult.REMOTE_CONFIRMED -> showToast("已退出登录")
                        LogoutResult.LOCAL_ONLY -> showToast("已在本机退出，未能确认服务器撤销")
                        LogoutResult.UNCHANGED -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showToast("未能完成本机退出，请重试")
            } finally {
                _busy.value = false
            }
        }
    }
}
