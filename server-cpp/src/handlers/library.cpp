#include <string>
#include <vector>

#include "router.hpp"
#include "util.hpp"

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

Json podcastJson(const std::vector<std::string>& row) {
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
        .set("subscribed", true)
        .set("subscribedAt", row.size() > 13 ? row[13] : "");
}

Json episodeJson(const std::vector<std::string>& row) {
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
    "p.id, p.feed_url, p.title, p.author, p.description, p.image_url, p.link, p.language, "
    "p.category, p.explicit, p.last_fetched_at, p.created_at, p.updated_at";

constexpr const char* kEpisodeColumns =
    "id, podcast_id, guid, title, description, audio_url, duration_seconds, published_at, "
    "image_url, episode_number, season_number";

std::vector<std::string> pathParts(const http::Request& req) {
    return http::splitPath(req.path);
}

}  // namespace

void registerLibraryRoutes(http::Server& server, AppContext& ctx) {
    // ---- 订阅列表 ----
    server.addRoute("GET", "/api/v1/subscriptions", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kPodcastColumns + ", s.created_at FROM subscriptions s "
            "JOIN podcasts p ON p.id = s.podcast_id "
            "WHERE s.user_id = ? AND s.deleted = 0 AND p.deleted = 0 ORDER BY s.created_at DESC",
            {auth.userId});
        Json items = Json::makeArray();
        for (const auto& row : rows) items.push(podcastJson(row));
        res.json(200, Json::makeObject().set("items", items).set("total", static_cast<int>(rows.size())));
        return true;
    });

    // ---- 订阅 / 取消 ----
    auto subscribeHandler = [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto podcastId = parts[3];
        const bool subscribe = req.method != "DELETE";
        if (subscribe) {
            const auto exists = ctx.db.queryScalar("SELECT id FROM podcasts WHERE id = ? AND deleted = 0",
                                                   {podcastId});
            if (!exists.has_value()) {
                AppContext::fail(res, 404, "not_found", "节目不存在");
                return true;
            }
        }
        const auto now = util::nowIso();
        ctx.db.run(
            "INSERT INTO subscriptions (id, user_id, podcast_id, notify, created_at, updated_at, deleted) "
            "VALUES (?, ?, ?, 1, ?, ?, ?) "
            "ON CONFLICT(user_id, podcast_id) DO UPDATE SET notify = excluded.notify, "
            "updated_at = excluded.updated_at, deleted = excluded.deleted",
            {newId(), auth.userId, podcastId, now, now, subscribe ? "0" : "1"});
        res.json(200, Json::makeObject().set("subscribed", subscribe));
        return true;
    };
    server.addRoute("PUT", "/api/v1/subscriptions/:podcastId", subscribeHandler);
    server.addRoute("DELETE", "/api/v1/subscriptions/:podcastId", subscribeHandler);

    // ---- 播放进度：列表 ----
    server.addRoute("GET", "/api/v1/progress", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        int limit = util::toInt(req.queryParam("limit", "30"), 30);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        int offset = util::toInt(req.queryParam("offset", "0"), 0);
        if (offset < 0) offset = 0;

        const auto rows = ctx.db.query(
            "SELECT id, episode_id, position_ms, duration_ms, completed, played_at, updated_at "
            "FROM progress WHERE user_id = ? AND deleted = 0 ORDER BY played_at DESC LIMIT ? OFFSET ?",
            {auth.userId, std::to_string(limit), std::to_string(offset)});
        Json items = Json::makeArray();
        std::vector<std::string> episodeIds;
        for (const auto& row : rows) {
            items.push(Json::makeObject()
                           .set("id", row[0])
                           .set("episodeId", row[1])
                           .set("positionMs", util::toInt64(row[2], 0))
                           .set("durationMs", util::toInt64(row[3], 0))
                           .set("completed", row[4] == "1")
                           .set("playedAt", row[5])
                           .set("updatedAt", row[6]));
            episodeIds.push_back(row[1]);
        }
        Json episodes = Json::makeArray();
        if (!episodeIds.empty()) {
            std::string placeholders;
            for (std::size_t i = 0; i < episodeIds.size(); ++i) {
                placeholders += (i ? ",?" : "?");
            }
            const auto episodeRows = ctx.db.query(
                std::string("SELECT ") + kEpisodeColumns +
                    " FROM episodes WHERE id IN (" + placeholders + ") AND deleted = 0",
                episodeIds);
            for (const auto& row : episodeRows) episodes.push(episodeJson(row));
        }
        const auto total = ctx.db.queryScalar(
            "SELECT COUNT(*) FROM progress WHERE user_id = ? AND deleted = 0", {auth.userId});
        res.json(200, Json::makeObject()
                          .set("items", items)
                          .set("episodes", episodes)
                          .set("total", util::toInt(total.value_or("0"), 0))
                          .set("limit", limit)
                          .set("offset", offset));
        return true;
    });

    // ---- 播放进度：单集读取 ----
    server.addRoute("GET", "/api/v1/progress/:episodeId",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto rows = ctx.db.query(
            "SELECT id, episode_id, position_ms, duration_ms, completed, played_at, updated_at "
            "FROM progress WHERE user_id = ? AND episode_id = ? AND deleted = 0",
            {auth.userId, parts[3]});
        if (rows.empty()) {
            res.json(200, Json::makeObject()
                              .set("episodeId", parts[3])
                              .set("positionMs", 0)
                              .set("durationMs", 0)
                              .set("completed", false));
            return true;
        }
        const auto& row = rows.front();
        res.json(200, Json::makeObject()
                          .set("id", row[0])
                          .set("episodeId", row[1])
                          .set("positionMs", util::toInt64(row[2], 0))
                          .set("durationMs", util::toInt64(row[3], 0))
                          .set("completed", row[4] == "1")
                          .set("playedAt", row[5])
                          .set("updatedAt", row[6]));
        return true;
    });

    // ---- 播放进度：写入 ----
    auto saveProgressHandler = [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto position = body.get("positionMs").asInt64(0);
        const auto duration = body.get("durationMs").asInt64(0);
        if (position < 0 || duration < 0) {
            AppContext::fail(res, 400, "invalid_progress", "进度不能为负数");
            return true;
        }
        const auto now = util::nowIso();
        const auto playedAt = util::normalizeTime(body.get("playedAt").asString(""));
        ctx.db.run(
            "INSERT INTO progress (id, user_id, episode_id, position_ms, duration_ms, completed, "
            "played_at, created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
            "ON CONFLICT(user_id, episode_id) DO UPDATE SET position_ms = excluded.position_ms, "
            "duration_ms = excluded.duration_ms, completed = excluded.completed, "
            "played_at = excluded.played_at, updated_at = excluded.updated_at, deleted = 0",
            {newId(), auth.userId, parts[3], std::to_string(position), std::to_string(duration),
             body.get("completed").asBool(false) ? "1" : "0", playedAt, now, now});
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    };
    server.addRoute("PUT", "/api/v1/progress/:episodeId", saveProgressHandler);
    server.addRoute("POST", "/api/v1/progress/:episodeId", saveProgressHandler);

    // ---- 收藏 / 待播 ----
    auto saveActionHandler = [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto now = util::nowIso();
        ctx.db.run(
            "INSERT INTO episode_actions (id, user_id, episode_id, starred, queued, downloaded, "
            "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0) "
            "ON CONFLICT(user_id, episode_id) DO UPDATE SET starred = excluded.starred, "
            "queued = excluded.queued, updated_at = excluded.updated_at, deleted = 0",
            {newId(), auth.userId, parts[3], body.get("starred").asBool(false) ? "1" : "0",
             body.get("queued").asBool(false) ? "1" : "0", now, now});
        res.json(200, Json::makeObject()
                          .set("episodeId", parts[3])
                          .set("starred", body.get("starred").asBool(false))
                          .set("queued", body.get("queued").asBool(false)));
        return true;
    };
    server.addRoute("PUT", "/api/v1/actions/:episodeId", saveActionHandler);
    server.addRoute("POST", "/api/v1/actions/:episodeId", saveActionHandler);

    // ---- 收藏 / 待播列表 ----
    server.addRoute("GET", "/api/v1/actions", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        std::string where = "user_id = ? AND deleted = 0";
        const auto type = req.queryParam("type");
        if (type == "starred") where += " AND starred = 1";
        else if (type == "queued") where += " AND queued = 1";
        else if (type == "downloaded") where += " AND downloaded = 1";
        const auto rows = ctx.db.query(
            "SELECT episode_id, starred, queued, downloaded, updated_at FROM episode_actions WHERE " +
                where + " ORDER BY updated_at DESC",
            {auth.userId});
        Json items = Json::makeArray();
        std::vector<std::string> episodeIds;
        for (const auto& row : rows) {
            items.push(Json::makeObject()
                           .set("episodeId", row[0])
                           .set("starred", row[1] == "1")
                           .set("queued", row[2] == "1")
                           .set("downloaded", row[3] == "1")
                           .set("updatedAt", row[4]));
            if (type.empty() || type == "starred" || type == "queued" || type == "downloaded") {
                episodeIds.push_back(row[0]);
            }
        }
        Json episodes = Json::makeArray();
        if (!episodeIds.empty()) {
            std::string placeholders;
            for (std::size_t i = 0; i < episodeIds.size(); ++i) placeholders += (i ? ",?" : "?");
            const auto episodeRows = ctx.db.query(
                std::string("SELECT ") + kEpisodeColumns +
                    " FROM episodes WHERE id IN (" + placeholders + ") AND deleted = 0",
                episodeIds);
            for (const auto& row : episodeRows) episodes.push(episodeJson(row));
        }
        res.json(200, Json::makeObject().set("items", items).set("episodes", episodes));
        return true;
    });

    // ---- 播放列表 ----
    server.addRoute("GET", "/api/v1/playlists", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto rows = ctx.db.query(
            "SELECT pl.id, pl.title, pl.description, pl.created_at, pl.updated_at, "
            "(SELECT COUNT(*) FROM playlist_items it WHERE it.playlist_id = pl.id AND it.deleted = 0) "
            "FROM playlists pl WHERE pl.user_id = ? AND pl.deleted = 0 ORDER BY pl.created_at DESC",
            {auth.userId});
        Json items = Json::makeArray();
        for (const auto& row : rows) {
            items.push(Json::makeObject()
                           .set("id", row[0])
                           .set("title", row[1])
                           .set("description", row[2])
                           .set("createdAt", row[3])
                           .set("updatedAt", row[4])
                           .set("itemCount", util::toInt(row[5], 0)));
        }
        res.json(200, Json::makeObject().set("items", items));
        return true;
    });

    server.addRoute("POST", "/api/v1/playlists", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto title = util::trim(body.get("title").asString(""));
        if (title.empty()) {
            AppContext::fail(res, 400, "missing_title", "请填写名称");
            return true;
        }
        const auto now = util::nowIso();
        ctx.db.run(
            "INSERT INTO playlists (id, user_id, title, description, created_at, updated_at, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?, 0)",
            {newId(), auth.userId, title, body.get("description").asString(""), now, now});
        const auto rows = ctx.db.query(
            "SELECT id, title, description, created_at, updated_at, 0 FROM playlists "
            "WHERE user_id = ? AND deleted = 0 ORDER BY created_at DESC",
            {auth.userId});
        Json items = Json::makeArray();
        for (const auto& row : rows) {
            items.push(Json::makeObject()
                           .set("id", row[0])
                           .set("title", row[1])
                           .set("description", row[2])
                           .set("createdAt", row[3])
                           .set("updatedAt", row[4])
                           .set("itemCount", 0));
        }
        res.json(201, Json::makeObject().set("items", items));
        return true;
    });

    // ---- 播放列表详情 ----
    server.addRoute("GET", "/api/v1/playlists/:id", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto playlistRows = ctx.db.query(
            "SELECT id, title, description, created_at, updated_at FROM playlists "
            "WHERE id = ? AND user_id = ? AND deleted = 0",
            {parts[3], auth.userId});
        if (playlistRows.empty()) {
            AppContext::fail(res, 404, "not_found", "播放列表不存在");
            return true;
        }
        const auto& pl = playlistRows.front();
        const auto episodeRows = ctx.db.query(
            std::string("SELECT e.") + kEpisodeColumns +
                " FROM playlist_items it JOIN episodes e ON e.id = it.episode_id "
                "WHERE it.playlist_id = ? AND it.user_id = ? AND it.deleted = 0 AND e.deleted = 0 "
                "ORDER BY it.position ASC",
            {parts[3], auth.userId});
        Json episodes = Json::makeArray();
        for (const auto& row : episodeRows) episodes.push(episodeJson(row));
        res.json(200, Json::makeObject()
                          .set("playlist", Json::makeObject()
                                               .set("id", pl[0])
                                               .set("title", pl[1])
                                               .set("description", pl[2])
                                               .set("createdAt", pl[3])
                                               .set("updatedAt", pl[4])
                                               .set("itemCount", static_cast<int>(episodeRows.size())))
                          .set("episodes", episodes));
        return true;
    });

    // ---- 播放列表项增删 ----
    server.addRoute("PUT", "/api/v1/playlists/:id/items/:episodeId",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 6) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto now = util::nowIso();
        const auto maxPos = ctx.db.queryScalar(
            "SELECT COALESCE(MAX(position), 0) FROM playlist_items WHERE playlist_id = ? AND deleted = 0",
            {parts[3]});
        ctx.db.run(
            "INSERT INTO playlist_items (id, user_id, playlist_id, episode_id, position, created_at, "
            "updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
            {newId(), auth.userId, parts[3], parts[5],
             std::to_string(util::toInt64(maxPos.value_or("0"), 0) + 1), now, now});
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });

    server.addRoute("DELETE", "/api/v1/playlists/:id/items/:episodeId",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 6) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        ctx.db.run(
            "UPDATE playlist_items SET deleted = 1, updated_at = ? "
            "WHERE playlist_id = ? AND episode_id = ? AND user_id = ?",
            {util::nowIso(), parts[3], parts[5], auth.userId});
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });

    server.addRoute("DELETE", "/api/v1/playlists/:id",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = pathParts(req);
        if (parts.size() < 4) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        ctx.db.run("UPDATE playlists SET deleted = 1, updated_at = ? WHERE id = ? AND user_id = ?",
                   {util::nowIso(), parts[3], auth.userId});
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });

    // ---- 用户偏好 ----
    server.addRoute("GET", "/api/v1/settings", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto rows = ctx.db.query(
            "SELECT playback_speed, skip_forward, skip_back, auto_download, download_policy, theme, "
            "notification_on FROM user_settings WHERE user_id = ?",
            {auth.userId});
        Json settings = Json::makeObject()
                            .set("playbackSpeed", 1.0)
                            .set("skipForward", 30)
                            .set("skipBack", 15)
                            .set("autoDownload", false)
                            .set("downloadPolicy", "wifi")
                            .set("theme", "system")
                            .set("notificationOn", true);
        if (!rows.empty()) {
            const auto& row = rows.front();
            settings.set("playbackSpeed", row[0].empty() ? 1.0 : std::stod(row[0]))
                .set("skipForward", util::toInt(row[1], 30))
                .set("skipBack", util::toInt(row[2], 15))
                .set("autoDownload", row[3] == "1")
                .set("downloadPolicy", row[4])
                .set("theme", row[5])
                .set("notificationOn", row[6] == "1");
        }
        res.json(200, settings);
        return true;
    });

    server.addRoute("PUT", "/api/v1/settings", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto now = util::nowIso();
        ctx.db.run(
            "INSERT INTO user_settings (user_id, playback_speed, skip_forward, skip_back, "
            "auto_download, download_policy, theme, notification_on, updated_at, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
            "ON CONFLICT(user_id) DO UPDATE SET playback_speed = excluded.playback_speed, "
            "skip_forward = excluded.skip_forward, skip_back = excluded.skip_back, "
            "auto_download = excluded.auto_download, download_policy = excluded.download_policy, "
            "theme = excluded.theme, notification_on = excluded.notification_on, "
            "updated_at = excluded.updated_at, deleted = 0",
            {auth.userId, std::to_string(body.get("playbackSpeed").asNumber(1.0)),
             std::to_string(body.get("skipForward").asInt(30)),
             std::to_string(body.get("skipBack").asInt(15)),
             body.get("autoDownload").asBool(false) ? "1" : "0",
             body.get("downloadPolicy").asString("wifi"), body.get("theme").asString("system"),
             body.get("notificationOn").asBool(true) ? "1" : "0", now});
        res.json(200, body);
        return true;
    });
}

}  // namespace podcast
