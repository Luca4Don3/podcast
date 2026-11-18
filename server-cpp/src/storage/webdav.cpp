#include "source.hpp"

#if defined(PODCAST_ENABLE_WEBDAV) && PODCAST_ENABLE_WEBDAV

#include <curl/curl.h>
#include <libxml/parser.h>
#include <libxml/tree.h>

#include <algorithm>
#include <charconv>
#include <set>
#include <utility>

namespace podcast::storage {
namespace {

constexpr std::size_t kMaxXml = 1024 * 1024;
constexpr std::size_t kMaxHeaders = 64 * 1024;
constexpr std::size_t kMaxUrl = 8192;
constexpr std::size_t kMaxListRows = 500;

bool curlReady() {
    // Keep libcurl initialized for the process lifetime, including outstanding Files.
    static const bool initialized = curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK;
    return initialized;
}

char asciiLower(char c) { return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c; }

bool asciiEqual(std::string_view a, std::string_view b) {
    return a.size() == b.size() && std::equal(a.begin(), a.end(), b.begin(),
        [](char x, char y) { return asciiLower(x) == asciiLower(y); });
}

bool digit(char c) { return c >= '0' && c <= '9'; }

std::optional<std::uint64_t> number(std::string_view s) {
    if (s.empty() || !digit(s.front())) return std::nullopt;
    std::uint64_t n = 0;
    const auto [end, ec] = std::from_chars(s.data(), s.data() + s.size(), n);
    if (ec != std::errc{} || end != s.data() + s.size()) return std::nullopt;
    return n;
}

std::string_view trim(std::string_view s, std::string_view whitespace = " \t") {
    const auto first = s.find_first_not_of(whitespace);
    if (first == s.npos) return {};
    return s.substr(first, s.find_last_not_of(whitespace) - first + 1);
}

bool unreserved(unsigned char c) {
    return digit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
           c == '-' || c == '.' || c == '_' || c == '~';
}

int hexValue(char c) {
    if (digit(c)) return c - '0';
    c = asciiLower(c);
    return c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
}

bool control(unsigned char c) { return c < 0x20 || c == 0x7f; }

bool safeSegment(std::string_view segment) {
    if (segment.empty() || segment == "." || segment == "..") return false;
    for (unsigned char c : segment) if (control(c) || c == '/' || c == '\\') return false;
    // No ambiguous escapes that a DAV server or front end could decode again.
    for (std::size_t i = 0; i + 2 < segment.size(); ++i) {
        if (segment[i] == '%' && hexValue(segment[i + 1]) >= 0 && hexValue(segment[i + 2]) >= 0)
            return false;
    }
    return true;
}

std::optional<std::string> decodeSegment(std::string_view raw) {
    std::string decoded;
    for (std::size_t i = 0; i < raw.size(); ++i) {
        const char c = raw[i];
        if (c == '%') {
            if (raw.size() - i < 3) return std::nullopt;
            const int hi = hexValue(raw[i + 1]), lo = hexValue(raw[i + 2]);
            if (hi < 0 || lo < 0) return std::nullopt;
            decoded.push_back(static_cast<char>(hi * 16 + lo));
            i += 2;
        } else {
            if (!unreserved(c) && std::string_view("!$&'()*+,;=:@").find(c) == raw.npos)
                return std::nullopt;
            decoded.push_back(c);
        }
    }
    if (!safeSegment(decoded) || !validRelativePath(decoded, false)) return std::nullopt;
    return decoded;
}

void encodeSegment(std::string& url, std::string_view segment) {
    constexpr char hex[] = "0123456789ABCDEF";
    for (unsigned char c : segment) {
        if (unreserved(c)) url.push_back(static_cast<char>(c));
        else {
            url.push_back('%');
            url.push_back(hex[c >> 4]);
            url.push_back(hex[c & 15]);
        }
    }
}

using Segments = std::vector<std::string>;

std::optional<Segments> uriPath(std::string_view raw) {
    if (raw.empty() || raw.front() != '/' || raw.starts_with("//")) return std::nullopt;
    raw.remove_prefix(1);
    Segments path;
    while (!raw.empty()) {
        const auto slash = raw.find('/');
        auto segment = decodeSegment(raw.substr(0, slash));
        if (!segment) return std::nullopt;
        path.push_back(std::move(*segment));
        if (slash == raw.npos) break;
        raw.remove_prefix(slash + 1);
    }
    return path;
}

struct Address {
    std::string host;
    std::string port;
    std::string_view rawPath;
};

std::optional<Address> httpsAddress(std::string_view url) {
    if (url.size() > kMaxUrl || !asciiEqual(url.substr(0, 8), "https://")) return std::nullopt;
    for (unsigned char c : url)
        if (c <= ' ' || c >= 0x7f || c == '\\' || c == '?' || c == '#') return std::nullopt;
    const auto slash = url.find('/', 8);
    const auto authority = url.substr(8, slash == url.npos ? url.npos : slash - 8);
    if (authority.empty() || authority.find_first_of("@%") != url.npos) return std::nullopt;
    // Parse only the authority with curl. Validate the path independently before use.
    std::unique_ptr<CURLU, decltype(&curl_url_cleanup)> parsed(curl_url(), curl_url_cleanup);
    const std::string origin = "https://" + std::string(authority);
    if (!parsed || curl_url_set(parsed.get(), CURLUPART_URL, origin.c_str(), 0) != CURLUE_OK)
        return std::nullopt;
    auto part = [&](CURLUPart key, unsigned flags) -> std::optional<std::string> {
        char* value = nullptr;
        if (curl_url_get(parsed.get(), key, &value, flags) != CURLUE_OK) return std::nullopt;
        std::unique_ptr<char, decltype(&curl_free)> owned(value, curl_free);
        return std::string(owned.get());
    };
    auto host = part(CURLUPART_HOST, 0), port = part(CURLUPART_PORT, CURLU_DEFAULT_PORT);
    if (!host || host->empty() || !port || *port == "0") return std::nullopt;
    return Address{std::move(*host), std::move(*port), slash == url.npos ? "/" : url.substr(slash)};
}

struct Endpoint {
    std::string host;
    std::string port;
    std::string base;
    Segments baseSegments;
    std::string user;
    std::string pass;

