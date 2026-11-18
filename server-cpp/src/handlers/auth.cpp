#include <string>

#include "crypto.hpp"
#include "router.hpp"
#include "util.hpp"

namespace podcast {

namespace {

// 读取请求体 JSON；失败返回空对象并写错误
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
    if (!out.isObject()) {
        AppContext::fail(res, 400, "invalid_body", "请求体必须是 JSON 对象");
        return false;
    }
    return true;
}

bool validEmail(const std::string& email) {
    const auto at = email.find('@');
    if (at == std::string::npos || at == 0 || at + 1 >= email.size()) return false;
    const auto dot = email.find('.', at + 1);
    return dot != std::string::npos && dot + 1 < email.size();
}

Json userJson(const std::vector<std::string>& row) {
    // id, email, display_name, role, created_at
    return Json::makeObject()
        .set("id", row.size() > 0 ? row[0] : "")
        .set("email", row.size() > 1 ? row[1] : "")
        .set("displayName", row.size() > 2 ? row[2] : "")
        .set("role", row.size() > 3 ? row[3] : "user")
        .set("createdAt", row.size() > 4 ? row[4] : "");
}

struct TokenPair {
    std::string access;
    std::string refresh;
    std::int64_t expiresIn = 0;
};

TokenPair issueTokens(AppContext& ctx, const std::string& userId, const std::string& email,
                      const std::string& role, const std::string& deviceId,
                      const std::string& userAgent) {
    TokenPair pair;
    crypto::JwtClaims claims;
    claims.userId = userId;
    claims.email = email;
    claims.role = role;
    claims.issuedAt = util::nowUnix();
    claims.expiresAt = claims.issuedAt + ctx.config.accessTokenTtlSec;
    pair.access = crypto::signJwt(claims, ctx.config.jwtSecret);
    pair.expiresIn = ctx.config.accessTokenTtlSec;

    const auto raw = crypto::newRefreshToken();
    const auto hash = crypto::hashToken(raw);
    const auto now = util::nowIso();
    const auto expires = util::nowIso();
    // 过期时间用秒计算后格式化
    std::time_t exp = static_cast<std::time_t>(util::nowUnix() + ctx.config.refreshTokenTtlSec);
    std::tm tm{};
    gmtime_r(&exp, &tm);
    char buf[32];
    std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &tm);
    const std::string expiresAt = buf;

    ctx.db.run(
        "INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, user_agent, expires_at, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        {newId(), userId, hash, deviceId, userAgent, expiresAt, now});
    if (!deviceId.empty()) {
        ctx.db.run(
            "INSERT INTO devices (id, user_id, name, platform, user_agent, last_seen_at, created_at, updated_at, deleted) "
            "VALUES (?, ?, '', 'android', ?, ?, ?, ?, 0) "
            "ON CONFLICT(id) DO UPDATE SET last_seen_at = excluded.last_seen_at, updated_at = excluded.updated_at",
            {deviceId, userId, userAgent, now, now, now});
    }
    pair.refresh = raw;
    return pair;
}

Json tokenJson(const TokenPair& pair) {
    return Json::makeObject()
        .set("accessToken", pair.access)
        .set("refreshToken", pair.refresh)
        .set("expiresIn", pair.expiresIn)
        .set("tokenType", "Bearer");
}

}  // namespace

