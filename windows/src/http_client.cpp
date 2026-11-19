#include "http_client.h"

#include <windows.h>
#include <winhttp.h>
#include <cstdlib>

namespace podcast {
namespace {

struct ParsedUrl { std::wstring host, path; int port = 0; bool https = false; };

bool valid_url(const std::string& url, ParsedUrl& out) {
    if (url.empty() || url.find('@') != std::string::npos) return false;
    const bool http = url.compare(0, 7, "http://") == 0;
    const bool https = url.compare(0, 8, "https://") == 0;
    if (!http && !https) return false;
    out.https = https;
    const std::size_t scheme = https ? 8 : 7;
    const std::size_t slash = url.find('/', scheme);
    const std::string authority = url.substr(scheme, slash == std::string::npos ? std::string::npos : slash - scheme);
    if (authority.empty()) return false;
    const std::size_t colon = authority.rfind(':');
    std::string host = authority, port;
    if (colon != std::string::npos && authority.find(']') == std::string::npos) {
        host = authority.substr(0, colon); port = authority.substr(colon + 1);
    }
    if (host.empty()) return false;
    out.host.assign(host.begin(), host.end());
    out.path = L"/";
    if (slash != std::string::npos) {
        const std::string p = url.substr(slash);
        out.path.assign(p.begin(), p.end());
    }
    out.port = port.empty() ? (https ? 443 : 80) : std::atoi(port.c_str());
    return out.port > 0 && out.port <= 65535;
}

bool fetch(const std::string& url, std::size_t max_bytes, std::vector<unsigned char>* body,
            const std::wstring* destination, HttpResponse* response, std::size_t* bytes_written) {
    ParsedUrl target;
    if (!valid_url(url, target)) return false;
    HINTERNET session = WinHttpOpen(L"PodcastApp-Windows/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                   WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return false;
    HINTERNET connect = WinHttpConnect(session, target.host.c_str(),
                                       static_cast<INTERNET_PORT>(target.port), 0);
    if (!connect) { WinHttpCloseHandle(session); return false; }
    HINTERNET request = WinHttpOpenRequest(connect, L"GET", target.path.c_str(), nullptr,
                                           WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                           target.https ? WINHTTP_FLAG_SECURE : 0);
    if (!request) { WinHttpCloseHandle(connect); WinHttpCloseHandle(session); return false; }
    const bool sent = WinHttpSendRequest(request, L"Accept-Encoding: identity", 0,
                                         WINHTTP_NO_REQUEST_DATA, 0, 0, 0) != FALSE &&
                       WinHttpReceiveResponse(request, nullptr) != FALSE;
    if (!sent) { WinHttpCloseHandle(request); WinHttpCloseHandle(connect); WinHttpCloseHandle(session); return false; }
    DWORD status = 0, size = sizeof(status);
    WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX);
    if (response) response->status = static_cast<int>(status);
    if (status != 200) { WinHttpCloseHandle(request); WinHttpCloseHandle(connect); WinHttpCloseHandle(session); return false; }
    HANDLE file = INVALID_HANDLE_VALUE;
    if (destination) {
        file = CreateFileW(destination->c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) { WinHttpCloseHandle(request); WinHttpCloseHandle(connect); WinHttpCloseHandle(session); return false; }
    }
    std::size_t total = 0;
    bool ok = true;
    for (;;) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request, &available)) { ok = false; break; }
        if (available == 0) break;
        std::vector<unsigned char> chunk(available);
        DWORD read = 0;
        if (!WinHttpReadData(request, chunk.data(), available, &read)) { ok = false; break; }
        total += read;
        if (total > max_bytes) { ok = false; break; }
        if (body) body->insert(body->end(), chunk.begin(), chunk.begin() + read);
        if (file) { DWORD written = 0; WriteFile(file, chunk.data(), read, &written, nullptr); if (written != read) { ok = false; break; } }
    }
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    WinHttpCloseHandle(request); WinHttpCloseHandle(connect); WinHttpCloseHandle(session);
    if (bytes_written) *bytes_written = total;
    return ok && total > 0;
}

} // namespace

bool http_get(const std::string& url, std::size_t max_bytes, HttpResponse& out) {
    return fetch(url, max_bytes, &out.body, nullptr, &out, nullptr);
}

std::int64_t http_download(const std::string& url, const std::wstring& destination, std::size_t max_bytes) {
    std::size_t written = 0;
    return fetch(url, max_bytes, nullptr, &destination, nullptr, &written) ? static_cast<std::int64_t>(written) : -1;
}

} // namespace podcast
