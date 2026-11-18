package com.podcastapp.data.serversetup

import com.podcastapp.data.Session
import com.podcastapp.data.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single workflow for both entry points. SessionStore owns the durable transaction. */
internal class ServerSetup(
    private val sessions: SessionStore,
    private val activateClient: (session: Session, forceNewClient: Boolean) -> Unit,
    private val login: ServerSetupLogin = ServerSetupLogin(),
) {
    private val changes = Mutex()

    suspend fun setAddress(custom: String?): Session = changes.withLock {
        val session = sessions.setCustomBaseUrl(custom)
        activateClient(session, false)
        session
    }

    suspend fun signIn(link: ServerSetupLink, deviceId: String): Session {
        val expected = sessions.current()
        val verified = login.verify(link, deviceId)
        return changes.withLock {
            val session = checkNotNull(sessions.saveImportedAuth(expected, link.baseUrl, verified)) {
                "验证期间配置或账号已变化，请重新导入"
            }
            activateClient(session, true)
            session
        }
    }
}
