#include "util.hpp"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cstring>
#include <ctime>
#include <stdexcept>

namespace podcast::util {

namespace {
constexpr char kHex[] = "0123456789abcdef";

std::string bytesToHex(const unsigned char* data, std::size_t len) {
    std::string out;
    out.resize(len * 2);
    for (std::size_t i = 0; i < len; ++i) {
        out[i * 2] = kHex[data[i] >> 4];
        out[i * 2 + 1] = kHex[data[i] & 0x0F];
    }
    return out;
}

constexpr char kBase64Url[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
constexpr char kBase64Std[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64EncodeWith(std::string_view input, const char* table, bool padding) {
    std::string out;
    out.reserve((input.size() + 2) / 3 * 4);
    std::size_t i = 0;
    while (i + 2 < input.size()) {
        const auto b0 = static_cast<unsigned char>(input[i]);
        const auto b1 = static_cast<unsigned char>(input[i + 1]);
        const auto b2 = static_cast<unsigned char>(input[i + 2]);
        out.push_back(table[b0 >> 2]);
        out.push_back(table[((b0 & 0x03) << 4) | (b1 >> 4)]);
        out.push_back(table[((b1 & 0x0F) << 2) | (b2 >> 6)]);
        out.push_back(table[b2 & 0x3F]);
        i += 3;
    }
    if (i < input.size()) {
        const auto b0 = static_cast<unsigned char>(input[i]);
        const bool two = (i + 1 < input.size());
        const auto b1 = two ? static_cast<unsigned char>(input[i + 1]) : 0;
        out.push_back(table[b0 >> 2]);
        out.push_back(table[((b0 & 0x03) << 4) | (b1 >> 4)]);
        if (two) {
            out.push_back(table[((b1 & 0x0F) << 2)]);
            if (padding) out.push_back('=');
        } else {
            if (padding) {
                out.push_back('=');
                out.push_back('=');
            }
        }
    }
    return out;
}

int base64Value(char c, bool urlSafe) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (urlSafe && c == '-') return 62;
    if (urlSafe && c == '_') return 63;
    if (!urlSafe && c == '+') return 62;
    if (!urlSafe && c == '/') return 63;
    return -1;
}

std::string base64DecodeWith(std::string_view input, bool urlSafe) {
    std::string out;
    out.reserve(input.size() * 3 / 4);
    int buffer = 0;
    int bits = 0;
    for (char c : input) {
        if (c == '=' || c == '\n' || c == '\r') continue;
        const int v = base64Value(c, urlSafe);
        if (v < 0) continue;
        buffer = (buffer << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<char>((buffer >> bits) & 0xFF));
        }
    }
    return out;
}

}  // namespace

std::string randomHex(std::size_t bytes) {
    std::string buf(bytes, '\0');
    if (RAND_bytes(reinterpret_cast<unsigned char*>(buf.data()),
                   static_cast<int>(bytes)) != 1) {
        // 退化路径：时间戳哈希，仅保证唯一性
        const auto t = std::to_string(
            std::chrono::steady_clock::now().time_since_epoch().count());
        const auto digest = sha256Raw(t);
        std::string out;
        while (out.size() < bytes * 2) out += digest;
        return out.substr(0, bytes * 2);
    }
    return bytesToHex(reinterpret_cast<const unsigned char*>(buf.data()), bytes);
}

std::string sha256Raw(std::string_view input) {
    std::string out(32, '\0');
    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    unsigned int len = 0;
    EVP_DigestInit_ex(ctx, EVP_sha256(), nullptr);
    EVP_DigestUpdate(ctx, input.data(), input.size());
    EVP_DigestFinal_ex(ctx, reinterpret_cast<unsigned char*>(out.data()), &len);
    EVP_MD_CTX_free(ctx);
    out.resize(len);
    return out;
}

std::string sha256Hex(std::string_view input) {
    const auto raw = sha256Raw(input);
    return bytesToHex(reinterpret_cast<const unsigned char*>(raw.data()), raw.size());
}

std::string base64UrlEncode(std::string_view input) {
    return base64EncodeWith(input, kBase64Url, false);
}

std::string base64UrlDecode(std::string_view input) {
    return base64DecodeWith(input, true);
}

std::string base64Encode(std::string_view input) {
    return base64EncodeWith(input, kBase64Std, true);
}

std::string urlDecode(std::string_view input) {
    std::string out;
    out.reserve(input.size());
    for (std::size_t i = 0; i < input.size(); ++i) {
        const char c = input[i];
        if (c == '%' && i + 2 < input.size()) {
            const auto hex = input.substr(i + 1, 2);
            unsigned int value = 0;
            const auto res = std::from_chars(hex.data(), hex.data() + hex.size(), value, 16);
            if (res.ec == std::errc{}) {
                out.push_back(static_cast<char>(value));
                i += 2;
                continue;
            }
        }
        out.push_back(c == '+' ? ' ' : c);
    }
    return out;
}

std::string urlEncode(std::string_view input) {
    std::string out;
    out.reserve(input.size() * 3);
    for (unsigned char c : input) {
        const bool safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                          (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' ||
                          c == '~';
        if (safe) {
            out.push_back(static_cast<char>(c));
        } else {
            // 百分号编码使用大写十六进制（RFC 3986 推荐形式）
            static constexpr char kHexUpper[] = "0123456789ABCDEF";
            out.push_back('%');
            out.push_back(kHexUpper[c >> 4]);
            out.push_back(kHexUpper[c & 0x0F]);
        }
    }
    return out;
}

std::string toLower(std::string_view s) {
    std::string out(s);
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

std::string trim(std::string_view s) {
    std::size_t begin = 0;
    std::size_t end = s.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(s[begin]))) ++begin;
    while (end > begin && std::isspace(static_cast<unsigned char>(s[end - 1]))) --end;
    return std::string(s.substr(begin, end - begin));
}

std::vector<std::string> split(std::string_view s, char delim) {
    std::vector<std::string> out;
    std::size_t start = 0;
    while (start <= s.size()) {
        const auto pos = s.find(delim, start);
        if (pos == std::string_view::npos) {
            out.emplace_back(s.substr(start));
            break;
        }
        out.emplace_back(s.substr(start, pos - start));
        start = pos + 1;
    }
    return out;
}

bool startsWith(std::string_view s, std::string_view prefix) {
    return s.size() >= prefix.size() && s.substr(0, prefix.size()) == prefix;
}

bool endsWith(std::string_view s, std::string_view suffix) {
    return s.size() >= suffix.size() && s.substr(s.size() - suffix.size()) == suffix;
}

std::string join(const std::vector<std::string>& parts, std::string_view sep) {
    std::string out;
    for (std::size_t i = 0; i < parts.size(); ++i) {
        if (i) out += sep;
        out += parts[i];
    }
    return out;
}

std::string nowIso() {
    const auto now = std::chrono::system_clock::now();
    const auto secs = std::chrono::time_point_cast<std::chrono::seconds>(now);
    const auto nanos =
        std::chrono::duration_cast<std::chrono::nanoseconds>(now - secs).count();
    std::time_t t = std::chrono::system_clock::to_time_t(secs);
    std::tm tm{};
    gmtime_r(&t, &tm);
    char date[32];
    std::strftime(date, sizeof(date), "%Y-%m-%dT%H:%M:%S", &tm);
    char out[64];
    std::snprintf(out, sizeof(out), "%s.%09lldZ", date,
                  static_cast<long long>(nanos));
    return out;
}

std::int64_t nowUnix() {
    return std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

std::string normalizeTime(std::string_view input) {
    const auto v = trim(input);
    if (v.empty()) return nowIso();
    static const char* formats[] = {
        "%Y-%m-%dT%H:%M:%SZ",       "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    };
    for (const char* fmt : formats) {
        std::tm tm{};
        if (::strptime(v.c_str(), fmt, &tm) != nullptr) {
            const std::time_t t = timegm(&tm);
            std::tm out{};
            gmtime_r(&t, &out);
            char buf[32];
            std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &out);
            return buf;
        }
    }
    return nowIso();
}

int toInt(std::string_view s, int fallback) {
    int value = 0;
    const auto res = std::from_chars(s.data(), s.data() + s.size(), value);
    return res.ec == std::errc{} ? value : fallback;
}

std::int64_t toInt64(std::string_view s, std::int64_t fallback) {
    std::int64_t value = 0;
    const auto res = std::from_chars(s.data(), s.data() + s.size(), value);
    return res.ec == std::errc{} ? value : fallback;
}

bool isValidUtf8(std::string_view s) {
    std::size_t i = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        int extra = 0;
        if (c < 0x80) {
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3;
        } else {
            return false;
        }
        if (i + extra >= s.size() + 1) return false;
        for (int k = 1; k <= extra; ++k) {
            if (i + k >= s.size()) return false;
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) return false;
        }
        i += extra + 1;
    }
    return true;
}

std::string sanitizeUtf8(std::string_view s) {
    if (isValidUtf8(s)) return std::string(s);
    std::string out;
    out.reserve(s.size());
    for (char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        out.push_back(c < 0x80 ? ch : '?');
    }
    return out;
}

}  // namespace podcast::util
