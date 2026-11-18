package com.podcastapp.data

import android.content.Context
import android.net.Network
import com.podcastapp.BuildConfig
import com.podcastapp.data.remote.ApiClient
import com.podcastapp.data.remote.ApiException
import com.podcastapp.data.serversetup.ServerSetup
import com.podcastapp.data.repository.AuthRepository
import com.podcastapp.data.repository.LibraryRepository
import com.podcastapp.data.repository.PodcastRepository
import com.podcastapp.data.repository.StationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 极简依赖容器：服务地址可在运行时修改，客户端随地址重建。
 */
object ServiceLocator {

    val defaultServerUrl: String = runCatching { ServerAddress.optional(BuildConfig.DEFAULT_API_BASE_URL) }
        .getOrNull().orEmpty()
    lateinit var sessionStore: SessionStore
        private set
    lateinit var syncQueue: SyncQueue
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val serverSetup by lazy { ServerSetup(sessionStore, ::activateServerClient) }

    private val _client = MutableStateFlow<ApiClient?>(null)
    val client: StateFlow<ApiClient?> get() = _client.asStateFlow()

    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> get() = _authExpired.asStateFlow()

    private val defaultRoute = MutableStateFlow<Network?>(null)
    private val _online = MutableStateFlow(false)
    /** 当前网络是否可用，UI 可据此显示「离线」提示。 */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    lateinit var authRepository: AuthRepository
        private set
    lateinit var podcastRepository: PodcastRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var stationRepository: StationRepository
        private set

    var deviceId: String = ""
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext
        sessionStore = SessionStore(appContext, defaultServerUrl)
        syncQueue = SyncQueue(appContext)
        networkMonitor = NetworkMonitor(appContext)
        deviceId = runBlocking { sessionStore.deviceId() }
        val session = runBlocking { sessionStore.current() }
        authRepository = AuthRepository(this)
        podcastRepository = PodcastRepository(this)
        libraryRepository = LibraryRepository(this)
        stationRepository = StationRepository(this)
        _client.value = buildClient(session)
        defaultRoute.value = networkMonitor.defaultRoute()
        _online.value = defaultRoute.value != null
        scope.launch {
            networkMonitor.observeRoutes().collect { route ->
                _online.value = route != null
                defaultRoute.value = route
            }
        }
        scope.launch {
            combine(defaultRoute, sessionStore.session, _client) { route, session, client ->
                val owner = session.progressOwner()
                if (route != null && owner != null && client?.baseUrlResolved == session.baseUrl) owner to route else null
            }.distinctUntilChanged().collectLatest { target ->
                if (target != null) try {
                    libraryRepository.flushPendingProgress(target.first)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the queue for the next reconnect or sign-in.
                }
            }
        }
    }

    fun requireClient(): ApiClient = _client.value ?: throw ApiException(0, "请先在设置中选择服务器")

    fun onUnauthorized() {
        _authExpired.value = true
    }

    fun consumeAuthExpired() {
        _authExpired.value = false
    }

    private fun activateServerClient(session: Session, forceNewClient: Boolean) {
        val previous = _client.value
        if (!forceNewClient && previous?.baseUrlResolved.orEmpty() == session.baseUrl) return
        _client.value = buildClient(session)
        previous?.okHttp?.dispatcher?.cancelAll()
        consumeAuthExpired()
    }

    private fun buildClient(session: Session): ApiClient? {
        if (session.baseUrl.isBlank()) return null
        // A superseded client must not read newly imported credentials, even on the same host.
        suspend fun currentForClient() = sessionStore.current().takeIf {
            it.baseUrl == session.baseUrl && it.importedConfigId == session.importedConfigId
        }
        lateinit var client: ApiClient
        client = ApiClient(
            baseUrl = session.baseUrl,
            tokenProvider = { runBlocking { currentForClient()?.accessToken } },
            onUnauthorized = { if (_client.value === client) onUnauthorized() },
            debug = BuildConfig.DEBUG,
            ownedTokenProvider = { owner -> runBlocking { currentForClient()?.takeIf { it.progressOwner() == owner }?.accessToken } },
        )
        client.tokenRefresher = { owner -> authRepository.refreshTokens(client, owner) }
        return client
    }
}
