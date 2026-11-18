#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <semaphore>
#include <stdexcept>
#include <string>
#include <string_view>

#include <openssl/rand.h>

#include "router.hpp"
#include "storage/source.hpp"
#include "util.hpp"

namespace podcast {
namespace {

using Clock = std::chrono::steady_clock;
constexpr auto kTicketTtl = std::chrono::hours(2);
constexpr std::size_t kMaxTickets = 512;
constexpr std::size_t kMaxPathBytes = 2048;

struct MediaSource {
    const char* label;
    std::shared_ptr<const storage::Source> connector;
};

struct Ticket {
    std::shared_ptr<const storage::Source> connector;
    std::string path;
    Clock::time_point until;
};

struct MediaState {
    explicit MediaState(int workers) : slots(std::clamp(workers / 2, 1, 8)) {}
    std::map<std::string, MediaSource> sources;
    std::map<std::string, Ticket> tickets;  // Stores digests, not reusable secrets.
    std::mutex mutex;
    std::counting_semaphore<8> slots;

    std::optional<Ticket> lookup(const std::string& secret) {
        if (secret.size() != 64 || secret.find_first_not_of("0123456789abcdef") != std::string::npos) return {};
        const std::lock_guard lock(mutex);
        const auto found = tickets.find(util::sha256Hex(secret));
        if (found == tickets.end() || found->second.until <= Clock::now()) return {};
        return found->second;
    }

