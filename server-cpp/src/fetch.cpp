#include "fetch.hpp"

#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <cstring>

#ifdef PODCAST_ENABLE_SSL
#include <openssl/err.h>
#include <openssl/ssl.h>
#endif

#include "util.hpp"

namespace podcast::fetch {

namespace {

struct Connection {
    int fd = -1;
#ifdef PODCAST_ENABLE_SSL
    SSL_CTX* ctx = nullptr;
    SSL* ssl = nullptr;
#endif
    bool tls = false;

    ssize_t sendAll(const void* data, std::size_t len) {
        std::size_t sent = 0;
#ifdef PODCAST_ENABLE_SSL
        if (tls && ssl) {
            while (sent < len) {
                const int n = SSL_write(ssl, static_cast<const char*>(data) + sent,
                                       static_cast<int>(len - sent));
                if (n <= 0) return -1;
                sent += static_cast<std::size_t>(n);
            }
            return static_cast<ssize_t>(sent);
        }
#endif
        while (sent < len) {
            const ssize_t n = ::send(fd, static_cast<const char*>(data) + sent, len - sent,
                                     MSG_NOSIGNAL);
            if (n <= 0) return -1;
            sent += static_cast<std::size_t>(n);
        }
        return static_cast<ssize_t>(sent);
    }

    ssize_t receive(void* buffer, std::size_t len) {
#ifdef PODCAST_ENABLE_SSL
        if (tls && ssl) return SSL_read(ssl, buffer, static_cast<int>(len));
#endif
        return ::recv(fd, buffer, len, 0);
    }

    void close() {
#ifdef PODCAST_ENABLE_SSL
        if (ssl) { SSL_shutdown(ssl); SSL_free(ssl); ssl = nullptr; }
        if (ctx) { SSL_CTX_free(ctx); ctx = nullptr; }
#endif
        if (fd >= 0) { ::close(fd); fd = -1; }
    }
};

bool connectTo(const Url& url, int timeoutSec, Connection& conn, std::string& error) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* result = nullptr;
    const auto port = std::to_string(url.port);
    if (::getaddrinfo(url.host.c_str(), port.c_str(), &hints, &result) != 0) {
        error = "dns lookup failed for " + url.host;
        return false;
    }
    for (auto* rp = result; rp != nullptr; rp = rp->ai_next) {
        const int fd = ::socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (fd < 0) continue;
        timeval tv{timeoutSec, 0};
        ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        if (::connect(fd, rp->ai_addr, rp->ai_addrlen) == 0) {
            conn.fd = fd;
            break;
        }
        ::close(fd);
    }
    ::freeaddrinfo(result);
    if (conn.fd < 0) {
        error = "connect failed to " + url.host;
        return false;
    }
#ifdef PODCAST_ENABLE_SSL
    if (url.scheme == Scheme::Https) {
        SSL_library_init();
        SSL_load_error_strings();
        conn.ctx = SSL_CTX_new(TLS_client_method());
        if (!conn.ctx) { error = "tls context failed"; return false; }
        SSL_CTX_set_default_verify_paths(conn.ctx);
        SSL_CTX_set_verify(conn.ctx, SSL_VERIFY_PEER, nullptr);
        conn.ssl = SSL_new(conn.ctx);
        SSL_set_tlsext_host_name(conn.ssl, url.host.c_str());
        SSL_set_fd(conn.ssl, conn.fd);
        if (SSL_connect(conn.ssl) != 1) {
            error = "tls handshake failed";
            return false;
        }
        conn.tls = true;
    }
#else
    if (url.scheme == Scheme::Https) {
        error = "https not enabled in this build";
        return false;
    }
#endif
    return true;
}

std::string buildRequest(const Url& url, const Options& options) {
    std::string hostHeader = url.host;
    if ((url.scheme == Scheme::Http && url.port != 80) ||
        (url.scheme == Scheme::Https && url.port != 443)) {
        hostHeader += ":" + std::to_string(url.port);
    }
    std::string req;
    req += "GET " + url.path + " HTTP/1.1\r\n";
    req += "Host: " + hostHeader + "\r\n";
    req += "User-Agent: " + options.userAgent + "\r\n";
    req += "Accept: application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5\r\n";
    req += "Accept-Encoding: identity\r\n";
    req += "Connection: close\r\n";
    if (!options.etag.empty()) req += "If-None-Match: " + options.etag + "\r\n";
    if (!options.lastModified.empty()) {
        req += "If-Modified-Since: " + options.lastModified + "\r\n";
    }
    req += "\r\n";
    return req;
}

bool parseHeaders(std::string_view headerBlock, Result& result, std::string& location) {
    const auto firstLineEnd = headerBlock.find("\r\n");
    const auto statusLine = headerBlock.substr(0, firstLineEnd);
    const auto firstSpace = statusLine.find(' ');
    if (firstSpace == std::string_view::npos) {
        result.error = "bad status line";
        return false;
    }
    result.status = util::toInt(statusLine.substr(firstSpace + 1, 3), 0);

    std::size_t pos = (firstLineEnd == std::string_view::npos) ? headerBlock.size() : firstLineEnd + 2;
    bool chunked = false;
    while (pos < headerBlock.size()) {
        const auto lineEnd = headerBlock.find("\r\n", pos);
        const auto line = headerBlock.substr(
            pos, (lineEnd == std::string_view::npos ? headerBlock.size() : lineEnd) - pos);
        const auto colon = line.find(':');
        if (colon != std::string_view::npos) {
            const auto key = util::toLower(util::trim(line.substr(0, colon)));
            const auto value = util::trim(line.substr(colon + 1));
            if (key == "content-type") result.contentType = value;
            else if (key == "etag") result.etag = value;
            else if (key == "last-modified") result.lastModified = value;
            else if (key == "location") location = value;
            else if (key == "transfer-encoding" &&
                     util::toLower(value).find("chunked") != std::string::npos) {
                chunked = true;
            }
        }
        if (lineEnd == std::string_view::npos) break;
        pos = lineEnd + 2;
    }
    return chunked;
}

std::string decodeChunked(const std::string& body, std::size_t maxBytes) {
    std::string decoded;
    std::size_t cursor = 0;
    while (cursor < body.size()) {
        const auto sizeEnd = body.find("\r\n", cursor);
        if (sizeEnd == std::string::npos) break;
        const auto sizeToken = body.substr(cursor, sizeEnd - cursor);
        const auto size = static_cast<std::size_t>(std::strtoul(sizeToken.c_str(), nullptr, 16));
        if (size == 0) break;
        if (sizeEnd + 2 + size > body.size()) break;
        decoded += body.substr(sizeEnd + 2, size);
        cursor = sizeEnd + 2 + size + 2;
        if (decoded.size() > maxBytes) break;
    }
    return decoded;
}

}  // namespace

