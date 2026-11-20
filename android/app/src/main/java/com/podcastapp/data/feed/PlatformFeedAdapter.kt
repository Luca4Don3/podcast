package com.podcastapp.data.feed

import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URISyntaxException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/**
 * 公开播客源的本地适配层：仅做 URL 归一化与安全的 XML 解析。
 *
 * 本文件不发起网络请求、不抓取网页、不伪造 RSS，也不使用任何官方
 * Spotify / 小宇宙 / Apple 播客 API。Spotify、小宇宙、喜马拉雅的链接
 * 只被标记为 [FeedReference.WebMetadata]，由上层决定如何展示。
 */

/** 链接所属平台。DIRECT 表示可直接当作 feed 地址使用。 */
enum class FeedPlatform { DIRECT, APPLE, SPOTIFY, XIAOYUZHOU, XIMALAYA }

/** 公开 feed 的凭据类型：无、HTTP Basic、Bearer Token、请求头 API Key、HTTP Digest。 */
enum class CredentialKind { NONE, BASIC, BEARER, API_KEY, DIGEST }

/**
 * 访问公开 feed 所需的凭据。只进入加密凭据存储，绝不写入 URL 或订阅 JSON。
 * [header] 仅在 [CredentialKind.API_KEY] 时作为请求头名使用。
 * [CredentialKind.DIGEST] 不生成静态 Authorization，只在服务端 401 挑战后由 DigestAuth 计算。
 */
data class SourceCredentials(
    val kind: CredentialKind = CredentialKind.NONE,
    val username: String = "",
    val secret: String = "",
    val header: String = "X-API-Key",
)

/** 归一化后的播客源引用。 */
sealed interface FeedReference {
    /** 输入链接的规范形式（去除首尾空白后的原样 http(s) URL）。 */
    val url: String

    /** 可直接抓取的地址；WebMetadata 为 null，表示不得伪造 RSS。 */
    val fetchUrl: String?

    val platform: FeedPlatform

    /** 普通 RSS / Atom / feed 直链；url 为已移除凭据的干净地址。 */
    data class DirectFeed(
        override val url: String,
        val credentials: SourceCredentials = SourceCredentials(),
    ) : FeedReference {
        override val fetchUrl: String get() = url
        override val platform: FeedPlatform get() = FeedPlatform.DIRECT
    }

    /** Apple 播客页面：给出 iTunes Search API 的 lookup 地址，不抓取网页。 */
    data class AppleLookup(
        val originalUrl: String,
        val lookupUrl: String,
        val showId: String,
    ) : FeedReference {
        override val url: String get() = originalUrl
        override val fetchUrl: String get() = lookupUrl
        override val platform: FeedPlatform get() = FeedPlatform.APPLE
    }

    /** 仅提供网页元数据，不提供可播放/可订阅的 feed 地址。 */
    data class WebMetadata(
        val pageUrl: String,
        override val platform: FeedPlatform,
    ) : FeedReference {
        override val url: String get() = pageUrl
        override val fetchUrl: String? get() = null
    }
}

/** OPML 中的一条订阅。 */
data class OpmlFeed(val title: String, val xmlUrl: String)

/** RSS item / Atom entry 解析结果。缺少的字段为空字符串。 */
data class ParsedEpisode(
    val title: String,
    val description: String,
    val guid: String,
    val audioUrl: String,
    val pubDate: String,
    val duration: String,
)

/** RSS2 channel / Atom feed 解析结果，最多 500 条。 */
data class ParsedFeed(
    val title: String,
    val description: String,
    val items: List<ParsedEpisode>,
)

object PlatformFeedAdapter {
    private const val MAX_ITEMS = 500
    private const val MAX_XML_CHARS = 4 * 1024 * 1024
    private const val MAX_URL_LENGTH = 8192