    std::string issue(const std::shared_ptr<const storage::Source>& connector, std::string path) {
        std::array<unsigned char, 32> data{};
        if (RAND_bytes(data.data(), static_cast<int>(data.size())) != 1) return {};
        std::string secret;
        constexpr std::string_view digits = "0123456789abcdef";
        secret.reserve(64);
        for (const auto value : data) {
            secret += digits[value >> 4];
            secret += digits[value & 15];
        }
        const std::lock_guard lock(mutex);
        const auto now = Clock::now();
        std::erase_if(tickets, [now](const auto& item) { return item.second.until <= now; });
        if (tickets.size() >= kMaxTickets ||
            !tickets.emplace(util::sha256Hex(secret), Ticket{connector, std::move(path), now + kTicketTtl}).second) return {};
        return secret;
    }
};

// Release the permit after the response (including its streaming callback) is destroyed.
struct Permit {
    std::shared_ptr<MediaState> state;
    bool held;
    explicit Permit(std::shared_ptr<MediaState> s) : state(std::move(s)), held(state->slots.try_acquire()) {}
    ~Permit() { if (held) state->slots.release(); }
    Permit(const Permit&) = delete;
};

struct Range { std::uint64_t start; std::uint64_t count; bool partial; };

std::optional<std::uint64_t> number(std::string_view input) {
    std::uint64_t value = 0;
    const auto* end = input.data() + input.size();
    const auto result = std::from_chars(input.data(), end, value);
    if (input.empty() || result.ec != std::errc{} || result.ptr != end) return {};
    return value;
}

std::optional<Range> parseRange(std::string_view value, std::uint64_t size) {
    if (value.empty()) return Range{0, size, false};
    if (!value.starts_with("bytes=") || size == 0 || value.size() > 80) return {};
    value.remove_prefix(6);
    const auto dash = value.find('-');
    if (dash == std::string_view::npos) return {};
    const auto right = value.substr(dash + 1);
    if (dash == 0) {
        const auto suffix = number(right);
        if (!suffix || *suffix == 0) return {};
        const auto count = std::min(*suffix, size);
        return Range{size - count, count, true};
    }
    const auto start = number(value.substr(0, dash));
    if (!start || *start >= size) return {};
    if (right.empty()) return Range{*start, size - *start, true};
    const auto last = number(right);
    if (!last || *last < *start) return {};
    return Range{*start, std::min(*last, size - 1) - *start + 1, true};
}

const char* audioType(std::string_view path) {
    const auto pos = path.rfind('.');
    if (pos == std::string_view::npos || path.size() - pos > 6) return nullptr;
    const auto ext = util::toLower(path.substr(pos));
    if (ext == ".mp3") return "audio/mpeg";
    if (ext == ".m4a" || ext == ".m4b") return "audio/mp4";
    if (ext == ".aac") return "audio/aac";
    if (ext == ".wav") return "audio/wav";
    if (ext == ".flac") return "audio/flac";
    if (ext == ".ogg" || ext == ".oga" || ext == ".opus") return "audio/ogg";
    return nullptr;
}

bool pathValid(std::string_view path, bool allowRoot) {
    return path.size() <= kMaxPathBytes && util::isValidUtf8(path) && storage::validRelativePath(path, allowRoot);
}

bool failure(http::Response& res, int status, const char* code, const char* message) {
    AppContext::fail(res, status, code, message);
    return true;
}

bool authorized(const http::Request& req, AppContext& ctx, http::Response& res, AuthContext& auth) {
    res.headers["Cache-Control"] = "no-store";
    if (authenticate(req, ctx, auth, nullptr)) return true;
    AppContext::fail(res, 401, "unauthorized", "请先登录");
    return false;
}

}  // namespace

void registerMediaRoutes(http::Server& server, AppContext& ctx, int workers) {
    auto state = std::make_shared<MediaState>(workers);
    if (!ctx.config.fileMediaRoot.empty()) {
        auto source = storage::localSource(ctx.config.fileMediaRoot);
        if (!source) throw std::runtime_error("FILE_MEDIA_ROOT is not an accessible dedicated directory");
        state->sources.emplace("local", MediaSource{"NAS / 只读目录", std::move(source)});
    }
    if (!ctx.config.fileMediaDavUrl.empty()) {
        auto source = storage::webDavSource(ctx.config.fileMediaDavUrl,
            ctx.config.fileMediaDavUser, ctx.config.fileMediaDavPassword);
        if (!source) throw std::runtime_error("WebDAV is not enabled or FILE_MEDIA_DAV_URL is invalid");
        state->sources.emplace("webdav", MediaSource{"HTTPS WebDAV", std::move(source)});
    }

    server.addRoute("GET", "/api/v1/media/sources", [state, &ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        if (!authorized(req, ctx, res, auth)) return true;
        auto sources = Json::makeArray();
        if (auth.userId == ctx.config.fileMediaOwnerId) {
            for (const auto& [id, source] : state->sources) {
                sources.push(Json::makeObject().set("id", id).set("label", source.label));
            }
        }
        res.json(200, Json::makeObject().set("sources", sources));
        return true;
    });
    server.addRoute("GET", "/api/v1/media/entries", [state, &ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        if (!authorized(req, ctx, res, auth)) return true;
        const auto found = state->sources.find(req.queryParam("source"));
        if (auth.userId != ctx.config.fileMediaOwnerId || found == state->sources.end())
            return failure(res, 404, "source_missing", "音源未配置或无权访问");
        const auto path = req.queryParam("path");
        if (!pathValid(path, true)) return failure(res, 400, "invalid_path", "目录路径无效");
        const Permit permit(state);
        if (!permit.held) return failure(res, 503, "media_busy", "音源忙，请稍后重试");
        auto listed = found->second.connector->list(path);
        if (!listed) return failure(res, 502, "list_failed", "无法读取目录，可能不可用或条目太多");
        std::sort(listed->begin(), listed->end(), [](const storage::Entry& a, const storage::Entry& b) {
            if (a.directory != b.directory) return a.directory;
            return a.name < b.name;
        });
        auto entries = Json::makeArray();
        for (const auto& item : *listed) {
            if (item.name.find('/') != std::string::npos || !pathValid(item.name, false) ||
                item.size > (1ULL << 53) - 1 || (!item.directory && !audioType(item.name))) continue;
            entries.push(Json::makeObject().set("name", item.name).set("directory", item.directory)
                .set("size", static_cast<std::int64_t>(item.size)));
        }
        res.json(200, Json::makeObject().set("entries", entries));
        return true;
    });
    server.addRoute("POST", "/api/v1/media/ticket", [state, &ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        if (!authorized(req, ctx, res, auth)) return true;
        Json body;
        if (req.body.size() > 8192 || !Json::parse(req.body, body) || !body.isObject())
            return failure(res, 400, "invalid_body", "请求体无效");
        const auto found = state->sources.find(body.get("source").asString());
        if (auth.userId != ctx.config.fileMediaOwnerId || found == state->sources.end())
            return failure(res, 404, "source_missing", "音源未配置或无权访问");
        const auto path = body.get("path").asString();
        if (!pathValid(path, false) || !audioType(path)) return failure(res, 400, "invalid_file", "不支持该文件");
        const Permit permit(state);
        if (!permit.held) return failure(res, 503, "media_busy", "音源忙，请稍后重试");
        if (!found->second.connector->open(path)) return failure(res, 404, "file_missing", "音频不可读");
        const auto secret = state->issue(found->second.connector, path);
        if (secret.empty()) return failure(res, 503, "ticket_unavailable", "暂时无法生成播放许可");
        res.json(200, Json::makeObject().set("ticket", secret));
        return true;
    });
    const auto stream = [state](const http::Request& req, http::Response& res) {
        res.headers["Cache-Control"] = "private, no-store";
        res.headers["X-Content-Type-Options"] = "nosniff";
        auto ticket = state->lookup(req.queryParam("ticket"));
        if (!ticket) return failure(res, 403, "ticket_invalid", "播放许可无效或已过期，请重新选择文件");
        auto permit = std::make_shared<Permit>(state);
        if (!permit->held) return failure(res, 503, "media_busy", "音源忙，请稍后重试");
        auto file = ticket->connector->open(ticket->path);
        if (!file || !file->read) return failure(res, 404, "file_missing", "音频不可读");
        const auto range = parseRange(req.method == "GET" && req.header("if-range").empty() ? req.header("range") : "", file->size);
        res.headers["Accept-Ranges"] = "bytes";
        if (!range) {
            res.headers["Content-Range"] = "bytes */" + std::to_string(file->size);
            return failure(res, 416, "range_invalid", "音频范围无效或不受支持");
        }
        res.status = range->partial ? 206 : 200;
        res.contentType = audioType(ticket->path);
        if (range->partial) {
            res.headers["Content-Range"] = "bytes " + std::to_string(range->start) + "-" +
                std::to_string(range->start + range->count - 1) + "/" + std::to_string(file->size);
        }
        res.streamLength = range->count;
        res.streamBody = [file = std::move(*file), keep = std::move(permit), range = *range](const storage::Sink& sink) {
            (void)keep;
            return file.read(range.start, range.count, sink);
        };
        return true;
    };
    server.addRoute("GET", "/api/v1/media/stream", stream);
    server.addRoute("HEAD", "/api/v1/media/stream", stream);
}

}  // namespace podcast
