package com.podcastapp.data.feed

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.podcastapp.data.storage.SmbCredentials

private const val PREFS_NAME = "podcast_source_credentials"
private const val KEY_KIND = ".kind"
private const val KEY_USERNAME = ".username"
private const val KEY_SECRET = ".secret"
private const val KEY_HEADER = ".header"
private const val KEY_SMB_DOMAIN = ".smbDomain"
private const val KEY_REQUIRE_HTTPS = ".transport.requireHttps"
private const val KEY_CUSTOM_CA = ".transport.customCaPem"
private const val KEY_CLIENT_CERT = ".transport.clientCertPem"
private const val KEY_CLIENT_KEY = ".transport.clientKeyPem"
private const val KEY_CLIENT_KEY_PASSWORD = ".transport.clientKeyPassword"
private const val KEY_DIGEST = ".transport.digest"
private const val DEFAULT_HEADER = "X-API-Key"

/** 存储抽象：真机实现是加密 SharedPreferences，JVM 单测可注入纯内存 fake。 */
internal interface CredentialStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** 仅供 JVM 单测使用的纯内存凭据存储，不落盘。 */
internal class InMemoryCredentialStorage : CredentialStorage {
    private val values = HashMap<String, String>()

    @Synchronized
    override fun getString(key: String): String? = values[key]

    @Synchronized
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }
}

/**
 * 按 feedId 保存 [SourceCredentials] 的加密存储。
 *
 * 真机走 security-crypto 1.1.0 的 MasterKey + EncryptedSharedPreferences，
 * 不会把凭据写进订阅 JSON；单测可用 internal 构造函数注入 [InMemoryCredentialStorage]。
 */
class SourceCredentialStore internal constructor(private val storage: CredentialStorage) {

    constructor(context: Context) : this(AndroidCredentialStorage(context))

    /** 旧调用保持兼容：只保存凭据，传输设置使用默认值。 */
    fun save(feedId: String, credentials: SourceCredentials) = save(feedId, credentials, SourceTransport())

    /** 凭据与传输材料都写进同一个 EncryptedSharedPreferences，不进入订阅 JSON。 */
    fun save(feedId: String, credentials: SourceCredentials, transport: SourceTransport) {
        storage.putString(feedId + KEY_KIND, credentials.kind.name)
        storage.putString(feedId + KEY_USERNAME, credentials.username)
        storage.putString(feedId + KEY_SECRET, credentials.secret)
        storage.putString(feedId + KEY_HEADER, credentials.header)
        storage.putString(feedId + KEY_REQUIRE_HTTPS, transport.requireHttps.toString())
        storage.putString(feedId + KEY_CUSTOM_CA, transport.customCaPem)
        storage.putString(feedId + KEY_CLIENT_CERT, transport.clientCertPem)
        storage.putString(feedId + KEY_CLIENT_KEY, transport.clientKeyPem)
        storage.putString(feedId + KEY_CLIENT_KEY_PASSWORD, transport.clientKeyPassword)
        storage.putString(feedId + KEY_DIGEST, transport.digest.toString())
    }

    fun load(feedId: String): SourceCredentials {
        val kind = runCatching {
            CredentialKind.valueOf(storage.getString(feedId + KEY_KIND).orEmpty())
        }.getOrDefault(CredentialKind.NONE)
        return SourceCredentials(
            kind = kind,
            username = storage.getString(feedId + KEY_USERNAME).orEmpty(),
            secret = storage.getString(feedId + KEY_SECRET).orEmpty(),
            header = storage.getString(feedId + KEY_HEADER) ?: DEFAULT_HEADER,
        )
    }

    /** 旧数据没有传输字段时返回默认值，保证向后兼容。 */
    fun loadTransport(feedId: String): SourceTransport = SourceTransport(
        requireHttps = storage.getString(feedId + KEY_REQUIRE_HTTPS)?.toBooleanStrictOrNull() ?: false,
        customCaPem = storage.getString(feedId + KEY_CUSTOM_CA).orEmpty(),
        clientCertPem = storage.getString(feedId + KEY_CLIENT_CERT).orEmpty(),
        clientKeyPem = storage.getString(feedId + KEY_CLIENT_KEY).orEmpty(),
        clientKeyPassword = storage.getString(feedId + KEY_CLIENT_KEY_PASSWORD).orEmpty(),
        digest = storage.getString(feedId + KEY_DIGEST)?.toBooleanStrictOrNull() ?: false,
    )

    internal fun saveSmb(id: String, credentials: SmbCredentials) {
        storage.putString(id + KEY_USERNAME, credentials.username)
        storage.putString(id + KEY_SECRET, credentials.password)
        storage.putString(id + KEY_SMB_DOMAIN, credentials.domain)
    }

    internal fun loadSmb(id: String) = SmbCredentials(
        storage.getString(id + KEY_USERNAME).orEmpty(),
        storage.getString(id + KEY_SECRET).orEmpty(),
        storage.getString(id + KEY_SMB_DOMAIN).orEmpty(),
    )

    fun remove(feedId: String) {
        storage.remove(feedId + KEY_SMB_DOMAIN)
        storage.remove(feedId + KEY_KIND)
        storage.remove(feedId + KEY_USERNAME)
        storage.remove(feedId + KEY_SECRET)
        storage.remove(feedId + KEY_HEADER)
        storage.remove(feedId + KEY_REQUIRE_HTTPS)
        storage.remove(feedId + KEY_CUSTOM_CA)
        storage.remove(feedId + KEY_CLIENT_CERT)
        storage.remove(feedId + KEY_CLIENT_KEY)
        storage.remove(feedId + KEY_CLIENT_KEY_PASSWORD)
        storage.remove(feedId + KEY_DIGEST)
    }
}

private class AndroidCredentialStorage(context: Context) : CredentialStorage {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
