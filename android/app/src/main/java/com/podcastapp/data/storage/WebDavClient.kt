package com.podcastapp.data.storage

import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.DigestAuthenticator
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import com.podcastapp.data.feed.SourceTransportSecurity
import com.podcastapp.data.feed.credentialHeaders
import com.podcastapp.data.feed.digestEnabled
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

private const val MAX_ENTRIES = 500
private const val MAX_XML_CHARS = 4 * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val USER_AGENT = "PodcastApp-WebDav/1.5"

private val CREDENTIAL_QUERY_KEYS = setOf("token", "api_key", "key")

private val PROPFIND_BODY = """
    <?xml version="1.0" encoding="utf-8"?>
    <propfind xmlns="DAV:"><prop>
      <resourcetype/><getcontentlength/><getlastmodified/><getcontenttype/><displayname/>
    </prop></propfind>
""".trimIndent()

/** PROPFIND 解析后的单个 WebDAV 条目；服务端未提供的属性为 null。 */
data class WebDavEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long? = null,
    val modifiedAt: Long? = null,
    val contentType: String? = null,
)

/**
 * 只读 WebDAV 客户端：PROPFIND 列目录、HEAD 取长度、GET 探测 Range。
 *
 * 不使用 Android 框架，凭据只作为请求头注入，绝不拼进 URL；传输材料与凭据在
 * 跨 host / 端口 / https 降级跳转时逐跳剥离。复用 data.feed 中的凭据/传输工具，
 * 不修改它们，也不发起任何非用户指定的网络请求。
 */