    std::optional<std::string> requestUrl(std::string_view path, bool collection) const {
        if (path.size() > kMaxUrl || !validRelativePath(path, collection)) return std::nullopt;
        std::string url = base;
        for (std::size_t i = 0; i < path.size();) {
            const auto slash = path.find('/', i);
            const auto component = path.substr(i, slash == path.npos ? path.npos : slash - i);
            if (!safeSegment(component)) return std::nullopt;
            encodeSegment(url, component);
            if (slash == path.npos) {
                if (collection) url.push_back('/');
                break;
            }
            url.push_back('/');
            i = slash + 1;
        }
        if (url.size() > kMaxUrl) return std::nullopt;
        return url;
    }

    std::optional<Segments> hrefPath(std::string_view href) const {
        if (href.empty() || href.size() > kMaxUrl) return std::nullopt;
        if (href.front() == '/') return uriPath(href);
        const auto address = httpsAddress(href);
        if (!address || !asciiEqual(host, address->host) || port != address->port) return std::nullopt;
        return uriPath(address->rawPath);
    }
};

int statusCode(std::string_view line) {
    const auto space = line.find(' ');
    if (space == line.npos || !line.starts_with("HTTP/")) return 0;
    const auto version = line.substr(5, space - 5);
    if (version != "1.0" && version != "1.1" && version != "2" && version != "3") return 0;
    line.remove_prefix(space + 1);
    if (line.size() < 3 || (line.size() > 3 && line[3] != ' ')) return 0;
    const auto code = number(line.substr(0, 3));
    return code && *code >= 100 && *code <= 599 ? static_cast<int>(*code) : 0;
}

struct ContentRange {
    std::uint64_t first, last, total;
    bool operator==(const ContentRange&) const = default;
};

std::optional<ContentRange> parseRange(std::string_view s) {
    if (!asciiEqual(s.substr(0, 6), "bytes ")) return std::nullopt;
    s.remove_prefix(6);
    const auto dash = s.find('-'), slash = s.find('/');
    if (dash == s.npos || slash == s.npos || dash >= slash) return std::nullopt;
    const auto first = number(s.substr(0, dash)), last = number(s.substr(dash + 1, slash - dash - 1)),
               total = number(s.substr(slash + 1));
    if (!first || !last || !total || *first > *last || *last >= *total) return std::nullopt;
    return ContentRange{*first, *last, *total};
}

struct Transfer {
    enum class Kind { List, Head, Read };
    explicit Transfer(Kind kind) : kind(kind) {}

