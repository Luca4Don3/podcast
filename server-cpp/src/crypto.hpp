#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace podcast::crypto {

// scrypt 密码哈希（OpenSSL EVP_PBE_scrypt），格式：
// scrypt$N$r$p$<base64url salt>$<base64url key>
std::string hashPassword(std::string_view password);

// 校验密码；ok=false 表示不匹配。needsRehash 用于将来升级参数
bool verifyPassword(std::string_view password, std::string_view encoded, bool* needsRehash = nullptr);

// JWT (HS256)
struct JwtClaims {
    std::string userId;
    std::string email;
    std::string role;
    std::int64_t issuedAt = 0;
    std::int64_t expiresAt = 0;
};

std::string signJwt(const JwtClaims& claims, std::string_view secret);
// 校验签名与有效期；失败返回 false 并写入 error
bool verifyJwt(std::string_view token, std::string_view secret, JwtClaims& out,
               std::string* error = nullptr);

// 不透明刷新令牌
std::string newRefreshToken();               // 原始令牌
std::string hashToken(std::string_view raw); // SHA-256 十六进制

}  // namespace podcast::crypto
