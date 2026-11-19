package com.podcastapp.data.feed

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * 单个来源的传输安全设置。
 *
 * 这里只描述「策略与材料」，不发起网络请求。所有字段（含自定义 CA、mTLS 私钥）
 * 只进入加密凭据存储，绝不写入订阅 JSON，也不写进 URL。
 * 故意不标记 Serializable：落盘快照里不应出现任何传输材料。
 */
data class SourceTransport(
    val requireHttps: Boolean = false,
    val customCaPem: String = "",
    val clientCertPem: String = "",
    val clientKeyPem: String = "",
    val clientKeyPassword: String = "",
    val digest: Boolean = false,
) {
    companion object {
        val DEFAULT: SourceTransport = SourceTransport()
    }
}

/** 传输材料的纯函数校验与 PEM 解析；失败一律抛出带明确安全语义、不含材料内容的异常。 */
internal object SourceTransportSecurity {
    private const val MAX_PEM_CHARS = 64 * 1024
    private const val KEYSTORE_PASSWORD = "podcastapp-source-transport"
    private val BASE64_BODY = Regex("""^[A-Za-z0-9+/=\r\n]+$""")
    private val SUPPORTED_KEY_ALGORITHMS = listOf("RSA", "EC", "DSA")

    /** requireHttps 时只允许 https；http 与 http 跳转在用请求发出前被拒绝。 */
    fun assertSchemeAllowed(url: String, transport: SourceTransport) {
        if (!transport.requireHttps) return
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        require(scheme == "https") { "requireHttps 已启用，拒绝 http 来源" }
    }

    /**
     * 跨 host、端口变化或 https 降级跳转时，剥离客户端证书、私钥、自定义 CA 与 digest。
     * requireHttps 是本地策略而非凭据，继续保留以阻止后续 http 跳转。
     */
    fun transportForRedirect(
        currentUrl: String,
        redirectUrl: String,
        transport: SourceTransport,
    ): SourceTransport {
        val current = runCatching { URI(currentUrl) }.getOrNull()
        val next = runCatching { URI(redirectUrl) }.getOrNull()
        val currentHost = current?.host
        val nextHost = next?.host
        val sameOrigin = currentHost != null && nextHost != null &&
            currentHost.equals(nextHost, ignoreCase = true) && current?.port == next?.port
        val httpsToHttps = current?.scheme?.equals("https", ignoreCase = true) == true &&
            next?.scheme?.equals("https", ignoreCase = true) == true
        return if (sameOrigin && httpsToHttps) {
            transport
        } else {
            transport.copy(
                customCaPem = "",
                clientCertPem = "",
                clientKeyPem = "",
                clientKeyPassword = "",
                digest = false,
            )
        }
    }

    /** 系统证书加自定义 CA；任何解析失败都不会静默退化成「关闭校验」。 */
    fun trustManager(customCaPem: String): X509TrustManager {
        val system = systemTrustManager()
        if (customCaPem.isBlank()) return system
        val extras = parseCertificates(customCaPem, "自定义 CA 证书")
        val keyStore = newKeyStore()
        system.acceptedIssuers.forEachIndexed { index, cert ->
            runCatching { keyStore.setCertificateEntry("system-$index", cert) }
        }
        extras.forEachIndexed { index, cert -> keyStore.setCertificateEntry("custom-$index", cert) }
        return trustManagers(keyStore)
    }

    /** 用 X509 证书链与 PKCS8 私钥构造 mTLS KeyManager。 */
    fun keyManager(clientCertPem: String, clientKeyPem: String, keyPassword: String): X509KeyManager {
        val chain = parseCertificates(clientCertPem, "客户端证书").toTypedArray()
        if (chain.isEmpty()) throw SecurityException("客户端证书为空")
        val privateKey = parsePrivateKey(clientKeyPem, keyPassword)
        val keyStore = newKeyStore()
        val password = KEYSTORE_PASSWORD.toCharArray()
        runCatching { keyStore.setKeyEntry("client", privateKey, password, chain) }
            .getOrElse { throw SecurityException("mTLS 密钥装载失败") }
        val factory = runCatching {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
        }.getOrElse { throw SecurityException("mTLS 密钥管理器初始化失败") }
        return factory.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
            ?: throw SecurityException("mTLS 密钥管理器不可用")
    }