    Kind kind;
    std::optional<ContentRange> requested;
    const Sink* sink = nullptr;
    std::string xml;
    std::uint64_t received = 0;
    std::size_t headerBytes = 0;
    int code = 0;
    bool complete = false;
    bool identity = false;
    bool chunked = false;
    bool hasType = false;
    bool multipart = false;
    std::optional<std::uint64_t> length;
    std::optional<ContentRange> range;

    std::uint64_t count() const { return requested->last - requested->first + 1; }

    bool accepted() const {
        if (!complete || (length && chunked)) return false;
        switch (kind) {
        case Kind::List: return code == 207 && !range && (!length || *length <= kMaxXml);
        case Kind::Head: return code == 200 && length && !range;
        case Kind::Read:
            if (multipart) return false;
            if (code == 206) return range == requested && (!length || *length == count());
            // A response that ignored Range cannot be used for a partial request.
            return code == 200 && !range && requested->first == 0 && count() == requested->total &&
                   length == requested->total;
        }
        return false;
    }

    bool header(std::string_view line) {
        if (line.size() > kMaxHeaders - headerBytes || !line.ends_with('\n')) return false;
        headerBytes += line.size();
        line.remove_suffix(1);
        if (line.ends_with('\r')) line.remove_suffix(1);
        if (line.starts_with("HTTP/")) {
            if (code && (!complete || code >= 200)) return false;
            code = statusCode(line);
            complete = identity = chunked = hasType = multipart = false;
            length.reset();
            range.reset();
            return code && code != 101;
        }
        if (!code) return false;
        if (complete) return code >= 200;  // Ignore trailers; never let them replace response metadata.
        if (line.empty()) {
            complete = true;
            // Reject a bad final response before any of its body can reach the sink.
            return code < 200 || accepted();
        }
        const auto colon = line.find(':');
        if (colon == line.npos || colon == 0) return false;
        const auto key = line.substr(0, colon);
        for (unsigned char c : key) {
            if (!unreserved(c) && std::string_view("!#$%&'*+^\x60|").find(c) == key.npos) return false;
        }
        const auto value = trim(line.substr(colon + 1));
        for (unsigned char c : value) if (control(c) && c != '\t') return false;
        if (asciiEqual(key, "Content-Length")) {
            if (length) return false;
            length = number(value);
            return length.has_value();
        }
        if (asciiEqual(key, "Content-Range")) {
            if (range) return false;
            range = parseRange(value);
            return range.has_value();
        }
        if (asciiEqual(key, "Content-Encoding")) {
            if (identity || !asciiEqual(value, "identity")) return false;
            identity = true;
        }
        if (asciiEqual(key, "Transfer-Encoding")) {
            if (chunked || !asciiEqual(value, "chunked")) return false;
            chunked = true;
        }
        if (asciiEqual(key, "Content-Type")) {
            if (hasType) return false;
            hasType = true;
            multipart = asciiEqual(value.substr(0, 10), "multipart/");
        }
        return true;
    }

    bool body(std::string_view data) {
        if (!accepted()) return false;
        if (kind == Kind::List) {
            const auto limit = length.value_or(kMaxXml);
            if (data.size() > limit - xml.size()) return false;
            xml.append(data);
            return true;
        }
        if (kind != Kind::Read || data.size() > count() - received || !(*sink)(data)) return false;
        received += data.size();
        return true;
    }

    static std::size_t onHeader(char* ptr, std::size_t unit, std::size_t n, void* userdata) noexcept {
        try {
            return static_cast<Transfer*>(userdata)->header({ptr, unit * n}) ? unit * n : 0;
        } catch (...) { return 0; }
    }

