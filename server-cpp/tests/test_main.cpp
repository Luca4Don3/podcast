#include <cstdio>
#include <cstdlib>
#include <string>

#include "config.hpp"
#include "json.hpp"
#include "util.hpp"
#include "xml.hpp"

static int g_failures = 0;

#define CHECK(cond, msg)                                                  \
    do {                                                                  \
        if (!(cond)) {                                                    \
            std::printf("FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

// Restore the caller's environment; configuration tests never depend on deployment values.
class ScopedEnv {
public:
    ScopedEnv(const char* key, const char* value) : key_(key) {
        const char* old = std::getenv(key);
        existed_ = old != nullptr;
        if (old) old_ = old;
        set(value);
    }
    ~ScopedEnv() { set(existed_ ? old_.c_str() : nullptr); }
    void set(const char* value) const {
        CHECK((value ? setenv(key_, value, 1) : unsetenv(key_)) == 0, "update test environment");
    }
private:
    const char* key_;
    bool existed_;
    std::string old_;
};

int main() {
    using namespace podcast;

    // ---- Directory configuration: opt-in only, including reused Config ----
    {
        ScopedEnv env("APP_ENV", "test");
        ScopedEnv secret("JWT_SECRET", "test-directory-secret-value");
        ScopedEnv itunes("ITUNES_SEARCH_URL", nullptr);
        ScopedEnv radio("RADIO_BROWSER_URL", nullptr);
        Config config;
        CHECK(config.itunesSearchUrl.empty() && config.radioBrowserUrl.empty(), "directory defaults disabled");
        CHECK(Config::loadFromEnv(config).empty(), "load default config");
        CHECK(config.itunesSearchUrl.empty() && config.radioBrowserUrl.empty(), "unset directories disabled");

        for (const char* disabled : {"", " \t\r\n "}) {
            itunes.set("  http://127.0.0.1:12345/podcasts \t");
            radio.set("  http://127.0.0.1:12345/stations \t");
            CHECK(Config::loadFromEnv(config).empty(), "load explicit directory URLs");
            CHECK(config.itunesSearchUrl == "http://127.0.0.1:12345/podcasts", "trim explicit podcast URL");
            CHECK(config.radioBrowserUrl == "http://127.0.0.1:12345/stations", "trim explicit radio URL");
            itunes.set(disabled);
            radio.set(disabled);
            CHECK(Config::loadFromEnv(config).empty(), "reload empty directory URLs");
            CHECK(config.itunesSearchUrl.empty() && config.radioBrowserUrl.empty(), "empty values clear reused Config");
        }
        config.itunesSearchUrl = "http://127.0.0.1:12345/stale";
        config.radioBrowserUrl = "http://127.0.0.1:12345/stale";
        itunes.set(nullptr);
        radio.set(nullptr);
        CHECK(Config::loadFromEnv(config).empty(), "reload unset directory URLs");
        CHECK(config.itunesSearchUrl.empty() && config.radioBrowserUrl.empty(), "unset values clear reused Config");
    }

    // ---- JSON ----
    {
        Json obj = Json::makeObject().set("a", 1).set("b", "文本").set("c", true).set("d", 1.5);
        Json nested = Json::makeArray().push(1).push(2);
        obj.set("arr", nested);
        Json parsed;
        CHECK(Json::parse(obj.dump(), parsed), "json parse");
        CHECK(parsed.get("a").asInt() == 1, "json int");
        CHECK(parsed.get("b").asString() == "文本", "json utf8");
        CHECK(parsed.get("c").asBool(), "json bool");
        CHECK(parsed.get("arr").size() == 2, "json array size");

        Json bad;
        CHECK(!Json::parse("{bad json", bad), "json rejects malformed");
        CHECK(!Json::parse("{\"a\":1} trailing", bad), "json rejects trailing");
    }

    // ---- Base64 / SHA256 ----
    {
        const std::string raw = "hello world";
        CHECK(util::base64UrlDecode(util::base64UrlEncode(raw)) == raw, "base64url round-trip");
        CHECK(util::sha256Hex("abc").substr(0, 8) == "ba7816bf", "sha256 known");
        CHECK(util::sha256Hex("abc").size() == 64, "sha256 length");
    }

    // ---- URL ----
    {
        CHECK(util::urlEncode("中文 a&b") == "%E4%B8%AD%E6%96%87%20a%26b", "url encode");
        CHECK(util::urlDecode("%E4%B8%AD%E6%96%87") == "中文", "url decode");
    }

    // ---- XML ----
    {
        const std::string feed =
            "<?xml version=\"1.0\"?><rss><channel><title>中文 &amp; 测试</title>"
            "<itunes:duration xmlns:itunes=\"http://x\">12:34</itunes:duration>"
            "<item><title>第一集</title><enclosure url=\"https://x/1.mp3\" type=\"audio/mpeg\"/></item>"
            "<item><title>无音频</title></item></channel></rss>";
        auto result = xml::parse(feed);
        CHECK(result.ok, "xml parse ok");
        const auto* channel = result.root.child("channel");
        CHECK(channel != nullptr, "xml channel");
        if (channel) {
            CHECK(channel->childText("title") == "中文 & 测试", "xml entity");
            CHECK(channel->childrenNamed("item").size() == 2, "xml item count");
            CHECK(channel->childText("itunes:duration") == "12:34", "xml prefixed tag");
        }
        auto bad = xml::parse("<html><body>x</body></html>");
        CHECK(!bad.root.child("channel"), "xml non-feed has no channel");
    }

    // ---- UTF-8 ----
    {
        CHECK(util::isValidUtf8("正常中文"), "utf8 valid");
        CHECK(!util::isValidUtf8(std::string("\xFF\xFE")), "utf8 invalid");
    }

    // ---- 时间 ----
    {
        const auto now = util::nowIso();
        CHECK(now.size() >= 20 && now.back() == 'Z', "iso8601 format");
        const auto normalized = util::normalizeTime("2024-11-12 10:00:00");
        CHECK(normalized.substr(0, 10) == "2024-11-12", "normalize time");
        const auto fallback = util::normalizeTime("not-a-date");
        CHECK(!fallback.empty(), "normalize time fallback");
    }

    // ---- 字符串工具 ----
    {
        CHECK(util::trim("  x  ") == "x", "trim");
        CHECK(util::split("a,b,c", ',').size() == 3, "split");
        CHECK(util::startsWith("abcdef", "abc"), "startsWith");
        CHECK(util::endsWith("abcdef", "def"), "endsWith");
        CHECK(util::toLower("ABC") == "abc", "toLower");
    }

    if (g_failures == 0) {
        std::printf("all tests passed\n");
        return 0;
    }
    std::printf("%d test(s) failed\n", g_failures);
    return 1;
}
