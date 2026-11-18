#include "config.hpp"

#include <cstdlib>
#include <fstream>
#include <sstream>

#include "util.hpp"

namespace podcast {

namespace {
const char* getEnv(const char* key) {
    const char* v = std::getenv(key);
    return (v && *v) ? v : nullptr;
}

std::int64_t parseDuration(const char* raw, std::int64_t fallback) {
    if (!raw || !*raw) return fallback;
    const std::string s = podcast::util::trim(raw);
    if (s.empty()) return fallback;
    std::int64_t value = 0;
    char unit = 's';
    std::size_t i = 0;
    while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
        value = value * 10 + (s[i] - '0');
        ++i;
    }
    if (i < s.size()) unit = s[i];
    switch (unit) {
        case 's': return value;
        case 'm': return value * 60;
        case 'h': return value * 3600;
        case 'd': return value * 86400;
        default: return fallback;
    }
}
}  // namespace

std::string Config::loadFromEnv(Config& out) {
    if (const char* v = getEnv("APP_ADDR")) out.addr = v;
    if (const char* v = getEnv("APP_ENV")) out.env = v;
    if (const char* v = getEnv("APP_DATA_DIR")) out.dataDir = v;
    if (const char* v = getEnv("DATABASE_PATH")) out.databasePath = v;
    if (const char* v = getEnv("PUBLIC_BASE_URL")) out.publicBaseUrl = v;
    if (const char* v = getEnv("JWT_SECRET")) out.jwtSecret = v;
    if (const char* v = getEnv("SIGNUP_INVITE_CODE")) out.inviteCode = v;
    if (const char* v = getEnv("RSS_USER_AGENT")) out.rssUserAgent = v;
    // 不能使用忽略空串的 getEnv：重用 Config 时，空值也必须清除旧目录。
    const char* itunesUrl = std::getenv("ITUNES_SEARCH_URL");
    const char* radioUrl = std::getenv("RADIO_BROWSER_URL");
    out.itunesSearchUrl = itunesUrl ? util::trim(itunesUrl) : "";
    out.radioBrowserUrl = radioUrl ? util::trim(radioUrl) : "";
    const auto mediaSetting = [](const char* key) -> std::string {
        const char* value = getEnv(key);
        return value ? util::trim(value) : "";
    };
    out.fileMediaOwnerId = mediaSetting("FILE_MEDIA_OWNER_ID");
    out.fileMediaRoot = mediaSetting("FILE_MEDIA_ROOT");
    out.fileMediaDavUrl = mediaSetting("FILE_MEDIA_DAV_URL");
    out.fileMediaDavUser = mediaSetting("FILE_MEDIA_DAV_USER");
    out.fileMediaDavPassword.clear();
    if (const auto* secretFile = getEnv("FILE_MEDIA_DAV_PASSWORD_FILE"); secretFile && !out.fileMediaDavUrl.empty()) {
        std::ifstream secret(secretFile, std::ios::binary);
        char value[4097];
        secret.read(value, sizeof(value));
        if (secret.bad() || secret.gcount() <= 0 || secret.gcount() >= static_cast<std::streamsize>(sizeof(value)))
            return "config: invalid FILE_MEDIA_DAV_PASSWORD_FILE";
        out.fileMediaDavPassword.assign(value, static_cast<std::size_t>(secret.gcount()));
        if (out.fileMediaDavPassword.ends_with('\n')) out.fileMediaDavPassword.pop_back();
        if (out.fileMediaDavPassword.ends_with('\r')) out.fileMediaDavPassword.pop_back();
        if (out.fileMediaDavPassword.empty() || out.fileMediaDavPassword.find_first_of(std::string("\0\r\n", 3)) != std::string::npos)
            return "config: invalid WebDAV password file contents";
    }
    if ((!out.fileMediaRoot.empty() || !out.fileMediaDavUrl.empty()) &&
        (out.fileMediaOwnerId.empty() || out.fileMediaOwnerId.size() > 128)) return "config: FILE_MEDIA_OWNER_ID is required";
    if (!out.fileMediaDavUrl.empty() && (out.fileMediaDavUser.empty() != out.fileMediaDavPassword.empty()))
        return "config: WebDAV basic authentication needs username and password file";

    out.accessTokenTtlSec =
        parseDuration(getEnv("ACCESS_TOKEN_TTL"), out.accessTokenTtlSec);
    out.refreshTokenTtlSec =
        parseDuration(getEnv("REFRESH_TOKEN_TTL"), out.refreshTokenTtlSec);
    if (const char* v = getEnv("RSS_MAX_BYTES")) {
        out.rssMaxBytes = static_cast<std::size_t>(util::toInt64(v, 20 << 20));
    }
    if (const char* v = getEnv("RSS_TIMEOUT")) {
        out.rssTimeoutSec = util::toInt(v, 20);
    }
    if (const char* v = getEnv("WORKER_THREADS")) {
        out.workerThreads = util::toInt(v, 0);
    }
    if (const char* v = getEnv("CORS_ALLOWED_ORIGINS")) {
        out.allowedOrigins.clear();
        for (auto& item : util::split(v, ',')) {
            const auto trimmed = util::trim(item);
            if (!trimmed.empty()) out.allowedOrigins.push_back(trimmed);
        }
        if (out.allowedOrigins.empty()) out.allowedOrigins.push_back("*");
    }

    if (out.jwtSecret.empty()) {
        if (out.env == "production") {
            return "config: JWT_SECRET is required in production";
        }
        out.jwtSecret = "dev-only-insecure-secret-change-me";
    }
    if (out.jwtSecret.size() < 16) {
        return "config: JWT_SECRET too short, need >= 16 bytes";
    }
    if (out.databasePath.empty()) {
        out.databasePath = out.dataDir + "/podcast.db";
    }
    return {};
}

std::pair<std::string, int> Config::listenAddress() const {
    std::string host = "0.0.0.0";
    int port = 8080;
    const auto pos = addr.rfind(':');
    if (pos != std::string::npos) {
        const auto h = addr.substr(0, pos);
        if (!h.empty()) host = h;
        if (host == "::" || host == "[::]") host = "::";
        port = util::toInt(addr.substr(pos + 1), 8080);
    } else {
        port = util::toInt(addr, 8080);
    }
    return {host, port};
}

bool loadDotEnv(const std::string& path) {
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        auto text = util::trim(line);
        if (text.empty() || text[0] == '#') continue;
        if (util::startsWith(text, "export ")) text = util::trim(text.substr(7));
        const auto eq = text.find('=');
        if (eq == std::string::npos) continue;
        const auto key = util::trim(text.substr(0, eq));
        auto value = util::trim(text.substr(eq + 1));
        if (value.size() >= 2 &&
            ((value.front() == '"' && value.back() == '"') ||
             (value.front() == '\'' && value.back() == '\''))) {
            value = value.substr(1, value.size() - 2);
        }
        if (key.empty()) continue;
        if (std::getenv(key.c_str()) == nullptr) {
            setenv(key.c_str(), value.c_str(), 0);
        }
    }
    return true;
}

}  // namespace podcast
