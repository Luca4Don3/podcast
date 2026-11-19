#ifndef PODCAST_HTTP_CLIENT_H
#define PODCAST_HTTP_CLIENT_H

#include <cstdint>
#include <string>
#include <vector>

namespace podcast {

struct HttpResponse {
    int status = 0;
    std::vector<unsigned char> body;
    std::string content_type;
};

// Only http/https are accepted. No userinfo. HTTPS certificate validation uses WinHTTP defaults.
// max_bytes bounds the in-memory response. Returns false on transport/validation failure.
bool http_get(const std::string& url, std::size_t max_bytes, HttpResponse& out);

// Stream a media file to disk with size limit; returns bytes written or -1.
// The caller owns the destination path and must commit via the cache core.
std::int64_t http_download(const std::string& url, const std::wstring& destination,
                           std::size_t max_bytes);

} // namespace podcast

#endif