class WebDavClient(
    baseUrl: String,
    private val credentials: SourceCredentials,
    private val transport: SourceTransport,
) {
    private val base: HttpUrl = validateBaseUrl(baseUrl, transport)

    /** 把（相对或绝对）路径拼到 base 的同一来源 URL；凭据不会出现在结果里。 */
    fun resolveFileUrl(path: String): String {
        require(!path.any { it.isISOControl() || it.isWhitespace() }) { "path 含非法字符" }
        val resolved = base.resolve(path) ?: throw IllegalArgumentException("path 无法解析为 URL")
        require(
            resolved.scheme.equals(base.scheme, ignoreCase = true) &&
                resolved.host.equals(base.host, ignoreCase = true) &&
                resolved.port == base.port,
        ) { "拒绝跨来源路径" }
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) { "URL 不得包含 userinfo" }
        return resolved.toString()
    }

    /** PROPFIND Depth:1，返回目录下的条目（最多 500 个），目录自身不计入。 */
    fun list(path: String = ""): Result<List<WebDavEntry>> = runCatching {
        val target = resolveFileUrl(path)
        val requestedPath = decodeWebDavValue(target.toHttpUrl().encodedPath)
        val body = PROPFIND_BODY.toByteArray(Charsets.UTF_8)
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val response = execute("PROPFIND", target, mapOf("Depth" to "1"), body, readBody = true)
        if (response.code != 207 && response.code != 200) {
            throw IOException("PROPFIND 失败: HTTP ${response.code}")
        }
        parsePropFindXml(response.body, requestedPath)
    }

    /**
     * 探测 Range 支持：先 HEAD 看长度与 Accept-Ranges，再用 GET Range: bytes=0-0 验证。
     * true 表示服务端返回 206；false 表示明确不支持或空文件；其它情况返回失败。
     */
    fun probeRange(path: String): Result<Boolean> = runCatching {
        val target = resolveFileUrl(path)
        val head = execute("HEAD", target, readBody = false)
        if (head.code == 404) throw IOException("文件不存在: HTTP 404")
        require(head.code in 200..299) { "HEAD 失败: HTTP ${head.code}" }
        if (head.headers["Accept-Ranges"]?.trim().equals("none", ignoreCase = true)) {
            return@runCatching false
        }
        if (head.headers["Content-Length"]?.trim()?.toLongOrNull() == 0L) {
            return@runCatching false
        }
        val ranged = execute("GET", target, mapOf("Range" to "bytes=0-0"), null, readBody = false)
        when (ranged.code) {
            206 -> true
            200 -> false
            else -> throw IOException("Range 探测失败: HTTP ${ranged.code}")
        }
    }

    private data class RawResponse(val code: Int, val headers: Headers, val body: String)

    /** 单次请求：手动处理跳转，逐跳重新生成凭据头与传输材料，不读取多余响应体。 */
    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        readBody: Boolean,
    ): RawResponse {
        var current = url
        var hopCredentials = credentials
        var hopTransport = transport
        for (hop in 0..MAX_REDIRECTS) {
            SourceTransportSecurity.assertSchemeAllowed(current, hopTransport)
            val request = Request.Builder()
                .url(current)
                .method(method, body)
                .header("User-Agent", USER_AGENT)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    credentialHeaders(hopCredentials).forEach { (name, value) -> header(name, value) }
                }
                .build()
            buildClient(hopCredentials, hopTransport).newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    if (hop == MAX_REDIRECTS) throw IOException("跳转次数过多")
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) throw IOException("跳转地址缺失")
                    val next = resolveRedirect(current, location, hopTransport)
                    hopCredentials = credentialsForHop(current, next, hopCredentials)
                    hopTransport = SourceTransportSecurity.transportForRedirect(current, next, hopTransport)
                    current = next
                } else {
                    val text = if (readBody) response.body?.string().orEmpty() else ""
                    return RawResponse(response.code, response.headers, text)
                }
            }
        }
        throw IOException("跳转次数过多")
    }

    private fun buildClient(creds: SourceCredentials, tr: SourceTransport): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
        val hasTlsMaterial = tr.customCaPem.isNotBlank() ||
            tr.clientCertPem.isNotBlank() || tr.clientKeyPem.isNotBlank()
        if (hasTlsMaterial) {
            val trustManager = SourceTransportSecurity.trustManager(tr.customCaPem)
            val keyManagers: Array<KeyManager>? =
                if (tr.clientCertPem.isNotBlank() || tr.clientKeyPem.isNotBlank()) {
                    arrayOf(SourceTransportSecurity.keyManager(tr.clientCertPem, tr.clientKeyPem, tr.clientKeyPassword))
                } else {
                    null
                }
            val socketFactory = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(trustManager), null)
            }.socketFactory
            builder.sslSocketFactory(socketFactory, trustManager)
        }
        if (digestEnabled(creds, tr)) builder.authenticator(DigestAuthenticator(creds))
        return builder.build()
    }

    private fun resolveRedirect(current: String, location: String, tr: SourceTransport): String {
        require(!location.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) { "跳转地址含非法字符" }
        val next = current.toHttpUrl().resolve(location) ?: throw IOException("跳转地址无效")
        require(next.scheme.equals("http", ignoreCase = true) || next.scheme.equals("https", ignoreCase = true)) {
            "拒绝非 http(s) 跳转"
        }
        require(next.username.isEmpty() && next.password.isEmpty()) { "跳转地址不得包含 userinfo" }
        SourceTransportSecurity.assertSchemeAllowed(next.toString(), tr)
        return next.toString()
    }

    /** 只在同 host、同端口、同 https 来源时转发凭据；其余情况降级为 NONE。 */
    private fun credentialsForHop(current: String, next: String, creds: SourceCredentials): SourceCredentials {
        if (creds.kind == CredentialKind.NONE) return SourceCredentials()
        val from = runCatching { current.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
        val to = runCatching { next.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
        val sameOrigin = from.host.equals(to.host, ignoreCase = true) &&
            from.port == to.port && from.scheme.equals(to.scheme, ignoreCase = true)
        val secure = from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("https", ignoreCase = true)
        return if (sameOrigin && secure) creds else SourceCredentials()
    }
}

/** 构造时校验 base：仅 http(s)、禁止 userinfo / 查询串凭据 / requireHttps 违规。 */
private fun validateBaseUrl(value: String, transport: SourceTransport): HttpUrl {
    require(value.isNotBlank()) { "baseUrl 为空" }
    require(!value.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) { "baseUrl 含非法字符" }
    val url = runCatching { value.trim().toHttpUrl() }
        .getOrElse { throw IllegalArgumentException("baseUrl 不是有效的 http(s) URL") }
    require(url.scheme.equals("http", ignoreCase = true) || url.scheme.equals("https", ignoreCase = true)) {
        "只接受 http(s)"
    }
    require(url.username.isEmpty() && url.password.isEmpty()) { "URL 不得包含 userinfo" }
    require(url.queryParameterNames.none { it.lowercase(Locale.ROOT) in CREDENTIAL_QUERY_KEYS }) {
        "凭据不得出现在 URL 查询参数中"
    }
    SourceTransportSecurity.assertSchemeAllowed(url.toString(), transport)
    return if (url.encodedPath.endsWith("/")) url else url.newBuilder().encodedPath(url.encodedPath + "/").build()
}

/** 安全解析 PROPFIND 的 multistatus；最多 500 条，跳过集合自身与重复 href。 */
internal fun parsePropFindXml(xml: String, requestedPath: String = ""): List<WebDavEntry> {
    val root = parseSecureXml(xml).documentElement ?: throw IllegalArgumentException("multistatus 根节点缺失")
    require(root.isNamed("multistatus")) { "不是 WebDAV multistatus 响应" }
    val target = normalizePath(requestedPath)
    val result = ArrayList<WebDavEntry>()
    val seen = HashSet<String>()
    for (response in directChildren(root).filter { it.isNamed("response") }) {
        val href = childText(response, "href")
        if (href.isBlank()) continue
        val path = normalizePath(hrefToPath(href, target))
        if (path.isEmpty() || (target.isNotEmpty() && path == target)) continue
        if (!seen.add(path)) continue
        val prop = directChildren(response).filter { it.isNamed("propstat") }
            .firstOrNull { stat ->
                val status = childText(stat, "status")
                status.isBlank() || status.contains(" 200 ", ignoreCase = true) || status.endsWith(" 200", ignoreCase = true)
            }
            ?.let { childElement(it, "prop") }
        val resourceType = prop?.let { childElement(it, "resourcetype") }
        val directory = resourceType?.let { descendants(it).any { node -> node.isNamed("collection") } } ?: false
        val displayName = prop?.let { childText(it, "displayname") }.orEmpty()
        val name = displayName.ifBlank { decodeWebDavValue(path.substringAfterLast('/')) }
        result.add(
            WebDavEntry(
                name = name,
                path = path,
                directory = directory,
                size = prop?.let { childText(it, "getcontentlength") }?.toLongOrNull(),
                modifiedAt = prop?.let { childText(it, "getlastmodified") }?.let(::parseHttpDate),
                contentType = prop?.let { childText(it, "getcontenttype") }?.takeIf { it.isNotBlank() },
            ),
        )
        if (result.size >= MAX_ENTRIES) break
    }
    return result
}

private fun normalizePath(path: String): String = path.trim().trimEnd('/').ifEmpty { "/" }

private fun hrefToPath(href: String, basePath: String): String {
    val raw = href.trim().substringBefore('?').substringBefore('#')
    val location = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
        runCatching { URI(raw).rawPath }.getOrNull().orEmpty()
    } else {
        raw
    }
    val decoded = decodeWebDavValue(location)
    if (decoded.isEmpty()) return ""
    if (decoded.startsWith("/")) return decoded
    return basePath.trimEnd('/').substringBeforeLast('/') + "/" + decoded
}