Url parseUrl(std::string_view raw) {
    Url url;
    auto text = util::trim(raw);
    if (util::startsWith(util::toLower(text), "https://")) {
        url.scheme = Scheme::Https;
        url.port = 443;
        text = text.substr(8);
    } else if (util::startsWith(util::toLower(text), "http://")) {
        url.scheme = Scheme::Http;
        url.port = 80;
        text = text.substr(7);
    } else {
        return url;
    }
    const auto slash = text.find('/');
    std::string authority = (slash == std::string::npos) ? text : text.substr(0, slash);
    url.path = (slash == std::string::npos) ? "/" : text.substr(slash);
    const auto at = authority.rfind('@');
    if (at != std::string::npos) authority = authority.substr(at + 1);
    const auto colon = authority.rfind(':');
    if (colon != std::string::npos) {
        url.host = authority.substr(0, colon);
        url.port = util::toInt(authority.substr(colon + 1), url.port);
    } else {
        url.host = authority;
    }
    url.valid = !url.host.empty();
    return url;
}

Result get(std::string_view urlText, const Options& options) {
    Result result;
    std::string current(urlText);
    for (int redirect = 0; redirect <= options.maxRedirects; ++redirect) {
        const auto url = parseUrl(current);
        if (!url.valid) {
            result.error = "invalid url";
            return result;
        }
        Connection conn;
        if (!connectTo(url, options.timeoutSec, conn, result.error)) {
            conn.close();
            return result;
        }
        const auto request = buildRequest(url, options);
        if (conn.sendAll(request.data(), request.size()) < 0) {
            result.error = "send failed";
            conn.close();
            return result;
        }

        std::string raw;
        char buffer[8192];
        while (true) {
            const ssize_t n = conn.receive(buffer, sizeof(buffer));
            if (n <= 0) break;
            raw.append(buffer, static_cast<std::size_t>(n));
            if (raw.size() > options.maxBytes + 65536) break;
        }
        conn.close();

        const auto headerEnd = raw.find("\r\n\r\n");
        if (headerEnd == std::string::npos) {
            result.error = "incomplete response";
            return result;
        }
        std::string location;
        const bool chunked = parseHeaders(std::string_view(raw).substr(0, headerEnd), result, location);
        auto body = raw.substr(headerEnd + 4);
        if (chunked) body = decodeChunked(body, options.maxBytes);
        if (body.size() > options.maxBytes) body.resize(options.maxBytes);
        result.body = std::move(body);
        result.ok = true;

        if ((result.status == 301 || result.status == 302 || result.status == 303 ||
             result.status == 307 || result.status == 308) && !location.empty()) {
            current = location;
            result.body.clear();
            continue;
        }
        return result;
    }
    result.error = "too many redirects";
    return result;
}

}  // namespace podcast::fetch
