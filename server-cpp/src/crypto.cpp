#include "crypto.hpp"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>

#include "json.hpp"
#include "util.hpp"

namespace podcast::crypto {

namespace {
// scrypt 参数：N=2^15, r=8, p=1（约 32MB 内存，延迟 <100ms）
constexpr std::uint64_t kScryptN = 1u << 15;
constexpr std::uint64_t kScryptR = 8;
constexpr std::uint64_t kScryptP = 1;
constexpr std::size_t kSaltLen = 16;
constexpr std::size_t kKeyLen = 32;
constexpr std::uint64_t kMaxMem = 64ull * 1024 * 1024;

bool deriveKey(std::string_view password, std::string_view salt, std::uint64_t n,
               std::uint64_t r, std::uint64_t p, unsigned char* out, std::size_t outLen) {
    return EVP_PBE_scrypt(password.data(), password.size(),
                          reinterpret_cast<const unsigned char*>(salt.data()), salt.size(),
                          n, r, p, kMaxMem, out, outLen) == 1;
}
}  // namespace

std::string hashPassword(std::string_view password) {
    std::array<unsigned char, kSaltLen> salt{};
    if (RAND_bytes(salt.data(), static_cast<int>(salt.size())) != 1) {
        const auto fallback = util::sha256Raw(std::to_string(util::nowUnix()));
        std::memcpy(salt.data(), fallback.data(), kSaltLen);
    }
    std::string saltBytes(reinterpret_cast<const char*>(salt.data()), salt.size());
    std::vector<unsigned char> key(kKeyLen);
    if (!deriveKey(password, saltBytes, kScryptN, kScryptR, kScryptP, key.data(), key.size())) {
        return {};
    }
    const std::string saltB64 = util::base64UrlEncode(saltBytes);
    const std::string keyB64 = util::base64UrlEncode(
        std::string(reinterpret_cast<const char*>(key.data()), key.size()));
    char buf[128];
    std::snprintf(buf, sizeof(buf), "scrypt$%llu$%llu$%llu$",
                  static_cast<unsigned long long>(kScryptN),
                  static_cast<unsigned long long>(kScryptR),
                  static_cast<unsigned long long>(kScryptP));
    return std::string(buf) + saltB64 + "$" + keyB64;
}

bool verifyPassword(std::string_view password, std::string_view encoded, bool* needsRehash) {
    if (needsRehash) *needsRehash = false;
    const auto parts = util::split(encoded, '$');
    if (parts.size() != 6 || parts[0] != "scrypt") return false;
    const auto n = static_cast<std::uint64_t>(util::toInt64(parts[1], 0));
    const auto r = static_cast<std::uint64_t>(util::toInt64(parts[2], 0));
    const auto p = static_cast<std::uint64_t>(util::toInt64(parts[3], 0));
    if (n == 0 || r == 0 || p == 0) return false;
    const auto salt = util::base64UrlDecode(parts[4]);
    const auto want = util::base64UrlDecode(parts[5]);
    if (salt.empty() || want.empty()) return false;

    std::vector<unsigned char> got(want.size());
    if (!deriveKey(password, salt, n, r, p, got.data(), got.size())) return false;
    const bool ok = CRYPTO_memcmp(got.data(), want.data(), want.size()) == 0;
    if (ok && needsRehash) {
        *needsRehash = (n != kScryptN || r != kScryptR || p != kScryptP);
    }
    return ok;
}

namespace {
std::string hmacSha256(std::string_view key, std::string_view data) {
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(), key.data(), static_cast<int>(key.size()),
         reinterpret_cast<const unsigned char*>(data.data()), data.size(), out, &len);
    return std::string(reinterpret_cast<const char*>(out), len);
}
}  // namespace

std::string signJwt(const JwtClaims& claims, std::string_view secret) {
    Json header = Json::makeObject();
    header.set("alg", "HS256").set("typ", "JWT");
    Json payload = Json::makeObject();
    payload.set("uid", claims.userId)
        .set("email", claims.email)
        .set("role", claims.role)
        .set("iss", "podcastapp")
        .set("sub", claims.userId)
        .set("iat", claims.issuedAt)
        .set("exp", claims.expiresAt);

    const auto signingInput =
        util::base64UrlEncode(header.dump()) + "." + util::base64UrlEncode(payload.dump());
    const auto signature = hmacSha256(secret, signingInput);
    return signingInput + "." + util::base64UrlEncode(signature);
}

bool verifyJwt(std::string_view token, std::string_view secret, JwtClaims& out,
               std::string* error) {
    auto fail = [&](const std::string& msg) {
        if (error) *error = msg;
        return false;
    };
    const auto parts = util::split(token, '.');
    if (parts.size() != 3) return fail("malformed token");

    const std::string signingInput = parts[0] + "." + parts[1];
    const auto expected = hmacSha256(secret, signingInput);
    const auto given = util::base64UrlDecode(parts[2]);
    if (expected.size() != given.size() ||
        CRYPTO_memcmp(expected.data(), given.data(), given.size()) != 0) {
        return fail("bad signature");
    }

    Json payload;
    if (!Json::parse(util::base64UrlDecode(parts[1]), payload)) return fail("bad payload");
    const auto exp = payload.get("exp").asInt64(0);
    if (exp <= util::nowUnix()) return fail("token expired");
    const auto iss = payload.get("iss").asString("");
    if (iss != "podcastapp") return fail("bad issuer");

    out.userId = payload.get("uid").asString("");
    out.email = payload.get("email").asString("");
    out.role = payload.get("role").asString("");
    out.issuedAt = payload.get("iat").asInt64(0);
    out.expiresAt = exp;
    if (out.userId.empty()) return fail("missing uid");
    return true;
}

std::string newRefreshToken() {
    std::string raw(32, '\0');
    if (RAND_bytes(reinterpret_cast<unsigned char*>(raw.data()),
                   static_cast<int>(raw.size())) != 1) {
        raw = util::sha256Raw(std::to_string(util::nowUnix()) +
                             std::to_string(util::nowUnix() * 31));
    }
    return util::base64UrlEncode(raw);
}

std::string hashToken(std::string_view raw) {
    return util::sha256Hex(util::trim(raw));
}

}  // namespace podcast::crypto