    static std::size_t onBody(char* ptr, std::size_t unit, std::size_t n, void* userdata) noexcept {
        try {
            const auto bytes = unit * n;
            return bytes && static_cast<Transfer*>(userdata)->body({ptr, bytes}) ? bytes : 0;
        } catch (...) { return 0; }
    }
};

bool perform(const Endpoint& endpoint, const std::string& url, Transfer& transfer) {
    using Kind = Transfer::Kind;
    constexpr char query[] = "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/>"
                             "<D:getcontentlength/></D:prop></D:propfind>";
    std::unique_ptr<curl_slist, decltype(&curl_slist_free_all)> headers(nullptr, curl_slist_free_all);
    std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> easy(curl_easy_init(), curl_easy_cleanup);
    if (!easy) return false;
    auto set = [&](CURLoption option, auto value) { return curl_easy_setopt(easy.get(), option, value) == CURLE_OK; };
    if (!set(CURLOPT_URL, url.c_str()) || !set(CURLOPT_PATH_AS_IS, 1L) ||
#if LIBCURL_VERSION_NUM >= 0x075500
        !set(CURLOPT_PROTOCOLS_STR, "https") || !set(CURLOPT_REDIR_PROTOCOLS_STR, "https") ||
#else
        !set(CURLOPT_PROTOCOLS, static_cast<long>(CURLPROTO_HTTPS)) ||
        !set(CURLOPT_REDIR_PROTOCOLS, static_cast<long>(CURLPROTO_HTTPS)) ||
#endif
        !set(CURLOPT_FOLLOWLOCATION, 0L) || !set(CURLOPT_PROXY, "") ||
        !set(CURLOPT_NETRC, static_cast<long>(CURL_NETRC_IGNORED)) ||
        !set(CURLOPT_SSL_VERIFYPEER, 1L) || !set(CURLOPT_SSL_VERIFYHOST, 2L) ||
        !set(CURLOPT_HTTPAUTH, static_cast<long>(CURLAUTH_BASIC)) ||
        !set(CURLOPT_ACCEPT_ENCODING, "identity") || !set(CURLOPT_HTTP_CONTENT_DECODING, 0L) ||
        !set(CURLOPT_NOSIGNAL, 1L) || !set(CURLOPT_CONNECTTIMEOUT_MS, 5000L) ||
        !set(CURLOPT_TIMEOUT_MS, transfer.kind == Kind::Read ? 120000L : 15000L) ||
        !set(CURLOPT_LOW_SPEED_LIMIT, 1L) || !set(CURLOPT_LOW_SPEED_TIME, 15L) ||
        !set(CURLOPT_HEADERFUNCTION, Transfer::onHeader) || !set(CURLOPT_HEADERDATA, &transfer) ||
        !set(CURLOPT_WRITEFUNCTION, Transfer::onBody) || !set(CURLOPT_WRITEDATA, &transfer)) return false;
    if ((!endpoint.user.empty() || !endpoint.pass.empty()) &&
        (!set(CURLOPT_USERNAME, endpoint.user.c_str()) || !set(CURLOPT_PASSWORD, endpoint.pass.c_str())))
        return false;

    std::string range;
    switch (transfer.kind) {
    case Kind::List:
        for (const char* h : {"Depth: 1", "Content-Type: application/xml; charset=utf-8", "Expect:"}) {
            auto* list = curl_slist_append(headers.get(), h);
            if (!list) return false;
            headers.release();
            headers.reset(list);
        }
        if (!set(CURLOPT_HTTPHEADER, headers.get()) || !set(CURLOPT_CUSTOMREQUEST, "PROPFIND") ||
            !set(CURLOPT_POSTFIELDS, query) || !set(CURLOPT_POSTFIELDSIZE, static_cast<long>(sizeof(query) - 1)))
            return false;
        break;
    case Kind::Head:
        if (!set(CURLOPT_NOBODY, 1L)) return false;
        break;
    case Kind::Read:
        range = std::to_string(transfer.requested->first) + "-" + std::to_string(transfer.requested->last);
        if (!set(CURLOPT_RANGE, range.c_str())) return false;
        break;
    }
    if (curl_easy_perform(easy.get()) != CURLE_OK || !transfer.accepted()) return false;
    if (transfer.kind == Kind::Read) return transfer.received == transfer.count();
    return transfer.kind != Kind::List || !transfer.length || transfer.xml.size() == *transfer.length;
}

void forbidSubset(void* ctx, const xmlChar*, const xmlChar*, const xmlChar*) {
    xmlStopParser(static_cast<xmlParserCtxtPtr>(ctx));
}

xmlParserInputPtr forbidExternalEntity(void* ctx, const xmlChar*, const xmlChar*) {
    xmlStopParser(static_cast<xmlParserCtxtPtr>(ctx));
    return nullptr;
}

using XmlDoc = std::unique_ptr<xmlDoc, decltype(&xmlFreeDoc)>;

XmlDoc parseXml(const std::string& body) {
    static const bool initialized = [] { xmlInitParser(); return true; }();
    (void)initialized;
    std::unique_ptr<xmlParserCtxt, decltype(&xmlFreeParserCtxt)> ctxt(
        xmlCreatePushParserCtxt(nullptr, nullptr, nullptr, 0, nullptr), xmlFreeParserCtxt);
    XmlDoc doc(nullptr, xmlFreeDoc);
    if (!ctxt) return doc;
    // Per-context SAX hooks reject DOCTYPE before parsing its declarations and
    // refuse external entities without changing libxml2's process-wide loader.
    ctxt->sax->internalSubset = forbidSubset;
    ctxt->sax->externalSubset = forbidSubset;
    ctxt->sax->resolveEntity = forbidExternalEntity;
    int options = XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING;
#if LIBXML_VERSION >= 21300
    options |= XML_PARSE_NO_XXE;
#endif
    if (xmlCtxtUseOptions(ctxt.get(), options) != 0) return doc;
    const int result = xmlParseChunk(ctxt.get(), body.data(), static_cast<int>(body.size()), 1);
    doc.reset(ctxt->myDoc);
    ctxt->myDoc = nullptr;
    if (result != 0 || !ctxt->wellFormed || (doc && (doc->intSubset || doc->extSubset))) doc.reset();
    return doc;
}

bool dav(const xmlNode* node, const char* name) {
    return node && node->type == XML_ELEMENT_NODE && node->ns &&
           xmlStrEqual(node->ns->href, BAD_CAST "DAV:") && xmlStrEqual(node->name, BAD_CAST name);
}

const xmlNode* onlyChild(const xmlNode* node, const char* name) {
    const xmlNode* found = nullptr;
    for (auto* child = node->children; child; child = child->next) {
        if (!dav(child, name)) continue;
        if (found) return nullptr;
        found = child;
    }
    return found;
}

std::string text(const xmlNode* node) {
    std::string result;
    if (!node) return result;
    for (auto* n = node->children; n; n = n->next) {
        if (n->type == XML_COMMENT_NODE) continue;
        if (n->type != XML_TEXT_NODE && n->type != XML_CDATA_SECTION_NODE) return {};
        if (n->content) result += reinterpret_cast<const char*>(n->content);
    }
    return std::string(trim(result, " \t\r\n"));
}

bool successful(const xmlNode* node) {
    const int status = statusCode(text(node));
    return status >= 200 && status < 300;
}

std::optional<Entry> properties(const xmlNode* response, std::string name) {
    std::optional<bool> directory;
    std::optional<std::uint64_t> size;
    for (auto* child = response->children; child; child = child->next) {
        // For a PROPFIND response only successful propstat blocks supply metadata.
        if (dav(child, "status") && !successful(child)) return std::nullopt;
        if (!dav(child, "propstat") || !successful(onlyChild(child, "status"))) continue;
        const auto* prop = onlyChild(child, "prop");
        if (!prop) continue;
        if (const auto* type = onlyChild(prop, "resourcetype")) {
            bool collection = false;
            for (auto* n = type->children; n; n = n->next) collection |= dav(n, "collection");
            if (directory && *directory != collection) return std::nullopt;
            directory = collection;
        }
        if (const auto* len = onlyChild(prop, "getcontentlength")) {
            auto parsed = number(text(len));
            if (!parsed || (size && parsed != size)) return std::nullopt;
            size = parsed;
        }
    }
    if (!directory) return std::nullopt;
    return Entry{std::move(name), *directory, *directory ? 0 : size.value_or(0)};
}

class WebDavSource final : public Source {
public:
    explicit WebDavSource(std::shared_ptr<const Endpoint> endpoint) : endpoint_(std::move(endpoint)) {}

