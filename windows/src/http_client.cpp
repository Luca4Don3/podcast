#include "http_client.h"

#include <windows.h>
#include <winhttp.h>
#include <array>
#include <string_view>

namespace podcast {
namespace {
struct ParsedUrl { std::wstring host, path; INTERNET_PORT port = 0; bool https = false; };
struct InternetHandle {
    HINTERNET value;
    ~InternetHandle() { if (value) WinHttpCloseHandle(value); }
};
struct FileHandle {
    HANDLE value = INVALID_HANDLE_VALUE;
    ~FileHandle() { if (value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};

bool parse_url(const std::string& url, ParsedUrl& out) {
    if (url.empty() || url.size() > 8192) return false;
    for (const unsigned char c : url) if (c <= 32 || c == 127 || c == '\\') return false;
    const auto scheme = url.find("://");
    if (scheme == std::string::npos) return false;
    const auto authority = std::string_view(url).substr(scheme + 3);
    if (authority.substr(0, authority.find_first_of("/?#")).find('@') != std::string_view::npos) return false;
    const auto length = static_cast<int>(url.size());
    const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, url.data(), length, nullptr, 0);
    if (!count) return false;
    std::wstring utf16(static_cast<size_t>(count), L'\0');
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, url.data(), length, utf16.data(), count)) return false;
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    parts.dwHostNameLength = parts.dwUrlPathLength = parts.dwExtraInfoLength = 1;
    if (!WinHttpCrackUrl(utf16.c_str(), 0, ICU_REJECT_USERPWD, &parts) || !parts.dwHostNameLength || !parts.nPort) return false;
    if (parts.nScheme != INTERNET_SCHEME_HTTP && parts.nScheme != INTERNET_SCHEME_HTTPS) return false;
    out.https = parts.nScheme == INTERNET_SCHEME_HTTPS;
    out.port = parts.nPort;
    out.host.assign(parts.lpszHostName, parts.dwHostNameLength);
    out.path = L"/";
    if (parts.dwUrlPathLength) out.path.assign(parts.lpszUrlPath, parts.dwUrlPathLength);
    if (parts.dwExtraInfoLength) out.path.append(parts.lpszExtraInfo, parts.dwExtraInfoLength);
    const auto fragment = out.path.find(L'#');
    if (fragment != std::wstring::npos) out.path.resize(fragment);
    return true;
}

std::int64_t fetch(const std::string& url, std::size_t maximum, HttpResponse* response,
                   const std::wstring* destination, const std::atomic_bool* cancel) {
    ParsedUrl target;
    if (!parse_url(url, target)) return -1;
    const auto deadline = GetTickCount64() + (destination ? 240000 : 20000);
    const auto stopped = [&] { return (cancel && cancel->load()) || GetTickCount64() >= deadline; };
    InternetHandle session{WinHttpOpen(L"PodcastApp-Windows/1.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                      WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0)};
    if (!session.value || !WinHttpSetTimeouts(session.value, 5000, 5000, 5000, 5000)) return -1;
    InternetHandle connection{WinHttpConnect(session.value, target.host.c_str(), target.port, 0)};
    if (!connection.value) return -1;
    InternetHandle request{WinHttpOpenRequest(connection.value, L"GET", target.path.c_str(), nullptr,
                           WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, target.https ? WINHTTP_FLAG_SECURE : 0)};
    if (!request.value) return -1;
    DWORD auth = WINHTTP_AUTOLOGON_SECURITY_LEVEL_HIGH;
    DWORD disabled = WINHTTP_DISABLE_AUTHENTICATION | WINHTTP_DISABLE_COOKIES;
    DWORD redirects = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    DWORD max_redirects = 5;
    if (!WinHttpSetOption(request.value, WINHTTP_OPTION_AUTOLOGON_POLICY, &auth, sizeof(auth)) ||
        !WinHttpSetOption(request.value, WINHTTP_OPTION_DISABLE_FEATURE, &disabled, sizeof(disabled)) ||
        !WinHttpSetOption(request.value, WINHTTP_OPTION_REDIRECT_POLICY, &redirects, sizeof(redirects)) ||
        !WinHttpSetOption(request.value, WINHTTP_OPTION_MAX_HTTP_AUTOMATIC_REDIRECTS, &max_redirects, sizeof(max_redirects)) || stopped()) return -1;
    if (!WinHttpSendRequest(request.value, L"Accept-Encoding: identity\r\n", DWORD(-1),
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0) || stopped() ||
        !WinHttpReceiveResponse(request.value, nullptr) || stopped()) return -1;
    DWORD status = 0, length = sizeof(status);
    if (!WinHttpQueryHeaders(request.value, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            WINHTTP_HEADER_NAME_BY_INDEX, &status, &length, WINHTTP_NO_HEADER_INDEX)) return -1;
    if (response) response->status = static_cast<int>(status);
    if (status != 200) return -1;
    FileHandle file;
    if (destination) {
        file.value = CreateFileW(destination->c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                                 FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file.value == INVALID_HANDLE_VALUE) return -1;
    }
    std::array<unsigned char, 16384> chunk{};
    std::size_t total = 0;
    while (!stopped()) {
        DWORD read = 0;
        if (!WinHttpReadData(request.value, chunk.data(), static_cast<DWORD>(chunk.size()), &read)) return -1;
        if (!read) return total && !stopped() ? static_cast<std::int64_t>(total) : -1;
        if (read > maximum - total) return -1;
        total += read;
        if (response) response->body.insert(response->body.end(), chunk.begin(), chunk.begin() + read);
        if (destination) {
            DWORD written = 0;
            if (!WriteFile(file.value, chunk.data(), read, &written, nullptr) || written != read) return -1;
        }
    }
    return -1;
}
} // namespace

bool valid_http_url(const std::string& url) {
    ParsedUrl ignored;
    return parse_url(url, ignored);
}

bool http_get(const std::string& url, std::size_t max_bytes, HttpResponse& out, const std::atomic_bool* cancel) {
    HttpResponse result;
    const bool ok = fetch(url, max_bytes, &result, nullptr, cancel) > 0;
    if (!ok) result.body.clear();
    out = std::move(result);
    return ok;
}

std::int64_t http_download(const std::string& url, const std::wstring& destination, std::size_t max_bytes) {
    return fetch(url, max_bytes, nullptr, &destination, nullptr);
}
} // namespace podcast
