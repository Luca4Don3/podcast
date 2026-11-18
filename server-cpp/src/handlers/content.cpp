#include <string>
#include <vector>

#include "crypto.hpp"
#include "fetch.hpp"
#include "router.hpp"
#include "util.hpp"
#include "xml.hpp"

namespace podcast {

namespace {

bool parseBody(const http::Request& req, Json& out, http::Response& res) {
    if (req.body.empty()) {
        out = Json::makeObject();
        return true;
    }
    std::string err;
    if (!Json::parse(req.body, out, &err)) {
        AppContext::fail(res, 400, "invalid_body", "请求体格式错误");
        return false;
    }
    return true;
}

struct EpisodeDraft {
    std::string guid;
    std::string title;
    std::string description;
    std::string audioUrl;
    std::string publishedAt;
    std::string imageUrl;
    int durationSeconds = 0;
    int episodeNumber = 0;
    int seasonNumber = 0;
};

struct FeedDraft {
    std::string title;
    std::string author;
    std::string description;
    std::string imageUrl;
    std::string link;
    std::string language;
    std::string category;
    bool explicitFlag = false;
    std::vector<EpisodeDraft> episodes;
};

int parseDuration(std::string_view value) {
    const auto text = util::trim(value);
    if (text.empty()) return 0;
    if (text.find(':') != std::string::npos) {
        int total = 0;
        for (const auto& part : util::split(text, ':')) {
            total = total * 60 + util::toInt(util::trim(part), 0);
        }
        return total;
    }
    return util::toInt(text, 0);
}

std::string parsePubDate(std::string_view value) {
    const auto text = util::trim(value);
    if (text.empty()) return util::nowIso();
    static const char* formats[] = {
        "%a, %d %b %Y %H:%M:%S %Z", "%a, %d %b %Y %H:%M:%S %z",
        "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d",
    };
    for (const char* fmt : formats) {
        std::tm tm{};
        if (::strptime(text.c_str(), fmt, &tm) != nullptr) {
            const std::time_t t = timegm(&tm);
            std::tm out{};
            gmtime_r(&t, &out);
            char buf[32];
            std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &out);
            return buf;
        }
    }
    return util::nowIso();
}

bool parseFeed(const std::string& raw, const std::string& contentType, FeedDraft& out,
               std::string* error) {
    const auto utf8 = xml::toUtf8(raw, contentType);
    auto result = xml::parse(utf8);
    if (!result.ok) {
        if (error) *error = result.error;
        return false;
    }
    const auto& root = result.root;
    const auto* channel = root.child("channel");
    if (!channel) {
        if (error) *error = "no channel element";
        return false;
    }
    out.title = channel->childText("title");
    if (out.title.empty()) out.title = channel->childText("itunes:title");
    out.author = channel->childText("itunes:author");
    if (out.author.empty()) out.author = channel->childText("author");
    out.description = channel->childText("description");
    if (out.description.empty()) out.description = channel->childText("itunes:summary");
    out.link = channel->childText("link");
    out.language = channel->childText("language");
    out.category = channel->childText("itunes:category");
    const auto explicitText = util::toLower(channel->childText("itunes:explicit"));
    out.explicitFlag = explicitText == "yes" || explicitText == "true" || explicitText == "1";
    if (const auto* image = channel->child("itunes:image")) {
        out.imageUrl = image->attr("href");
    }
    if (out.imageUrl.empty()) {
        if (const auto* image = channel->child("image")) {
            out.imageUrl = image->childText("url");
        }
    }

    for (const auto* item : channel->childrenNamed("item")) {
        EpisodeDraft episode;
        for (const auto* enc : item->childrenNamed("enclosure")) {
            const auto type = util::toLower(enc->attr("type"));
            const auto url = util::trim(enc->attr("url"));
            if (url.empty()) continue;
            if (type.empty() || util::startsWith(type, "audio/") || util::startsWith(type, "video/mp4")) {
                episode.audioUrl = url;
                break;
            }
            if (episode.audioUrl.empty()) episode.audioUrl = url;
        }
        if (episode.audioUrl.empty()) continue;
        episode.guid = util::trim(item->childText("guid"));
        if (episode.guid.empty()) episode.guid = episode.audioUrl;
        episode.title = item->childText("itunes:title");
        if (episode.title.empty()) episode.title = item->childText("title");
        if (episode.title.empty()) episode.title = "未命名单集";
        episode.description = item->childText("itunes:summary");
        if (episode.description.empty()) episode.description = item->childText("description");
        episode.publishedAt = parsePubDate(item->childText("pubDate"));
        episode.durationSeconds = parseDuration(item->childText("itunes:duration"));
        episode.episodeNumber = util::toInt(item->childText("itunes:episode"), 0);
        episode.seasonNumber = util::toInt(item->childText("itunes:season"), 0);
        if (const auto* image = item->child("itunes:image")) {
            episode.imageUrl = image->attr("href");
        }
        out.episodes.push_back(std::move(episode));
    }
    return true;
}

Json podcastJson(const std::vector<std::string>& row, bool subscribed = false) {
    // id, feed_url, title, author, description, image_url, link, language, category,
    // explicit, last_fetched_at, created_at, updated_at
    return Json::makeObject()
        .set("id", row.size() > 0 ? row[0] : "")
        .set("feedUrl", row.size() > 1 ? row[1] : "")
        .set("title", row.size() > 2 ? row[2] : "")
        .set("author", row.size() > 3 ? row[3] : "")
        .set("description", row.size() > 4 ? row[4] : "")
        .set("imageUrl", row.size() > 5 ? row[5] : "")
        .set("link", row.size() > 6 ? row[6] : "")
        .set("language", row.size() > 7 ? row[7] : "")
        .set("category", row.size() > 8 ? row[8] : "")
        .set("explicit", row.size() > 9 && row[9] == "1")
        .set("lastFetchedAt", row.size() > 10 ? row[10] : "")
        .set("createdAt", row.size() > 11 ? row[11] : "")
        .set("updatedAt", row.size() > 12 ? row[12] : "")
        .set("subscribed", subscribed);
}

Json episodeJson(const std::vector<std::string>& row) {
    // id, podcast_id, guid, title, description, audio_url, duration_seconds,
    // published_at, image_url, episode_number, season_number
    return Json::makeObject()
        .set("id", row.size() > 0 ? row[0] : "")
        .set("podcastId", row.size() > 1 ? row[1] : "")
        .set("guid", row.size() > 2 ? row[2] : "")
        .set("title", row.size() > 3 ? row[3] : "")
        .set("description", row.size() > 4 ? row[4] : "")
        .set("audioUrl", row.size() > 5 ? row[5] : "")
        .set("durationSeconds", row.size() > 6 ? util::toInt(row[6], 0) : 0)
        .set("publishedAt", row.size() > 7 ? row[7] : "")
        .set("imageUrl", row.size() > 8 ? row[8] : "")
        .set("episodeNumber", row.size() > 9 ? util::toInt(row[9], 0) : 0)
        .set("seasonNumber", row.size() > 10 ? util::toInt(row[10], 0) : 0);
}

constexpr const char* kPodcastColumns =
    "id, feed_url, title, author, description, image_url, link, language, category, explicit, "
    "last_fetched_at, created_at, updated_at";

constexpr const char* kEpisodeColumns =
    "id, podcast_id, guid, title, description, audio_url, duration_seconds, published_at, "
    "image_url, episode_number, season_number";

}  // namespace