    std::optional<std::vector<Entry>> list(std::string_view path) const override {
        const auto url = endpoint_->requestUrl(path, true);
        if (!url) return std::nullopt;
        Transfer transfer(Transfer::Kind::List);
        if (!perform(*endpoint_, *url, transfer)) return std::nullopt;
        auto doc = parseXml(transfer.xml);
        if (!doc) return std::nullopt;
        const auto* root = xmlDocGetRootElement(doc.get());
        if (!dav(root, "multistatus")) return std::nullopt;

        auto parent = endpoint_->baseSegments;
        while (!path.empty()) {
            const auto slash = path.find('/');
            parent.emplace_back(path.substr(0, slash));
            if (slash == path.npos) break;
            path.remove_prefix(slash + 1);
        }
        std::vector<Entry> entries;
        std::set<std::string> names;
        std::size_t rows = 0;
        bool selfSeen = false;
        for (auto* response = root->children; response; response = response->next) {
            if (!dav(response, "response")) continue;
            const auto child = endpoint_->hrefPath(text(onlyChild(response, "href")));
            if (!selfSeen && child && *child == parent) {
                selfSeen = true;
                continue;
            }
            if (++rows > kMaxListRows) return std::nullopt;
            if (!child || child->size() != parent.size() + 1 ||
                !std::equal(parent.begin(), parent.end(), child->begin())) continue;
            if (auto entry = properties(response, child->back()); entry && names.insert(entry->name).second)
                entries.push_back(std::move(*entry));
        }
        return entries;
    }

