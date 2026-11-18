#include "router.hpp"

#include <string>

#include "crypto.hpp"
#include "util.hpp"

namespace podcast {

void AppContext::fail(http::Response& res, int status, const std::string& code,
                      const std::string& message) {
    res.json(status, Json::makeObject().set("code", code).set("message", message));
}

void AppContext::ok(http::Response& res, const Json& payload) { res.json(200, payload); }

std::string newId() { return util::randomHex(16); }

bool authenticate(const http::Request& req, AppContext& ctx, AuthContext& out,
                  std::string* error) {
    const auto token = req.bearerToken();
    if (token.empty()) {
        if (error) *error = "缺少访问令牌";
        return false;
    }
    crypto::JwtClaims claims;
    std::string err;
    if (!crypto::verifyJwt(token, ctx.config.jwtSecret, claims, &err)) {
        if (error) *error = err;
        return false;
    }
    out.userId = claims.userId;
    out.email = claims.email;
    out.role = claims.role;
    out.authenticated = true;
    return true;
}

}  // namespace podcast
