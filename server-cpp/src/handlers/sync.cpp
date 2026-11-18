#include <string>
#include <vector>

#include "router.hpp"
#include "util.hpp"

namespace podcast {

namespace {

std::vector<std::string> pathParts(const http::Request& req) {
    return http::splitPath(req.path);
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
        .set("subscribed", true);
}

}  // namespace

void registerSyncRoutes(http::Server& server, AppContext& ctx) {
    server.addRoute("POST", "/api/v1/sync", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!req.body.empty()) {
            if (!Json::parse(req.body, body)) {
                AppContext::fail(res, 400, "invalid_body", "请求体格式错误");
                return true;
            }
        }
        if (!body.isObject()) body = Json::makeObject();

        // 1) 上传本地变更
        for (const auto& item : body.get("items").arrayItems()) {
            const auto episodeId = item.get("episodeId").asString("");
            if (episodeId.empty()) continue;
            const auto now = util::nowIso();
            const auto playedAt = util::normalizeTime(item.get("playedAt").asString(""));
            ctx.db.run(
                "INSERT INTO progress (id, user_id, episode_id, position_ms, duration_ms, completed, "
                "played_at, created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                "ON CONFLICT(user_id, episode_id) DO UPDATE SET position_ms = excluded.position_ms, "
                "duration_ms = excluded.duration_ms, completed = excluded.completed, "
                "played_at = excluded.played_at, updated_at = excluded.updated_at, deleted = 0",
                {newId(), auth.userId, episodeId,
                 std::to_string(item.get("positionMs").asInt64(0)),
                 std::to_string(item.get("durationMs").asInt64(0)),
                 item.get("completed").asBool(false) ? "1" : "0", playedAt, now, now});
            if (item.has("starred") || item.has("queued")) {
                ctx.db.run(
                    "INSERT INTO episode_actions (id, user_id, episode_id, starred, queued, "
                    "downloaded, created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0) "
                    "ON CONFLICT(user_id, episode_id) DO UPDATE SET starred = excluded.starred, "
                    "queued = excluded.queued, updated_at = excluded.updated_at, deleted = 0",
                    {newId(), auth.userId, episodeId,
                     item.get("starred").asBool(false) ? "1" : "0",
                     item.get("queued").asBool(false) ? "1" : "0", now, now});
            }
        }

        // 2) 返回云端快照
        const auto subRows = ctx.db.query(
            "SELECT p.id, p.feed_url, p.title, p.author, p.description, p.image_url, p.link, "
            "p.language, p.category, p.explicit, p.last_fetched_at, p.created_at, p.updated_at "
            "FROM subscriptions s JOIN podcasts p ON p.id = s.podcast_id "
            "WHERE s.user_id = ? AND s.deleted = 0 AND p.deleted = 0 ORDER BY s.created_at DESC",
            {auth.userId});
        Json subscriptions = Json::makeArray();
        for (const auto& row : subRows) subscriptions.push(podcastJson(row));

        const auto progressRows = ctx.db.query(
            "SELECT id, episode_id, position_ms, duration_ms, completed, played_at, updated_at "
            "FROM progress WHERE user_id = ? AND deleted = 0 ORDER BY played_at DESC LIMIT 500",
            {auth.userId});
        Json progress = Json::makeArray();
        for (const auto& row : progressRows) {
            progress.push(Json::makeObject()
                              .set("id", row[0])
                              .set("episodeId", row[1])
                              .set("positionMs", util::toInt64(row[2], 0))
                              .set("durationMs", util::toInt64(row[3], 0))
                              .set("completed", row[4] == "1")
                              .set("playedAt", row[5])
                              .set("updatedAt", row[6]));
        }

        const auto actionRows = ctx.db.query(
            "SELECT episode_id, starred, queued, downloaded, updated_at FROM episode_actions "
            "WHERE user_id = ? AND deleted = 0 ORDER BY updated_at DESC",
            {auth.userId});
        Json actions = Json::makeArray();
        for (const auto& row : actionRows) {
            actions.push(Json::makeObject()
                             .set("episodeId", row[0])
                             .set("starred", row[1] == "1")
                             .set("queued", row[2] == "1")
                             .set("downloaded", row[3] == "1")
                             .set("updatedAt", row[4]));
        }

        const auto playlistRows = ctx.db.query(
            "SELECT id, title, description, created_at, updated_at FROM playlists "
            "WHERE user_id = ? AND deleted = 0 ORDER BY created_at DESC",
            {auth.userId});
        Json playlists = Json::makeArray();
        for (const auto& row : playlistRows) {
            playlists.push(Json::makeObject()
                               .set("id", row[0])
                               .set("title", row[1])
                               .set("description", row[2])
                               .set("createdAt", row[3])
                               .set("updatedAt", row[4]));
        }

        res.json(200, Json::makeObject()
                          .set("subscriptions", subscriptions)
                          .set("progress", progress)
                          .set("actions", actions)
                          .set("playlists", playlists)
                          .set("serverTime", util::nowIso()));
        return true;
    });
}

}  // namespace podcast