    std::optional<File> open(std::string_view path) const override {
        auto url = endpoint_->requestUrl(path, false);
        if (!url) return std::nullopt;
        Transfer head(Transfer::Kind::Head);
        if (!perform(*endpoint_, *url, head)) return std::nullopt;
        const auto size = *head.length;
        return File{size, [endpoint = endpoint_, url = std::move(*url), size]
                    (std::uint64_t offset, std::uint64_t length, const Sink& sink) {
            if (offset > size) return false;
            length = std::min(length, size - offset);
            if (length == 0) return true;
            if (!sink) return false;
            Transfer transfer(Transfer::Kind::Read);
            transfer.requested = ContentRange{offset, offset + length - 1, size};
            transfer.sink = &sink;
            return perform(*endpoint, url, transfer);
        }};
    }

private:
    const std::shared_ptr<const Endpoint> endpoint_;
};

}  // namespace

std::shared_ptr<const Source> webDavSource(const std::string& baseUrl, const std::string& username,
                                          const std::string& password) {
    if (username.find(':') != username.npos || std::any_of(username.begin(), username.end(), control) ||
        std::any_of(password.begin(), password.end(), control) || !curlReady()) return nullptr;
    const auto address = httpsAddress(baseUrl);
    if (!address) return nullptr;
    auto baseSegments = uriPath(address->rawPath);
    if (!baseSegments) return nullptr;
    std::string base = "https://" + address->host;
    if (address->port != "443") base += ':' + address->port;
    base += address->rawPath;
    if (base.back() != '/') base.push_back('/');
    return std::make_shared<WebDavSource>(std::make_shared<const Endpoint>(
        Endpoint{address->host, address->port, std::move(base), std::move(*baseSegments), username, password}));
}

}  // namespace podcast::storage

#else

namespace podcast::storage {

std::shared_ptr<const Source> webDavSource(const std::string&, const std::string&, const std::string&) {
    return nullptr;
}

}  // namespace podcast::storage

#endif
