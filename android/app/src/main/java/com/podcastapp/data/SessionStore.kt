package com.podcastapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podcastapp.data.remote.AuthResponse
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "podcast_session")

/** 本地会话：服务器地址、令牌、当前用户。 */
data class Session(
    val baseUrl: String,
    val accessToken: String?,
    val refreshToken: String?,
    val userId: String?,
    val email: String?,
    val displayName: String?,
    val role: String?,
    val customBaseUrl: String? = null,
    internal val loginId: String? = null,
    internal val importedConfigId: String? = null,
) {
    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank() && !userId.isNullOrBlank()
}

// Older stored logins have no ID; any subsequent login always receives a new one.
internal fun Session.sameLogin(other: Session): Boolean =
    isLoggedIn && other.isLoggedIn && baseUrl == other.baseUrl && userId == other.userId && loginId == other.loginId

class SessionStore internal constructor(
    private val store: DataStore<Preferences>,
    private val defaultBaseUrl: String,
) {
    constructor(context: Context, defaultBaseUrl: String) : this(context.dataStore, defaultBaseUrl)

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val AUTH_BASE_URL = stringPreferencesKey("auth_base_url")
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val LOGIN_ID = stringPreferencesKey("login_id")
        val IMPORTED_CONFIG_ID = stringPreferencesKey("imported_config_id")
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ROLE = stringPreferencesKey("role")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val session: Flow<Session> = store.data.map { it.toSession() }

    suspend fun current(): Session = store.data.first().toSession()

    /** Change the endpoint and clear old credentials in one transaction. */
    suspend fun setCustomBaseUrl(custom: String?): Session = store.edit { prefs ->
        val before = prefs.toSession()
        if (before.baseUrl != ServerAddress.effective(custom, defaultBaseUrl)) {
            prefs.removeAuth()
        } else if (before.isLoggedIn) {
            prefs[Keys.AUTH_BASE_URL] = before.baseUrl
        }
        if (custom == null) prefs.remove(Keys.BASE_URL) else prefs[Keys.BASE_URL] = custom
    }.toSession()

    suspend fun saveAuth(
        accessToken: String,
        refreshToken: String,
        userId: String,
        email: String,
        displayName: String,
        role: String,
        expectedBaseUrl: String,
    ): Boolean {
        var saved = false
        store.edit {
            if (it.effectiveBaseUrl() != expectedBaseUrl) return@edit
            it[Keys.AUTH_BASE_URL] = expectedBaseUrl
            it[Keys.LOGIN_ID] = UUID.randomUUID().toString()
            saved = true
            it[Keys.ACCESS] = accessToken
            it[Keys.REFRESH] = refreshToken
            it[Keys.USER_ID] = userId
            it[Keys.EMAIL] = email
            it[Keys.DISPLAY_NAME] = displayName
            it[Keys.ROLE] = role
        }
        return saved
    }

    /** Adopt a verified target and its tokens together, never exposing a half-applied server switch. */
    internal suspend fun saveImportedAuth(expected: Session, baseUrl: String, response: AuthResponse): Session? {
        var saved = false
        return store.edit { prefs ->
            val current = prefs.toSession()
            if (current.baseUrl != expected.baseUrl || current.customBaseUrl != expected.customBaseUrl ||
                current.importedConfigId != expected.importedConfigId || current.isLoggedIn != expected.isLoggedIn ||
                (current.isLoggedIn && !current.sameLogin(expected))) return@edit
            prefs[Keys.BASE_URL] = baseUrl
            prefs[Keys.AUTH_BASE_URL] = baseUrl
            val newId = UUID.randomUUID().toString()
            prefs[Keys.LOGIN_ID] = newId
            prefs[Keys.IMPORTED_CONFIG_ID] = newId
            prefs[Keys.ACCESS] = response.token.accessToken
            prefs[Keys.REFRESH] = response.token.refreshToken
            prefs[Keys.USER_ID] = response.user.id
            prefs[Keys.EMAIL] = response.user.email
            prefs[Keys.DISPLAY_NAME] = response.user.displayName
            prefs[Keys.ROLE] = response.user.role
            saved = true
        }.takeIf { saved }?.toSession()
    }

    suspend fun saveTokens(expected: Session, accessToken: String, refreshToken: String): Boolean {
        var saved = false
        store.edit {
            if (!it.matchesAuth(expected)) return@edit
            it[Keys.AUTH_BASE_URL] = expected.baseUrl
            it[Keys.ACCESS] = accessToken
            it[Keys.REFRESH] = refreshToken
            saved = true
        }
        return saved
    }

    suspend fun updateProfile(expectedBaseUrl: String, displayName: String, email: String) {
        store.edit {
            if (it.effectiveBaseUrl() != expectedBaseUrl || !it.toSession().isLoggedIn) return@edit
            it[Keys.DISPLAY_NAME] = displayName
            it[Keys.EMAIL] = email
        }
    }

    suspend fun clearAuth(expected: Session): Boolean {
        var cleared = false
        store.edit {
            if (!it.matchesAuth(expected)) return@edit
            it.removeAuth()
            cleared = true
        }
        return cleared
    }

    /** Clear the selected login and return its latest token pair in the same transaction. */
    internal suspend fun takeAuth(expected: Session): Session? {
        var taken: Session? = null
        store.edit {
            val current = it.toSession()
            if (!current.sameLogin(expected)) return@edit
            taken = current
            it.removeAuth()
        }
        return taken
    }

    private fun Preferences.matchesAuth(expected: Session): Boolean {
        val current = toSession()
        return current.sameLogin(expected) && current.accessToken == expected.accessToken &&
            current.refreshToken == expected.refreshToken
    }

    private fun MutablePreferences.removeAuth() {
        remove(Keys.AUTH_BASE_URL)
        remove(Keys.ACCESS)
        remove(Keys.REFRESH)
        remove(Keys.LOGIN_ID)
        remove(Keys.USER_ID)
        remove(Keys.EMAIL)
        remove(Keys.DISPLAY_NAME)
        remove(Keys.ROLE)
    }

    suspend fun deviceId(): String {
        val existing = store.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        store.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    private fun Preferences.customBaseUrl(): String? =
        this[Keys.BASE_URL]?.let { runCatching { ServerAddress.optional(it) }.getOrNull() }

    private fun Preferences.effectiveBaseUrl(): String =
        ServerAddress.effective(customBaseUrl(), defaultBaseUrl)

    private fun Preferences.toSession(): Session {
        val custom = customBaseUrl()
        val base = ServerAddress.effective(custom, defaultBaseUrl)
        // Legacy auth can only be attributed to an explicitly stored address.
        val authMatches = base.isNotBlank() && base == (this[Keys.AUTH_BASE_URL] ?: custom)
        return Session(
            baseUrl = base,
            accessToken = this[Keys.ACCESS].takeIf { authMatches },
            refreshToken = this[Keys.REFRESH].takeIf { authMatches },
            userId = this[Keys.USER_ID].takeIf { authMatches },
            email = this[Keys.EMAIL].takeIf { authMatches },
            displayName = this[Keys.DISPLAY_NAME].takeIf { authMatches },
            role = this[Keys.ROLE].takeIf { authMatches },
            customBaseUrl = custom,
            loginId = this[Keys.LOGIN_ID].takeIf { authMatches },
            importedConfigId = this[Keys.IMPORTED_CONFIG_ID],
        )
    }
}
