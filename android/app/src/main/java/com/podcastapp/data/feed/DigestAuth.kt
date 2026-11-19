package com.podcastapp.data.feed

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** 从 WWW-Authenticate 中解析出的 Digest 挑战；只保留生成响应所需的参数。 */
internal data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: String,
    val qop: String?,
    val opaque: String?,
)

/**
 * HTTP Digest 的纯计算部分。只在服务端返回 401 + WWW-Authenticate: Digest 挑战后
 * 才生成 Authorization；不支持 MD5 / SHA-256 之外的算法或 qop 以外的保护质量，
 * 非 Digest 挑战或缺参数直接拒绝。
 */
internal object DigestAuth {
    private val RANDOM = SecureRandom()
    private val COUNTER = AtomicLong(1)
    private val DIGEST_PREFIX = Regex("""(?i)^Digest\s+(.*)$""")
    private val PARAMETER = Regex("""^([A-Za-z0-9_-]+)\s*=\s*(.*)$""")

    fun parseChallenge(header: String?): DigestChallenge {
        require(!header.isNullOrBlank()) { "缺少 Digest 挑战" }
        val parts = splitHeader(header)
        var start = -1
        var firstParameter = ""
        for (index in parts.indices) {
            val match = DIGEST_PREFIX.find(parts[index].trim())
            if (match != null) {
                start = index
                firstParameter = match.groupValues[1]
                break
            }
        }
        require(start >= 0) { "WWW-Authenticate 不是 Digest 挑战" }
        val params = LinkedHashMap<String, String>()
        collectParameter(firstParameter, params)
        for (index in start + 1 until parts.size) {
            val token = parts[index].trim()
            if (token.isEmpty()) continue
            if (PARAMETER.find(token) == null) break
            collectParameter(token, params)
        }
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        require(realm.isNotEmpty() && nonce.isNotEmpty()) { "Digest 挑战缺少 realm 或 nonce" }
        val algorithmRaw = (params["algorithm"] ?: "MD5").trim()
        val algorithm = algorithmRaw.uppercase(Locale.ROOT)
        require(algorithm == "MD5" || algorithm == "SHA-256") { "不支持的 Digest 算法" }
        val qopRaw = params["qop"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val qop = when {
            qopRaw.isEmpty() -> null
            qopRaw.split(',').any { it.trim() == "auth" } -> "auth"
            else -> throw IllegalArgumentException("不支持的 Digest qop")
        }
        return DigestChallenge(realm, nonce, algorithm, qop, params["opaque"]?.takeIf { it.isNotEmpty() })
    }

    /** 计算 Authorization 值；nc / cnonce 可注入以便做确定性测试。 */
    fun authorization(
        credentials: SourceCredentials,
        challenge: DigestChallenge,
        method: String,
        uri: String,
        nc: String = nextNc(),
        cnonce: String = newCnonce(),
    ): String {
        val digestName = if (challenge.algorithm == "SHA-256") "SHA-256" else "MD5"
        val hash = { value: String ->
            hex(MessageDigest.getInstance(digestName).digest(value.toByteArray(Charsets.UTF_8)))
        }
        val ha1 = hash("${credentials.username}:${challenge.realm}:${credentials.secret}")
        val ha2 = hash("$method:$uri")
        val response = if (challenge.qop == "auth") {
            hash("$ha1:${challenge.nonce}:$nc:$cnonce:auth:$ha2")
        } else {
            hash("$ha1:${challenge.nonce}:$ha2")
        }
        val fields = ArrayList<String>().apply {
            add("username=\"${escape(credentials.username)}\"")
            add("realm=\"${escape(challenge.realm)}\"")
            add("nonce=\"${escape(challenge.nonce)}\"")
            add("uri=\"${escape(uri)}\"")
            if (challenge.qop == "auth") {
                add("qop=auth")
                add("nc=$nc")
                add("cnonce=\"${escape(cnonce)}\"")
            }
            add("response=\"$response\"")
            add("algorithm=${challenge.algorithm}")
            challenge.opaque?.let { add("opaque=\"${escape(it)}\"") }
        }
        return "Digest " + fields.joinToString(", ")
    }

    fun nextNc(): String = java.lang.String.format(Locale.ROOT, "%08x", COUNTER.getAndIncrement() and 0xffffffffL)

    fun newCnonce(): String {
        val bytes = ByteArray(8)
        RANDOM.nextBytes(bytes)
        return hex(bytes)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(Character.forDigit(value shr 4, 16))
            append(Character.forDigit(value and 0x0f, 16))
        }
    }

    /** 按逗号切分但不拆开引号内的值（挑战参数里 realm/nonce 常含逗号）。 */
    private fun splitHeader(header: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (char in header) {
            when {
                escaped -> { current.append(char); escaped = false }
                quoted && char == '\\' -> { current.append(char); escaped = true }
                char == '"' -> { quoted = !quoted; current.append(char) }
                char == ',' && !quoted -> { result.add(current.toString()); current.setLength(0) }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun collectParameter(text: String, into: MutableMap<String, String>) {
        val match = PARAMETER.find(text.trim()) ?: return
        val key = match.groupValues[1].lowercase(Locale.ROOT)
        var value = match.groupValues[2].trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
                .replace("\"", "\"")
                .replace("\\\\", "\\")
        }
        into.putIfAbsent(key, value)
    }
}

/**
 * OkHttp 401 Authenticator：只有发送过初始请求并收到 Digest 挑战后才附加 Authorization。
 * 每次来源独立创建，重试最多一次，避免 challenge 循环导致凭据被反复发送。
 */
internal class DigestAuthenticator(
    private val credentials: SourceCredentials,
) : Authenticator {
    private var attempts = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        if (credentials.secret.isEmpty() || attempts >= 1) return null
        val challenge = response.headers("WWW-Authenticate")
            .firstNotNullOfOrNull { raw -> runCatching { DigestAuth.parseChallenge(raw) }.getOrNull() }
            ?: return null
        if (response.request.header("Authorization") != null) {
            attempts++
            return null
        }
        attempts++
        val url = response.request.url
        val uri = url.encodedPath + if (url.encodedQuery.isNullOrEmpty()) "" else "?" + url.encodedQuery
        val value = DigestAuth.authorization(credentials, challenge, response.request.method, uri)
        return response.request.newBuilder().header("Authorization", value).build()
    }
}