    fun parseCertificates(pem: String, label: String): List<X509Certificate> {
        requireNotOversize(pem, label)
        val blocks = pemBlocks(pem, "CERTIFICATE")
        if (blocks.isEmpty()) throw SecurityException("$label 不是有效的 PEM 证书")
        val factory = CertificateFactory.getInstance("X.509")
        return blocks.map { der ->
            runCatching {
                factory.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            }.getOrNull() ?: throw SecurityException("$label 解析失败")
        }
    }

    fun parsePrivateKey(pem: String, password: String): PrivateKey {
        requireNotOversize(pem, "客户端私钥")
        val plain = pemBlocks(pem, "PRIVATE KEY")
        val encrypted = pemBlocks(pem, "ENCRYPTED PRIVATE KEY")
        val der = when {
            plain.isNotEmpty() -> plain.first()
            encrypted.isNotEmpty() -> encrypted.first()
            else -> throw SecurityException("客户端私钥不是 PKCS8 PEM")
        }
        val spec = if (encrypted.isNotEmpty()) {
            if (password.isEmpty()) throw SecurityException("加密的客户端私钥缺少密码")
            val info = runCatching { EncryptedPrivateKeyInfo(der) }
                .getOrElse { throw SecurityException("客户端私钥加密参数解析失败") }
            val pbeKey = runCatching {
                SecretKeyFactory.getInstance(info.algName)
                    .generateSecret(PBEKeySpec(password.toCharArray()))
            }.getOrElse { throw SecurityException("客户端私钥解密失败") }
            runCatching { info.getKeySpec(pbeKey) }
                .getOrElse { throw SecurityException("客户端私钥解密失败") }
        } else {
            PKCS8EncodedKeySpec(der)
        }
        for (algorithm in SUPPORTED_KEY_ALGORITHMS) {
            val key = runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
            if (key != null) return key
        }
        throw SecurityException("客户端私钥算法不受支持")
    }

    private fun requireNotOversize(pem: String, label: String) {
        if (pem.length > MAX_PEM_CHARS) throw SecurityException("$label 超过大小限制")
    }

    /** 提取 PEM 块并做严格 Base64 校验；忽略块外文本，不回显任何材料内容。 */
    private fun pemBlocks(pem: String, label: String): List<ByteArray> {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val blocks = ArrayList<ByteArray>()
        var from = 0
        while (true) {
            val start = pem.indexOf(begin, from)
            if (start < 0) break
            val bodyStart = start + begin.length
            val stop = pem.indexOf(end, bodyStart)
            if (stop < 0) break
            val body = pem.substring(bodyStart, stop).trim()
            if (body.isEmpty() || !BASE64_BODY.matches(body)) {
                throw SecurityException("$label PEM 内容无效")
            }
            val der = runCatching { Base64.getDecoder().decode(body.replace("\\s".toRegex(), "")) }
                .getOrElse { throw SecurityException("$label PEM Base64 解码失败") }
            if (der.isEmpty()) throw SecurityException("$label PEM 内容为空")
            blocks.add(der)
            from = stop + end.length
        }
        return blocks
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw SecurityException("系统证书校验不可用")
    }

    private fun trustManagers(keyStore: KeyStore): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        runCatching { factory.init(keyStore) }
            .getOrElse { throw SecurityException("自定义 CA 信任管理器初始化失败") }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw SecurityException("自定义 CA 信任管理器不可用")
    }

    /** 避免依赖平台默认 KeyStore 类型（Android 上可能是 AndroidKeyStore）。 */
    private fun newKeyStore(): KeyStore {
        for (type in listOf("PKCS12", KeyStore.getDefaultType())) {
            val store = runCatching { KeyStore.getInstance(type) }.getOrNull() ?: continue
            val loaded = runCatching { store.load(null, null) }.isSuccess
            if (loaded) return store
        }
        throw SecurityException("无法创建内存密钥库")
    }
}