    private val HOST_LABEL = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\z""")
    private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}\z""")
    private val APPLE_ID = Regex("""(?:^|/)id(\d+)/?\z""", RegexOption.IGNORE_CASE)
    private val SPOTIFY_SHOW = Regex("""/show/([A-Za-z0-9]+)/?\z""", RegexOption.IGNORE_CASE)
    private val XIAOYUZHOU_PODCAST = Regex("""/podcast/([A-Za-z0-9]+)/?\z""", RegexOption.IGNORE_CASE)
    private val XIMALAYA_ALBUM = Regex("""/album/(\d+)/?\z""", RegexOption.IGNORE_CASE)

    /**
     * 把用户输入归一化为 [FeedReference]。
     *
     * URL 中的 user:pass@ 以及 ?token= / ?api_key= / ?key= 会被提取为 [SourceCredentials]
     * 并从返回的 url 中移除；URL 中的凭据优先于显式传入的 [credentials]。
     * 拒绝控制字符、歧义 authority、非法 host 以及非 http(s) 协议。
     */
    fun normalize(
        input: String,
        credentials: SourceCredentials = SourceCredentials(),
    ): Result<FeedReference> = runCatching {
        require(!input.any { it.isISOControl() }) { "Control characters are not allowed in a URL" }
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Input is blank" }
        // 反斜杠/空白可能造成 authority 误判，必须在提取凭据之前拒绝。
        require(!raw.any { it == '\\' || it.isWhitespace() }) {
            "Only http(s) URLs with a valid host are supported"
        }
        val sanitized = stripUrlCredentials(raw)
        val uri = parseHttpUri(sanitized.url)
            ?: throw IllegalArgumentException("Only http(s) URLs with a valid host are supported")
        classify(sanitized.url, uri, sanitized.credentials ?: credentials)
    }

    /** 解析 OPML，提取 outline 的 xmlUrl/title，按 xmlUrl 去重，最多 500 条，输入上限 4 MiB。 */
    fun parseOpml(xml: String): Result<List<OpmlFeed>> = runCatching {
        val document = parseDocument(xml)
        val nodes = document.documentElement.getElementsByTagName("*")
        val seen = LinkedHashSet<String>()
        val feeds = ArrayList<OpmlFeed>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (!element.isNamed("outline")) continue
            val xmlUrl = element.attr("xmlUrl")
            if (!isHttpUrl(xmlUrl)) continue
            if (!seen.add(xmlUrl)) continue
            val title = element.attr("title")
                .ifBlank { element.attr("text") }
                .ifBlank { xmlUrl }
            feeds.add(OpmlFeed(title = title, xmlUrl = xmlUrl))
            if (feeds.size >= MAX_ITEMS) break
        }
        feeds
    }

    /** 解析 RSS2 channel/item 或 Atom feed/entry，安全解析且最多 500 条，输入上限 4 MiB。 */
    fun parseFeedXml(xml: String): Result<ParsedFeed> = runCatching {
        val document = parseDocument(xml)
        val root = document.documentElement
        val container = childElements(root).firstOrNull { it.isNamed("channel") } ?: root
        val title = childText(container, "title")
        val description = childText(container, "description", "subtitle", "summary")
        val directItems = childElements(container).filter { it.isNamed("item") || it.isNamed("entry") }
        val elements = if (directItems.isNotEmpty()) {
            directItems
        } else {
            descendants(root).filter { it.isNamed("item") || it.isNamed("entry") }
        }
        ParsedFeed(
            title = title,
            description = description,
            items = elements.take(MAX_ITEMS).map(::parseEpisode),
        )
    }

    private fun classify(raw: String, uri: URI, credentials: SourceCredentials): FeedReference {
        val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
        val path = uri.path.orEmpty()
        when (host) {
            "podcasts.apple.com" -> {
                val id = APPLE_ID.find(path)?.groupValues?.get(1)
                if (id != null) {
                    return FeedReference.AppleLookup(
                        originalUrl = raw,
                        lookupUrl = "https://itunes.apple.com/lookup?id=$id&entity=podcast",
                        showId = id,
                    )
                }
            }
            "open.spotify.com" -> {
                if (SPOTIFY_SHOW.containsMatchIn(path)) {
                    return FeedReference.WebMetadata(raw, FeedPlatform.SPOTIFY)
                }
            }
            "www.xiaoyuzhoufm.com" -> {
                if (XIAOYUZHOU_PODCAST.containsMatchIn(path)) {
                    return FeedReference.WebMetadata(raw, FeedPlatform.XIAOYUZHOU)
                }
            }
            "www.ximalaya.com" -> {
                if (XIMALAYA_ALBUM.containsMatchIn(path)) {
                    return FeedReference.WebMetadata(raw, FeedPlatform.XIMALAYA)
                }
            }
        }
        return FeedReference.DirectFeed(raw, credentials)
    }

    /** 提取 URL 内嵌凭据，返回去掉凭据之后的 URL；没有内嵌凭据时 [credentials] 为 null。 */
    private fun stripUrlCredentials(raw: String): SanitizedUrl {
        var url = raw
        var extracted: SourceCredentials? = null

        // user:pass@ —— 多个 @ 的 authority 有歧义，保留原样交给 parseHttpUri 拒绝。
        val schemeEnd = url.indexOf("://")
        if (schemeEnd > 0) {
            val authorityStart = schemeEnd + 3
            val rawEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            val authorityEnd = if (rawEnd < 0) url.length else rawEnd
            val authority = url.substring(authorityStart, authorityEnd)
            val at = authority.indexOf('@')
            if (at >= 0 && authority.indexOf('@', at + 1) < 0) {
                val userInfo = authority.substring(0, at)
                if (userInfo.isNotEmpty()) {
                    val colon = userInfo.indexOf(':')
                    extracted = SourceCredentials(
                        kind = CredentialKind.BASIC,
                        username = decodeComponent(if (colon >= 0) userInfo.substring(0, colon) else userInfo),
                        secret = decodeComponent(if (colon >= 0) userInfo.substring(colon + 1) else ""),
                    )
                }
                url = url.substring(0, authorityStart) + authority.substring(at + 1) + url.substring(authorityEnd)
            }
        }

        // token / api_key / key —— 其余查询参数原样保留（含原有编码与顺序）。
        val queryStart = url.indexOf('?')
        if (queryStart >= 0) {
            val fragmentAt = url.indexOf('#', queryStart + 1)
            val queryEnd = if (fragmentAt < 0) url.length else fragmentAt
            val kept = ArrayList<String>()
            var token: String? = null
            var apiKey: String? = null
            url.substring(queryStart + 1, queryEnd).split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eq = pair.indexOf('=')
                val name = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                when (name.lowercase(Locale.ROOT)) {
                    "token" -> token = decodeComponent(value)
                    "api_key", "key" -> {
                        if (apiKey == null) apiKey = decodeComponent(value)
                    }
                    else -> kept.add(pair)
                }
            }
            url = if (kept.isEmpty()) {
                url.substring(0, queryStart) + url.substring(queryEnd)
            } else {
                url.substring(0, queryStart + 1) + kept.joinToString("&") + url.substring(queryEnd)
            }
            val queryCredentials = when {
                token != null -> SourceCredentials(kind = CredentialKind.BEARER, secret = token.orEmpty())
                apiKey != null -> SourceCredentials(kind = CredentialKind.API_KEY, secret = apiKey.orEmpty())
                else -> null
            }
            if (queryCredentials != null) {
                extracted = extracted?.copy(
                    kind = queryCredentials.kind,
                    secret = queryCredentials.secret,
                ) ?: queryCredentials
            }
        }
        return SanitizedUrl(url, extracted)
    }

    private fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    private data class SanitizedUrl(val url: String, val credentials: SourceCredentials?)

    private fun parseHttpUri(raw: String): URI? {
        if (raw.isEmpty() || raw.length > MAX_URL_LENGTH) return null
        if (raw.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null || uri.rawAuthority?.contains('@') == true) return null
        val host = uri.host ?: return null
        if (!isValidHost(host)) return null
        if (uri.port != -1 && uri.port !in 0..65535) return null
        return uri
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith("[")) {
            if (!host.endsWith("]")) return false
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() && inner.all { it.isDigit() || it in "abcdefABCDEF:." }
        }
        if (host.contains(':')) return false
        if (IPV4.matches(host)) {
            return host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        val labels = host.removeSuffix(".").split('.')
        return labels.isNotEmpty() && labels.all { HOST_LABEL.matches(it) }
    }

    private fun isHttpUrl(value: String): Boolean = parseHttpUri(value.trim()) != null

    private fun parseDocument(xml: String): Document {
        require(xml.length <= MAX_XML_CHARS) { "XML input exceeds the 4 MiB limit" }
        require(xml.isNotBlank()) { "XML input is blank" }
        rejectDoctype(xml)
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            isExpandEntityReferences = false
            try {
                isXIncludeAware = false
            } catch (_: UnsupportedOperationException) {
                // Providers without XInclude support leave it disabled.
            }
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are not allowed") }
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
        })
        return builder.parse(InputSource(StringReader(xml)))
    }

    // Android's DOM provider does not implement Xerces' DOCTYPE feature. Reject
    // the declaration before handing the same characters to either provider.
    private fun rejectDoctype(xml: String) {
        var next = 0
        while (true) {
            val start = xml.indexOf('<', next)
            if (start < 0) return
            next = when {
                xml.startsWith("<!DOCTYPE", start) -> throw SAXException("XML DOCTYPE declarations are not allowed")
                xml.startsWith("<!--", start) -> skipXmlSection(xml, start + 4, "-->")
                xml.startsWith("<?", start) -> skipXmlSection(xml, start + 2, "?>")
                xml.startsWith("<![CDATA[", start) -> skipXmlSection(xml, start + 9, "]]>")
                else -> start + 1
            }
        }
    }

    private fun skipXmlSection(xml: String, from: Int, terminator: String): Int {
        val end = xml.indexOf(terminator, from)
        if (end < 0) throw SAXException("Unterminated XML markup")
        return end + terminator.length
    }

    private fun Element.isNamed(name: String): Boolean =
        localName?.equals(name, ignoreCase = true) == true || nodeName.equals(name, ignoreCase = true)

    private fun Element.attr(name: String): String {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) as? Attr ?: continue
            if (attr.nodeName.equals(name, ignoreCase = true) ||
                attr.localName?.equals(name, ignoreCase = true) == true
            ) {
                val value = attr.value.trim()
                if (value.isNotEmpty()) return value
            }
        }
        return ""
    }

    private fun childElements(parent: Element): List<Element> {
        val nodes = parent.childNodes
        val result = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) result.add(node)
        }
        return result
    }

    private fun descendants(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("*")
        val result = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            result.add(node)
        }
        return result
    }

    private fun childText(parent: Element, vararg names: String): String {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && names.any { node.isNamed(it) }) {
                val text = node.textContent.trim()
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }

    private fun parseEpisode(element: Element): ParsedEpisode = ParsedEpisode(
        title = childText(element, "title"),
        description = childText(element, "description", "summary", "content", "encoded"),
        guid = childText(element, "guid", "id"),
        audioUrl = extractAudioUrl(element),
        pubDate = childText(element, "pubDate", "published", "updated", "date"),
        duration = childText(element, "duration"),
    )

    private fun extractAudioUrl(item: Element): String {
        val nodes = item.getElementsByTagName("*")
        var fallback = ""
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val isEnclosure = element.isNamed("enclosure") ||
                (element.isNamed("link") && element.attr("rel").equals("enclosure", ignoreCase = true))
            if (!isEnclosure) continue
            val url = element.attr("url").ifBlank { element.attr("href") }
            if (!isHttpUrl(url)) continue
            if (element.attr("type").startsWith("audio/", ignoreCase = true)) return url
            if (fallback.isEmpty()) fallback = url
        }
        return fallback
    }
}
