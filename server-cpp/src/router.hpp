#pragma once

#include <memory>
#include <string>

#include "config.hpp"
#include "db.hpp"
#include "http.hpp"
#include "json.hpp"

namespace podcast {

// 认证上下文（由中间件写入）
struct AuthContext {
    std::string userId;
    std::string email;
    std::string role;
    bool authenticated = false;
};

struct AppContext {
    Config config;
    Database db;
    std::string startedAt;

    // 统一 JSON 错误
    static void fail(http::Response& res, int status, const std::string& code,
                     const std::string& message);
    // 成功包装
    static void ok(http::Response& res, const Json& payload);
};

// 路由注册入口
void registerAuthRoutes(http::Server& server, AppContext& ctx);
void registerContentRoutes(http::Server& server, AppContext& ctx);
void registerLibraryRoutes(http::Server& server, AppContext& ctx);
void registerSyncRoutes(http::Server& server, AppContext& ctx);
void registerStationRoutes(http::Server& server, AppContext& ctx);
void registerMediaRoutes(http::Server& server, AppContext& ctx, int workers);

// 从请求解析当前用户（失败返回空）
bool authenticate(const http::Request& req, AppContext& ctx, AuthContext& out,
                  std::string* error);

// 通用
std::string newId();

}  // namespace podcast
