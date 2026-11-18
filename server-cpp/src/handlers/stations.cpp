#include <string>
#include <vector>

#include "fetch.hpp"
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

bool isHttpUrl(const std::string& url) {
    const auto lower = util::toLower(url);
    return util::startsWith(lower, "http://") || util::startsWith(lower, "https://");
}

bool guessHls(const std::string& url) {
    const auto lower = util::toLower(url);
    return lower.find(".m3u8") != std::string::npos || lower.find("/hls/") != std::string::npos;
}

std::string guessName(const std::string& streamUrl) {
    const auto parsed = fetch::parseUrl(streamUrl);
    return parsed.valid ? parsed.host : "网络电台";
}

std::string shortenTags(const std::string& tags) {
    auto parts = util::split(tags, ',');
    std::vector<std::string> trimmed;
    for (const auto& part : parts) {
        const auto t = util::trim(part);
        if (!t.empty()) trimmed.push_back(t);
        if (trimmed.size() >= 3) break;
    }
    return util::join(trimmed, ", ");
}

constexpr const char* kStationColumns =
    "id, user_id, name, stream_url, homepage, favicon, genre, country, language, codec, "
    "bitrate, is_hls, source, external_id, created_at, updated_at";

Json stationJson(const std::vector<std::string>& row, bool favorite = false) {
    return Json::makeObject()
        .set("id", row.size() > 0 ? row[0] : "")
        .set("name", row.size() > 2 ? row[2] : "")
        .set("streamUrl", row.size() > 3 ? row[3] : "")
        .set("homepage", row.size() > 4 ? row[4] : "")
        .set("favicon", row.size() > 5 ? row[5] : "")
        .set("genre", row.size() > 6 ? row[6] : "")
        .set("country", row.size() > 7 ? row[7] : "")
        .set("language", row.size() > 8 ? row[8] : "")
        .set("codec", row.size() > 9 ? row[9] : "")
        .set("bitrate", row.size() > 10 ? util::toInt(row[10], 0) : 0)
        .set("isHls", row.size() > 11 && row[11] == "1")
        .set("source", row.size() > 12 ? row[12] : "manual")
        .set("externalId", row.size() > 13 ? row[13] : "")
        .set("createdAt", row.size() > 14 ? row[14] : "")
        .set("updatedAt", row.size() > 15 ? row[15] : "")
        .set("favorite", favorite);
}

std::string upsertStation(AppContext& ctx, const std::string& userId, const Json& body,
                          const std::string& source) {
    const auto streamUrl = util::trim(body.get("streamUrl").asString(""));
    if (!isHttpUrl(streamUrl)) return {};
    auto name = util::trim(body.get("name").asString(""));
    if (name.empty()) name = guessName(streamUrl);
    const auto now = util::nowIso();
    const auto id = newId();
    ctx.db.run(
        "INSERT INTO stations (id, user_id, name, stream_url, homepage, favicon, genre, country, "
        "language, codec, bitrate, is_hls, source, external_id, created_at, updated_at, deleted) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
        "ON CONFLICT(user_id, stream_url) DO UPDATE SET name = excluded.name, "
        "homepage = excluded.homepage, favicon = excluded.favicon, genre = excluded.genre, "
        "country = excluded.country, language = excluded.language, codec = excluded.codec, "
        "bitrate = excluded.bitrate, is_hls = excluded.is_hls, source = excluded.source, "
        "external_id = excluded.external_id, updated_at = excluded.updated_at, deleted = 0",
        {id, userId, name, streamUrl, util::trim(body.get("homepage").asString("")),
         util::trim(body.get("favicon").asString("")), util::trim(body.get("genre").asString("")),
         util::trim(body.get("country").asString("")),
         util::trim(body.get("language").asString("")),
         util::toLower(util::trim(body.get("codec").asString(""))).empty()
             ? ""
             : util::toLower(util::trim(body.get("codec").asString(""))),
         std::to_string(body.get("bitrate").asInt(0)), guessHls(streamUrl) ? "1" : "0", source,
         util::trim(body.get("externalId").asString("")), now, now});
    const auto rows = ctx.db.query(
        "SELECT id FROM stations WHERE user_id = ? AND stream_url = ?", {userId, streamUrl});
    return rows.empty() ? std::string() : rows.front()[0];
}

}  // namespace