void registerContentRoutes(http::Server& server, AppContext& ctx) {
    // ---- 播客列表 ----
    server.addRoute("GET", "/api/v1/podcasts", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto scope = req.queryParam("scope");
        if (scope == "subscribed") {
            const auto rows = ctx.db.query(
                "SELECT p.id, p.feed_url, p.title, p.author, p.description, p.image_url, p.link, "
                "p.language, p.category, p.explicit, p.last_fetched_at, p.created_at, p.updated_at "
                "FROM subscriptions s JOIN podcasts p ON p.id = s.podcast_id "
                "WHERE s.user_id = ? AND s.deleted = 0 AND p.deleted = 0 ORDER BY s.created_at DESC",
                {auth.userId});
            Json items = Json::makeArray();
            for (const auto& row : rows) items.push(podcastJson(row, true));
            res.json(200, Json::makeObject().set("items", items).set("total", static_cast<int>(rows.size())));
            return true;
        }

        int limit = util::toInt(req.queryParam("limit", "50"), 50);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        int offset = util::toInt(req.queryParam("offset", "0"), 0);
        if (offset < 0) offset = 0;

        std::string where = " WHERE deleted = 0";
        std::vector<std::string> params;
        const auto q = req.queryParam("q");
        if (!q.empty()) {
            where += " AND (title LIKE ? OR author LIKE ? OR description LIKE ?)";
            const auto like = "%" + q + "%";
            params.insert(params.end(), {like, like, like});
        }
        const auto category = req.queryParam("category");
        if (!category.empty() && category != "all") {
            where += " AND category = ?";
            params.push_back(category);
        }
        const auto total =
            ctx.db.queryScalar("SELECT COUNT(*) FROM podcasts" + where, params).value_or("0");
        auto pageParams = params;
        pageParams.push_back(std::to_string(limit));
        pageParams.push_back(std::to_string(offset));
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kPodcastColumns + " FROM podcasts" + where +
                " ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?",
            pageParams);
        Json items = Json::makeArray();
        for (const auto& row : rows) items.push(podcastJson(row));
        res.json(200, Json::makeObject()
                          .set("items", items)
                          .set("total", util::toInt(total, 0))
                          .set("limit", limit)
                          .set("offset", offset));
        return true;
    });

    // ---- 添加 RSS ----
    server.addRoute("POST", "/api/v1/podcasts", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto feedUrl = util::trim(body.get("feedUrl").asString(""));
        if (!util::startsWith(feedUrl, "http://") && !util::startsWith(feedUrl, "https://")) {
            AppContext::fail(res, 400, "invalid_feed_url", "请提供 http(s) 订阅源地址");
            return true;
        }

        // 已有缓存时取 etag
        const auto existing = ctx.db.query(
            "SELECT id, etag, last_modified FROM podcasts WHERE feed_url = ? AND deleted = 0",
            {feedUrl});
        fetch::Options options;
        options.userAgent = ctx.config.rssUserAgent;
        options.maxBytes = ctx.config.rssMaxBytes;
        options.timeoutSec = ctx.config.rssTimeoutSec;
        if (!existing.empty()) {
            options.etag = existing.front()[1];
            options.lastModified = existing.front()[2];
        }

        auto fetched = fetch::get(feedUrl, options);
        if (!fetched.ok && !existing.empty()) {
            // 离线回退到缓存
            const auto rows = ctx.db.query(
                std::string("SELECT ") + kPodcastColumns + " FROM podcasts WHERE id = ?",
                {existing.front()[0]});
            res.json(200, Json::makeObject().set("podcast", podcastJson(rows.front(), true)));
            return true;
        }
        if (!fetched.ok) {
            AppContext::fail(res, 502, "feed_unreachable", "订阅源抓取失败：" + fetched.error);
            return true;
        }
        if (fetched.status == 304 && !existing.empty()) {
            const auto rows = ctx.db.query(
                std::string("SELECT ") + kPodcastColumns + " FROM podcasts WHERE id = ?",
                {existing.front()[0]});
            res.json(200, Json::makeObject().set("podcast", podcastJson(rows.front(), true)));
            return true;
        }
        if (fetched.status != 200) {
            AppContext::fail(res, 502, "feed_unreachable", "订阅源返回 " + std::to_string(fetched.status));
            return true;
        }

        FeedDraft feed;
        if (!parseFeed(fetched.body, fetched.contentType, feed, &error)) {
            AppContext::fail(res, 400, "invalid_feed", "订阅源解析失败：" + error);
            return true;
        }

        const auto now = util::nowIso();
        std::string podcastId = existing.empty() ? newId() : existing.front()[0];
        ctx.db.run(
            "INSERT INTO podcasts (id, feed_url, title, author, description, image_url, link, language, "
            "category, explicit, etag, last_modified, last_fetched_at, created_at, updated_at, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
            "ON CONFLICT(feed_url) DO UPDATE SET title = excluded.title, author = excluded.author, "
            "description = excluded.description, image_url = excluded.image_url, link = excluded.link, "
            "language = excluded.language, category = excluded.category, explicit = excluded.explicit, "
            "etag = excluded.etag, last_modified = excluded.last_modified, "
            "last_fetched_at = excluded.last_fetched_at, updated_at = excluded.updated_at, deleted = 0",
            {podcastId, feedUrl, feed.title, feed.author, feed.description, feed.imageUrl,
             feed.link, feed.language, feed.category, feed.explicitFlag ? "1" : "0",
             fetched.etag, fetched.lastModified, now, now, now});

        const auto saved = ctx.db.query("SELECT id FROM podcasts WHERE feed_url = ?", {feedUrl});
        if (!saved.empty()) podcastId = saved.front()[0];

        for (const auto& episode : feed.episodes) {
            ctx.db.run(
                "INSERT INTO episodes (id, podcast_id, guid, title, description, audio_url, "
                "duration_seconds, published_at, image_url, episode_number, season_number, "
                "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                "ON CONFLICT(podcast_id, guid) DO UPDATE SET title = excluded.title, "
                "description = excluded.description, audio_url = excluded.audio_url, "
                "duration_seconds = excluded.duration_seconds, published_at = excluded.published_at, "
                "image_url = excluded.image_url, episode_number = excluded.episode_number, "
                "season_number = excluded.season_number, updated_at = excluded.updated_at, deleted = 0",
                {newId(), podcastId, episode.guid, episode.title, episode.description,
                 episode.audioUrl, std::to_string(episode.durationSeconds), episode.publishedAt,
                 episode.imageUrl.empty() ? feed.imageUrl : episode.imageUrl,
                 std::to_string(episode.episodeNumber), std::to_string(episode.seasonNumber),
                 now, now});
        }

        ctx.db.run(
            "INSERT INTO subscriptions (id, user_id, podcast_id, notify, created_at, updated_at, deleted) "
            "VALUES (?, ?, ?, 1, ?, ?, 0) "
            "ON CONFLICT(user_id, podcast_id) DO UPDATE SET deleted = 0, updated_at = excluded.updated_at",
            {newId(), auth.userId, podcastId, now, now});

        const auto rows = ctx.db.query(
            std::string("SELECT ") + kPodcastColumns + " FROM podcasts WHERE id = ?", {podcastId});
        res.json(200, Json::makeObject().set("podcast", podcastJson(rows.front(), true)));
        return true;
    });

    // ---- 播客详情 ----
    server.addRoute("GET", "/api/v1/podcasts/:id", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = http::splitPath(req.path);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto id = parts[3];
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kPodcastColumns + " FROM podcasts WHERE id = ? AND deleted = 0",
            {id});
        if (rows.empty()) {
            AppContext::fail(res, 404, "not_found", "节目不存在");
            return true;
        }
        const auto sub = ctx.db.queryScalar(
            "SELECT COUNT(*) FROM subscriptions WHERE user_id = ? AND podcast_id = ? AND deleted = 0",
            {auth.userId, id});
        res.json(200, podcastJson(rows.front(), util::toInt(sub.value_or("0"), 0) > 0));
        return true;
    });

    // ---- 单集列表 ----
    server.addRoute("GET", "/api/v1/podcasts/:id/episodes",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = http::splitPath(req.path);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto id = parts[3];
        int limit = util::toInt(req.queryParam("limit", "30"), 30);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        int offset = util::toInt(req.queryParam("offset", "0"), 0);
        if (offset < 0) offset = 0;

        const auto total = ctx.db.queryScalar(
            "SELECT COUNT(*) FROM episodes WHERE podcast_id = ? AND deleted = 0", {id});
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kEpisodeColumns +
                " FROM episodes WHERE podcast_id = ? AND deleted = 0 "
                "ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?",
            {id, std::to_string(limit), std::to_string(offset)});
        Json items = Json::makeArray();
        for (const auto& row : rows) items.push(episodeJson(row));
        res.json(200, Json::makeObject()
                          .set("items", items)
                          .set("total", util::toInt(total.value_or("0"), 0))
                          .set("limit", limit)
                          .set("offset", offset));
        return true;
    });

    // ---- 单集详情 ----
    server.addRoute("GET", "/api/v1/episodes/:id", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = http::splitPath(req.path);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kEpisodeColumns + " FROM episodes WHERE id = ? AND deleted = 0",
            {parts[3]});
        if (rows.empty()) {
            AppContext::fail(res, 404, "not_found", "单集不存在");
            return true;
        }
        res.json(200, Json::makeObject().set("episode", episodeJson(rows.front())));
        return true;
    });

    // ---- 刷新单集 ----
    server.addRoute("POST", "/api/v1/podcasts/:id/refresh",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = http::splitPath(req.path);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto id = parts[3];
        const auto rows = ctx.db.query(
            "SELECT feed_url FROM podcasts WHERE id = ? AND deleted = 0", {id});
        if (rows.empty()) {
            AppContext::fail(res, 404, "not_found", "节目不存在");
            return true;
        }
        // 直接复用添加接口的逻辑：清空 etag 后重新抓取
        ctx.db.run("UPDATE podcasts SET etag = '', last_modified = '' WHERE id = ?", {id});
        fetch::Options options;
        options.userAgent = ctx.config.rssUserAgent;
        options.maxBytes = ctx.config.rssMaxBytes;
        options.timeoutSec = ctx.config.rssTimeoutSec;
        auto fetched = fetch::get(rows.front()[0], options);
        if (!fetched.ok || fetched.status != 200) {
            AppContext::fail(res, 502, "feed_unreachable", "刷新失败");
            return true;
        }
        FeedDraft feed;
        if (!parseFeed(fetched.body, fetched.contentType, feed, &error)) {
            AppContext::fail(res, 400, "invalid_feed", "订阅源解析失败");
            return true;
        }
        const auto now = util::nowIso();
        for (const auto& episode : feed.episodes) {
            ctx.db.run(
                "INSERT INTO episodes (id, podcast_id, guid, title, description, audio_url, "
                "duration_seconds, published_at, image_url, episode_number, season_number, "
                "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                "ON CONFLICT(podcast_id, guid) DO UPDATE SET title = excluded.title, "
                "audio_url = excluded.audio_url, duration_seconds = excluded.duration_seconds, "
                "updated_at = excluded.updated_at, deleted = 0",
                {newId(), id, episode.guid, episode.title, episode.description, episode.audioUrl,
                 std::to_string(episode.durationSeconds), episode.publishedAt,
                 episode.imageUrl.empty() ? feed.imageUrl : episode.imageUrl,
                 std::to_string(episode.episodeNumber), std::to_string(episode.seasonNumber),
                 now, now});
        }
        ctx.db.run("UPDATE podcasts SET last_fetched_at = ?, updated_at = ? WHERE id = ?",
                   {now, now, id});
        const auto saved = ctx.db.query(
            std::string("SELECT ") + kPodcastColumns + " FROM podcasts WHERE id = ?", {id});
        res.json(200, Json::makeObject().set("podcast", podcastJson(saved.front(), true)));
        return true;
    });

    // ---- 发现（管理员显式配置的兼容目录，默认禁用）----
    server.addRoute("GET", "/api/v1/discover/search",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto endpoint = util::trim(ctx.config.itunesSearchUrl);
        if (endpoint.empty()) {
            res.json(200, Json::makeObject().set("items", Json::makeArray())
                              .set("total", 0).set("enabled", false)
                              .set("message", "播客目录未启用，请手动导入有权使用的 RSS 地址"));
            return true;
        }
        const auto term = util::trim(req.queryParam("q"));
        if (term.empty()) {
            AppContext::fail(res, 400, "missing_query", "请提供搜索关键词");
            return true;
        }
        const auto limit = util::toInt(req.queryParam("limit", "25"), 25);
        const auto url = endpoint + "?media=podcast&entity=podcast&term=" +
                         util::urlEncode(term) + "&limit=" + std::to_string(limit) +
                         "&country=" + util::urlEncode(req.queryParam("country", "CN"));
        fetch::Options options;
        options.userAgent = ctx.config.rssUserAgent;
        options.maxBytes = 4u << 20;
        options.timeoutSec = 12;
        auto fetched = fetch::get(url, options);
        if (!fetched.ok || fetched.status != 200) {
            AppContext::fail(res, 502, "discover_unavailable", "外部目录不可用，请直接添加 RSS 地址");
            return true;
        }
        Json parsed;
        if (!Json::parse(fetched.body, parsed)) {
            AppContext::fail(res, 502, "discover_unavailable", "目录响应解析失败");
            return true;
        }
        Json items = Json::makeArray();
        for (const auto& item : parsed.get("results").arrayItems()) {
            const auto feedUrl = item.get("feedUrl").asString("");
            if (feedUrl.empty()) continue;
            items.push(Json::makeObject()
                           .set("name", item.get("collectionName").asString(""))
                           .set("artist", item.get("artistName").asString(""))
                           .set("feedUrl", feedUrl)
                           .set("artworkUrl", item.get("artworkUrl600").asString(""))
                           .set("genre", item.get("primaryGenreName").asString(""))
                           .set("episodeCount", item.get("trackCount").asInt(0))
                           .set("country", item.get("country").asString("")));
        }
        res.json(200, Json::makeObject().set("items", items)
                          .set("total", static_cast<int>(items.size())).set("enabled", true));
        return true;
    });
}

}  // namespace podcast
