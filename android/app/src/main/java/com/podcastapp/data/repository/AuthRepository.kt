package com.podcastapp.data.repository

import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.Session
import com.podcastapp.data.SessionStore
import com.podcastapp.data.progressOwner
import com.podcastapp.data.remote.ApiClient
import com.podcastapp.data.remote.ApiException
import com.podcastapp.data.remote.AuthResponse
import com.podcastapp.data.remote.ChangePasswordRequest
import com.podcastapp.data.remote.LoginRequest
import com.podcastapp.data.remote.RefreshAttempt
import com.podcastapp.data.remote.RefreshRequest
import com.podcastapp.data.remote.RegisterRequest
import com.podcastapp.data.remote.UpdateProfileRequest
import com.podcastapp.data.remote.UserDto
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class LogoutResult { UNCHANGED, LOCAL_ONLY, REMOTE_CONFIRMED }

class AuthRepository internal constructor(
    private val sessionStore: SessionStore,
    private val requireClient: () -> ApiClient,
    private val deviceId: () -> String,
) {
    constructor(locator: ServiceLocator) : this(locator.sessionStore, locator::requireClient, { locator.deviceId })

    private val refreshMutex = Mutex()

    suspend fun register(email: String, password: String, displayName: String, inviteCode: String): UserDto =
        apiCall {
            val client = requireClient()
            val res = client.api.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password,
                    displayName = displayName.trim(),
                    inviteCode = inviteCode.trim(),
                    deviceId = deviceId(),
                ),
            )
            persist(res, client.baseUrlResolved)
            res.user
        }

    suspend fun login(email: String, password: String): UserDto = apiCall {
        val client = requireClient()
        val res = client.api.login(
            LoginRequest(email = email.trim(), password = password, deviceId = deviceId()),
        )
        persist(res, client.baseUrlResolved)
        res.user
    }

    suspend fun logout(expected: Session): LogoutResult {
        if (!expected.isLoggedIn) return LogoutResult.UNCHANGED
        val lockOwner = Any()
        val (session, canRevoke) = try {
            // Let a nearly completed refresh settle, but do not block local sign-out on the network.
            val settled = withTimeoutOrNull(1_000L) { refreshMutex.lock(lockOwner); true } == true
            sessionStore.takeAuth(expected) to settled
        } finally {
            if (refreshMutex.holdsLock(lockOwner)) refreshMutex.unlock(lockOwner)
        }
        if (session == null) return LogoutResult.UNCHANGED
        val access = session.accessToken
        val refresh = session.refreshToken
        // Never send an empty refresh token: the server treats that as logout on all devices.
        if (!canRevoke || access.isNullOrBlank() || refresh.isNullOrBlank()) return LogoutResult.LOCAL_ONLY
        val confirmed = try {
            withTimeoutOrNull(2_000L) {
                val client = requireClient()
                if (client.baseUrlResolved != session.baseUrl) return@withTimeoutOrNull false
                client.revokeSession(access, refresh)
                true
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (confirmed) LogoutResult.REMOTE_CONFIRMED else LogoutResult.LOCAL_ONLY
    }

    /** Refresh only on the server that issued the session. */
    suspend fun refreshTokens(client: ApiClient, owner: ProgressOwner?): Boolean = refreshMutex.withLock {
        val session = sessionStore.current()
        val attempt = coroutineContext[RefreshAttempt]
        if (!session.isLoggedIn || session.baseUrl != client.baseUrlResolved ||
            (owner != null && session.progressOwner() != owner) ||
            (attempt != null && session.accessToken != attempt.failedAccessToken)) return false
        val refresh = session.refreshToken?.takeIf { it.isNotBlank() } ?: return false
        val token = try {
            apiCall { client.api.refresh(RefreshRequest(refresh, deviceId()), owner) }.token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiException && e.isUnauthorized && sessionStore.clearAuth(session)) client.notifyAuthExpired()
            return false
        }
        val saved = sessionStore.saveTokens(session, token.accessToken, token.refreshToken)
        if (saved) attempt?.newAccessToken = token.accessToken
        saved
    }

    suspend fun me(): UserDto = apiCall {
        val client = requireClient()
        val res = client.api.me()
        sessionStore.updateProfile(client.baseUrlResolved, res.user.displayName, res.user.email)
        res.user
    }

    suspend fun updateProfile(displayName: String): UserDto = apiCall {
        val client = requireClient()
        val res = client.api.updateProfile(UpdateProfileRequest(displayName))
        sessionStore.updateProfile(client.baseUrlResolved, res.user.displayName, res.user.email)
        res.user
    }

    suspend fun changePassword(current: String, newPassword: String) = apiCall {
        requireClient().api.changePassword(ChangePasswordRequest(current, newPassword))
    }

    private suspend fun persist(res: AuthResponse, server: String) {
        val saved = sessionStore.saveAuth(
            accessToken = res.token.accessToken,
            refreshToken = res.token.refreshToken,
            userId = res.user.id,
            email = res.user.email,
            displayName = res.user.displayName,
            role = res.user.role,
            expectedBaseUrl = server,
        )
        if (!saved) throw ApiException(0, "服务器已变更，请重新登录")
    }
}
