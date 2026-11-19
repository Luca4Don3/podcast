package com.podcastapp.data.feed

import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
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

/** 归一化后的播客源引用。 */
sealed interface FeedReference {
    /** 输入链接的规范形式（去除首尾空白后的原样 http(s) URL）。 */
    val url: String

    /** 可直接抓取的地址；WebMetadata 为 null，表示不得伪造 RSS。 */
    val fetchUrl: String?

    val platform: FeedPlatform

    /** 普通 RSS / Atom / feed 直链。 */
    data class DirectFeed(override val url: String) : FeedReference {
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
     * 拒绝控制字符、userinfo、非法 host 以及非 http(s) 协议；其余合法 http(s) 链接均为 [FeedReference.DirectFeed]。
     */
    fun normalize(input: String): Result<FeedReference> = runCatching {
        require(!input.any { it.isISOControl() }) { "Control characters are not allowed in a URL" }
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Input is blank" }
        val uri = parseHttpUri(raw) ?: throw IllegalArgumentException("Only http(s) URLs with a valid host are supported")
        classify(raw, uri)
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

    private fun classify(raw: String, uri: URI): FeedReference {
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
        return FeedReference.DirectFeed(raw)
    }

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
            // Android's javax.xml.XMLConstants does not expose these property names.
            trySetAttribute(this, "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            trySetAttribute(this, "http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val builder: DocumentBuilder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
        })
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun setFeatureStrict(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (e: Exception) {
            throw IllegalStateException("Required secure XML feature is unavailable: $name", e)
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