void registerAuthRoutes(http::Server& server, AppContext& ctx) {
    // ---- 注册 ----
    server.addRoute("POST", "/api/v1/auth/register", [&ctx](const http::Request& req, http::Response& res) {
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto email = util::toLower(util::trim(body.get("email").asString("")));
        const auto password = body.get("password").asString("");
        const auto displayName = util::trim(body.get("displayName").asString(""));
        const auto inviteCode = util::trim(body.get("inviteCode").asString(""));
        const auto deviceId = util::trim(body.get("deviceId").asString(""));

        if (!validEmail(email)) {
            AppContext::fail(res, 400, "invalid_email", "邮箱格式不正确");
            return true;
        }
        if (password.size() < 8) {
            AppContext::fail(res, 400, "weak_password", "密码至少 8 位");
            return true;
        }
        if (!ctx.config.inviteCode.empty() && inviteCode != ctx.config.inviteCode) {
            AppContext::fail(res, 403, "invalid_invite", "邀请码不正确");
            return true;
        }

        const auto count = ctx.db.queryScalar("SELECT COUNT(*) FROM users").value_or("0");
        const std::string role = util::toInt64(count, 0) == 0 ? "admin" : "user";
        const auto hash = crypto::hashPassword(password);
        if (hash.empty()) {
            AppContext::fail(res, 500, "internal", "密码处理失败");
            return true;
        }
        const auto now = util::nowIso();
        const auto userId = newId();
        const auto name = displayName.empty() ? email.substr(0, email.find('@')) : displayName;
        std::string err;
        const int inserted = ctx.db.run(
            "INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            {userId, email, hash, name, role, now, now}, &err);
        if (inserted < 0) {
            if (err.find("UNIQUE") != std::string::npos || err.find("unique") != std::string::npos) {
                AppContext::fail(res, 409, "email_taken", "该邮箱已注册");
            } else {
                AppContext::fail(res, 500, "internal", "注册失败");
            }
            return true;
        }

        const auto pair = issueTokens(ctx, userId, email, role, deviceId, req.header("user-agent"));
        res.json(201, Json::makeObject()
                          .set("user", Json::makeObject()
                                            .set("id", userId)
                                            .set("email", email)
                                            .set("displayName", name)
                                            .set("role", role)
                                            .set("createdAt", now))
                          .set("token", tokenJson(pair)));
        return true;
    });

    // ---- 登录 ----
    server.addRoute("POST", "/api/v1/auth/login", [&ctx](const http::Request& req, http::Response& res) {
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto email = util::toLower(util::trim(body.get("email").asString("")));
        const auto password = body.get("password").asString("");
        const auto deviceId = util::trim(body.get("deviceId").asString(""));

        const auto rows = ctx.db.query(
            "SELECT id, email, password_hash, display_name, role, created_at FROM users WHERE email = ?",
            {email});
        if (rows.empty()) {
            AppContext::fail(res, 401, "invalid_credentials", "邮箱或密码错误");
            return true;
        }
        const auto& row = rows.front();
        bool needsRehash = false;
        if (!crypto::verifyPassword(password, row[2], &needsRehash)) {
            AppContext::fail(res, 401, "invalid_credentials", "邮箱或密码错误");
            return true;
        }
        if (needsRehash) {
            const auto upgraded = crypto::hashPassword(password);
            if (!upgraded.empty()) {
                ctx.db.run("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                           {upgraded, util::nowIso(), row[0]});
            }
        }
        const auto pair = issueTokens(ctx, row[0], row[1], row[4], deviceId, req.header("user-agent"));
        res.json(200, Json::makeObject()
                          .set("user", Json::makeObject()
                                            .set("id", row[0])
                                            .set("email", row[1])
                                            .set("displayName", row[3])
                                            .set("role", row[4])
                                            .set("createdAt", row[5]))
                          .set("token", tokenJson(pair)));
        return true;
    });

    // ---- 刷新 ----
    server.addRoute("POST", "/api/v1/auth/refresh", [&ctx](const http::Request& req, http::Response& res) {
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto raw = util::trim(body.get("refreshToken").asString(""));
        const auto deviceId = util::trim(body.get("deviceId").asString(""));
        if (raw.empty()) {
            AppContext::fail(res, 401, "invalid_refresh", "刷新令牌无效");
            return true;
        }
        const auto hash = crypto::hashToken(raw);
        const auto rows = ctx.db.query(
            "SELECT id, user_id, device_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?",
            {hash});
        if (rows.empty()) {
            AppContext::fail(res, 401, "invalid_refresh", "刷新令牌无效");
            return true;
        }
        const auto& row = rows.front();
        if (!row[4].empty()) {
            AppContext::fail(res, 401, "invalid_refresh", "刷新令牌已失效");
            return true;
        }
        // 统一成相同精度再比较，避免 ISO 字符串长度不一致导致误判
        if (row[3] < util::nowIso().substr(0, 20) + "Z") {
            AppContext::fail(res, 401, "invalid_refresh", "刷新令牌已过期");
            return true;
        }
        const auto userRows = ctx.db.query(
            "SELECT id, email, password_hash, display_name, role, created_at FROM users WHERE id = ?",
            {row[1]});
        if (userRows.empty()) {
            AppContext::fail(res, 401, "invalid_refresh", "账号不存在");
            return true;
        }
        const auto& user = userRows.front();
        ctx.db.run("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?", {util::nowIso(), row[0]});
        const auto effectiveDevice = deviceId.empty() ? row[2] : deviceId;
        const auto pair = issueTokens(ctx, user[0], user[1], user[4], effectiveDevice,
                                     req.header("user-agent"));
        res.json(200, Json::makeObject()
                          .set("user", Json::makeObject()
                                            .set("id", user[0])
                                            .set("email", user[1])
                                            .set("displayName", user[3])
                                            .set("role", user[4])
                                            .set("createdAt", user[5]))
                          .set("token", tokenJson(pair)));
        return true;
    });

    // ---- 退出 ----
    server.addRoute("POST", "/api/v1/auth/logout", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        parseBody(req, body, res);
        const auto raw = util::trim(body.get("refreshToken").asString(""));
        const bool allDevices = body.get("allDevices").asBool(false);
        if (allDevices || raw.empty()) {
            ctx.db.run("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                       {util::nowIso(), auth.userId});
        } else {
            const auto hash = crypto::hashToken(raw);
            ctx.db.run("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND user_id = ?",
                       {util::nowIso(), hash, auth.userId});
        }
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });

    // ---- 当前用户 ----
    server.addRoute("GET", "/api/v1/me", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        const auto rows = ctx.db.query(
            "SELECT id, email, display_name, role, created_at FROM users WHERE id = ?", {auth.userId});
        if (rows.empty()) {
            AppContext::fail(res, 404, "not_found", "账号不存在");
            return true;
        }
        res.json(200, Json::makeObject().set("user", userJson(rows.front())));
        return true;
    });

    // ---- 修改昵称 ----
    server.addRoute("PATCH", "/api/v1/me", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto name = util::trim(body.get("displayName").asString(""));
        ctx.db.run("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?",
                   {name, util::nowIso(), auth.userId});
        const auto rows = ctx.db.query(
            "SELECT id, email, display_name, role, created_at FROM users WHERE id = ?", {auth.userId});
        if (rows.empty()) {
            AppContext::fail(res, 404, "not_found", "账号不存在");
            return true;
        }
        res.json(200, Json::makeObject().set("user", userJson(rows.front())));
        return true;
    });

    // ---- 修改密码 ----
    server.addRoute("POST", "/api/v1/me/password", [&ctx](const http::Request& req, http::Response& res) {
        AuthContext auth;
        std::string error;
        if (!authenticate(req, ctx, auth, &error)) {
            AppContext::fail(res, 401, "unauthorized", error);
            return true;
        }
        Json body;
        if (!parseBody(req, body, res)) return true;
        const auto current = body.get("currentPassword").asString("");
        const auto next = body.get("newPassword").asString("");
        if (next.size() < 8) {
            AppContext::fail(res, 400, "weak_password", "新密码至少 8 位");
            return true;
        }
        const auto rows = ctx.db.query("SELECT password_hash FROM users WHERE id = ?", {auth.userId});
        if (rows.empty() || !crypto::verifyPassword(current, rows.front()[0])) {
            AppContext::fail(res, 403, "invalid_credentials", "当前密码错误");
            return true;
        }
        const auto hash = crypto::hashPassword(next);
        if (hash.empty()) {
            AppContext::fail(res, 500, "internal", "密码处理失败");
            return true;
        }
        ctx.db.run("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                   {hash, util::nowIso(), auth.userId});
        ctx.db.run("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                   {util::nowIso(), auth.userId});
        res.json(200, Json::makeObject().set("status", "ok"));
        return true;
    });
}

}  // namespace podcast