void registerStationRoutes(http::Server& server, AppContext& ctx) {
    // ---- 列表 ----
    server.addRoute("GET", "/api/v1/stations", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kStationColumns +
                " FROM stations WHERE deleted = 0 AND (user_id = ? OR (user_id = '' AND id IN "
                "(SELECT station_id FROM station_favorites WHERE user_id = ? AND deleted = 0))) "
                "ORDER BY (user_id = ?) DESC, updated_at DESC",
            {auth.userId, auth.userId, auth.userId});
        const auto favRows = ctx.db.query(
            "SELECT station_id FROM station_favorites WHERE user_id = ? AND deleted = 0",
            {auth.userId});
        std::vector<std::string> favorites;
        for (const auto& r : favRows) favorites.push_back(r[0]);

        Json items = Json::makeArray();
        for (const auto& row : rows) {
            bool fav = false;
            for (const auto& f : favorites) {
                if (f == row[0]) { fav = true; break; }
            }
            items.push(stationJson(row, fav));
        }
        res.json(200, Json::makeObject().set("items", items).set("total", static_cast<int>(rows.size())));
        return true;
    });

    // ---- 添加 ----
    server.addRoute("POST", "/api/v1/stations", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto streamUrl = util::trim(body.get("streamUrl").asString(""));
        if (!isHttpUrl(streamUrl)) {
            AppContext::fail(res, 400, "invalid_stream_url", "请提供 http(s) 音频流地址");
            return true;
        }
        const auto id = upsertStation(ctx, auth.userId, body, "manual");
        if (id.empty()) {
            AppContext::fail(res, 500, "internal", "保存失败");
            return true;
        }
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kStationColumns + " FROM stations WHERE id = ?", {id});
        res.json(200, Json::makeObject().set("station", stationJson(rows.front())));
        return true;
    });

    // ---- 删除 ----
    server.addRoute("DELETE", "/api/v1/stations/:id",
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
        const int changed = ctx.db.run(
            "UPDATE stations SET deleted = 1, updated_at = ? WHERE id = ? AND user_id = ?",
            {util::nowIso(), parts[3], auth.userId});
        if (changed == 0) {
            AppContext::fail(res, 404, "not_found", "电台不存在");
            return true;
        }
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });

    // ---- 收藏 ----
    auto favoriteHandler = [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto parts = http::splitPath(req.path);
        if (parts.size() < 5) {
            AppContext::fail(res, 400, "invalid_path", "路径错误");
            return true;
        }
        const auto station = ctx.db.query(
            "SELECT id FROM stations WHERE id = ? AND deleted = 0 AND (user_id = ? OR user_id = '')",
            {parts[3], auth.userId});
        if (station.empty()) {
            AppContext::fail(res, 404, "not_found", "电台不存在");
            return true;
        }
        bool favorite = req.method != "DELETE";
        if (req.method == "PUT" && !req.body.empty()) {
            Json body;
            if (Json::parse(req.body, body)) favorite = body.get("favorite").asBool(favorite);
        }
        const auto now = util::nowIso();
        ctx.db.run(
            "INSERT INTO station_favorites (id, user_id, station_id, created_at, updated_at, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(user_id, station_id) DO UPDATE SET deleted = excluded.deleted, "
            "updated_at = excluded.updated_at",
            {newId(), auth.userId, parts[3], now, now, favorite ? "0" : "1"});
        res.json(200, Json::makeObject().set("favorite", favorite));
        return true;
    };
    server.addRoute("PUT", "/api/v1/stations/:id/favorite", favoriteHandler);
    server.addRoute("POST", "/api/v1/stations/:id/favorite", favoriteHandler);
    server.addRoute("DELETE", "/api/v1/stations/:id/favorite", favoriteHandler);

    // ---- 流探测 ----
    server.addRoute("GET", "/api/v1/stations/probe",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto raw = util::trim(req.queryParam("url"));
        if (!isHttpUrl(raw)) {
            AppContext::fail(res, 400, "invalid_stream_url", "请提供 http(s) 音频流地址");
            return true;
        }
        fetch::Options options;
        options.userAgent = ctx.config.rssUserAgent;
        options.maxBytes = 4096;
        options.timeoutSec = 8;
        const auto started = std::chrono::steady_clock::now();
        auto fetched = fetch::get(raw, options);
        const auto latency = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started);
        const bool reachable = fetched.ok && fetched.status > 0 && fetched.status < 400;
        res.json(200, Json::makeObject()
                          .set("reachable", reachable)
                          .set("statusCode", fetched.status)
                          .set("contentType", fetched.contentType)
                          .set("isHls", guessHls(raw) ||
                                            util::toLower(fetched.contentType).find("mpegurl") !=
                                                std::string::npos)
                          .set("finalUrl", raw)
                          .set("latencyMs", static_cast<std::int64_t>(latency.count()))
                          .set("error", fetched.error));
        return true;
    });

    // ---- 目录搜索（管理员显式配置的兼容目录，默认禁用）----
    server.addRoute("GET", "/api/v1/discover/stations",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto endpoint = util::trim(ctx.config.radioBrowserUrl);
        if (endpoint.empty()) {
            res.json(200, Json::makeObject().set("items", Json::makeArray())
                              .set("total", 0).set("enabled", false)
                              .set("message", "电台目录未启用，请手动导入有权使用的电台地址"));
            return true;
        }
        const auto term = util::trim(req.queryParam("q"));
        const auto country = util::trim(req.queryParam("country"));
        const auto tag = util::trim(req.queryParam("tag"));
        auto limit = util::toInt(req.queryParam("limit", "30"), 30);
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;

        std::string query = "?limit=" + std::to_string(limit) +
                            "&hidebroken=true&order=clickcount&reverse=true";
        if (!term.empty()) query += "&name=" + util::urlEncode(term);
        if (!country.empty()) query += "&countrycode=" + util::urlEncode(util::toLower(country).empty() ? country : country);
        if (!tag.empty()) query += "&tag=" + util::urlEncode(tag);

        fetch::Options options;
        options.userAgent = ctx.config.rssUserAgent + " (+radio-directory)";
        options.maxBytes = 8u << 20;
        options.timeoutSec = 12;
        auto fetched = fetch::get(endpoint + query, options);
        if (!fetched.ok || fetched.status != 200) {
            AppContext::fail(res, 502, "directory_unavailable", "电台目录不可用，可手动添加流地址");
            return true;
        }
        Json parsed;
        if (!Json::parse(fetched.body, parsed) || !parsed.isArray()) {
            AppContext::fail(res, 502, "directory_unavailable", "目录响应解析失败");
            return true;
        }
        Json items = Json::makeArray();
        for (const auto& station : parsed.arrayItems()) {
            auto streamUrl = util::trim(station.get("url_resolved").asString(""));
            if (streamUrl.empty()) streamUrl = util::trim(station.get("url").asString(""));
            if (!isHttpUrl(streamUrl)) continue;
            items.push(Json::makeObject()
                           .set("name", util::trim(station.get("name").asString("")))
                           .set("streamUrl", streamUrl)
                           .set("homepage", station.get("homepage").asString(""))
                           .set("favicon", station.get("favicon").asString(""))
                           .set("genre", shortenTags(station.get("tags").asString("")))
                           .set("country", station.get("country").asString(""))
                           .set("language", station.get("language").asString(""))
                           .set("codec", util::toLower(station.get("codec").asString("")))
                           .set("bitrate", station.get("bitrate").asInt(0))
                           .set("isHls", station.get("hls").asInt(0) == 1 || guessHls(streamUrl))
                           .set("externalId", station.get("stationuuid").asString(""))
                           .set("votes", station.get("votes").asInt(0)));
        }
        res.json(200, Json::makeObject().set("items", items)
                          .set("total", static_cast<int>(items.size())).set("enabled", true));
        return true;
    });

    // ---- 用户自行批量导入（仅归属当前用户，不发布为公共电台）----
    server.addRoute("POST", "/api/v1/stations/import",
                    [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto stations = body.get("stations").arrayItems();
        if (stations.empty() || stations.size() > 100) {
            AppContext::fail(res, 400, "invalid_batch", "一次最多导入 100 个电台");
            return true;
        }
        int imported = 0;
        for (const auto& station : stations) {
            if (!upsertStation(ctx, auth.userId, station, "manual").empty()) ++imported;
        }
        const auto rows = ctx.db.query(
            std::string("SELECT ") + kStationColumns +
                " FROM stations WHERE deleted = 0 AND (user_id = ? OR (user_id = '' AND id IN "
                "(SELECT station_id FROM station_favorites WHERE user_id = ? AND deleted = 0))) "
                "ORDER BY updated_at DESC",
            {auth.userId, auth.userId});
        Json items = Json::makeArray();
        for (const auto& row : rows) items.push(stationJson(row));
        res.json(200, Json::makeObject().set("imported", imported).set("items", items));
        return true;
    });
}

}  // namespace podcast