private fun decodeWebDavValue(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

private fun parseHttpDate(value: String): Long? {
    val text = value.trim()
    if (text.isEmpty()) return null
    runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()?.let { return it }
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
    for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss 'GMT'", "EEE, d MMM yyyy HH:mm:ss 'GMT'")) {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        runCatching { format.parse(text)?.time }.getOrNull()?.let { return it }
    }
    return null
}

private fun parseSecureXml(xml: String): Document {
    // Real servers (and test fixtures) may prepend whitespace/BOM before the XML declaration.
    val normalized = xml.removePrefix("\uFEFF").trimStart()
    require(normalized.length <= MAX_XML_CHARS) { "XML 输入超过 4 MiB 上限" }
    require(normalized.isNotBlank()) { "XML 输入为空" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        setFeatureStrict(this, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureStrict(this, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureStrict(this, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureStrict(this, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureStrict(this, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try {
            isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            Unit
        }
        isExpandEntityReferences = false
        trySetAttribute(this, "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        trySetAttribute(this, "http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }
    val builder: DocumentBuilder = factory.newDocumentBuilder()
    builder.setErrorHandler(object : ErrorHandler {
        override fun warning(e: SAXParseException) = Unit
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
    })
    return builder.parse(InputSource(StringReader(normalized)))
}

private fun setFeatureStrict(factory: DocumentBuilderFactory, name: String, value: Boolean) {
    try {
        factory.setFeature(name, value)
    } catch (e: Exception) {
        throw IllegalStateException("Required secure XML feature is unavailable: ${name}", e)
    }
}

private fun trySetAttribute(factory: DocumentBuilderFactory, name: String, value: String) {
    try {
        factory.setAttribute(name, value)
    } catch (_: Exception) {
        Unit
    }
}

private fun Element.isNamed(name: String): Boolean =
    localName?.equals(name, ignoreCase = true) == true || nodeName.equals(name, ignoreCase = true)

private fun directChildren(parent: Element): List<Element> {
    val nodes = parent.childNodes
    val result = ArrayList<Element>()
    for (i in 0 until nodes.length) {
        if (nodes.item(i) is Element) result.add(nodes.item(i) as Element)
    }
    return result
}

private fun childElement(parent: Element, name: String): Element? =
    directChildren(parent).firstOrNull { it.isNamed(name) }

private fun childText(parent: Element, vararg names: String): String {
    val children = directChildren(parent)
    for (name in names) {
        children.firstOrNull { it.isNamed(name) }?.textContent?.trim()?.let { text ->
            if (text.isNotEmpty()) return text
        }
    }
    return ""
}

private fun descendants(root: Element): List<Element> {
    val nodes = root.getElementsByTagName("*")
    val result = ArrayList<Element>()
    for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { result.add(it) }
    return result
}
