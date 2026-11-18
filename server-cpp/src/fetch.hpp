#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace podcast::fetch {

enum class Scheme { Http, Https };

struct Url {
    Scheme scheme = Scheme::Http;
    std::string host;
    int port = 80;
    std::string path;   // 含 query
    bool valid = false;
};

Url parseUrl(std::string_view raw);

struct Result {
    bool ok = false;
    int status = 0;
    std::string body;
    std::string contentType;
    std::string etag;
    std::string lastModified;
    std::string error;
};

struct Options {
    std::string userAgent = "PodcastApp/1.0";
    std::size_t maxBytes = 20u * 1024u * 1024u;
    int timeoutSec = 20;
    int maxRedirects = 10;
    std::string etag;
    std::string lastModified;
};

// 支持 HTTP/HTTPS、重定向、条件请求；结果已在内存中
Result get(std::string_view url, const Options& options);

}  // namespace podcast::fetch
