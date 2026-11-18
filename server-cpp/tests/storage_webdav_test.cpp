#include "storage/source.hpp"

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <map>
#include <string>
#include <string_view>

#if defined(PODCAST_ENABLE_WEBDAV) && PODCAST_ENABLE_WEBDAV
#include <curl/curl.h>

// The loopback fixture's CA is supplied only to this test executable. Do not
// change certificate validation or the trust store used by the production binary.
extern "C" CURL* __real_curl_easy_init();
extern "C" CURL* __wrap_curl_easy_init() {
    auto* easy = __real_curl_easy_init();
    const char* ca = std::getenv("PODCAST_WEBDAV_TEST_CA");
    if (easy && ca && curl_easy_setopt(easy, CURLOPT_CAINFO, ca) != CURLE_OK) {
        curl_easy_cleanup(easy);
        return nullptr;
    }
    return easy;
}
#endif

#define REQUIRE(expr) do { if (!(expr)) { \
    std::cerr << "WebDAV fixture check failed on line " << __LINE__ << ": " #expr "\n"; \
    std::exit(1); \
} } while (false)

int main(int argc, char** argv) {
    using podcast::storage::webDavSource;
#if defined(PODCAST_ENABLE_WEBDAV) && PODCAST_ENABLE_WEBDAV
    REQUIRE(argc == 3);
    const std::string endpoint = argv[1];
    const std::string ca = argv[2];
    const std::string base = endpoint + "/isolated%20root";
    REQUIRE(!webDavSource("http://127.0.0.1", "u", "p"));
    REQUIRE(!webDavSource("https://u:p@127.0.0.1", "u", "p"));
    REQUIRE(!webDavSource(base + "?", "u", "p"));
    REQUIRE(!webDavSource(base + "#", "u", "p"));
    REQUIRE(!webDavSource(base + "/%2e%2e", "u", "p"));
    REQUIRE(!webDavSource(base + "/a%2Fb", "u", "p"));
    REQUIRE(!webDavSource(base, "bad:user", "p"));
    auto source = webDavSource(base, "fixture", "fake:password");
    REQUIRE(source);
    REQUIRE(unsetenv("PODCAST_WEBDAV_TEST_CA") == 0);
    REQUIRE(!source->list(""));  // Without the generated CA, the certificate is untrusted.
    REQUIRE(setenv("PODCAST_WEBDAV_TEST_CA", ca.c_str(), 1) == 0);
    const auto wrongHost = webDavSource("https://localhost" + base.substr(17), "fixture", "fake:password");
    REQUIRE(wrongHost && !wrongHost->list(""));  // CA is trusted; SAN contains only 127.0.0.1.
    const auto unauthorized = webDavSource(base, "fixture", "wrong");
    REQUIRE(unauthorized && !unauthorized->list(""));
    const auto entries = source->list("");
    REQUIRE(entries && entries->size() == 3);
    std::map<std::string, podcast::storage::Entry> byName;
    for (const auto& entry : *entries) byName.emplace(entry.name, entry);
    REQUIRE(byName.contains("dir") && byName.at("dir").directory && byName.at("dir").size == 0);
    const std::string audioName = "音 声%?.mp3";
    REQUIRE(byName.contains(audioName) && !byName.at(audioName).directory && byName.at(audioName).size == 8);
    REQUIRE(byName.contains("abs+name") && !byName.at("abs+name").directory && byName.at("abs+name").size == 1);
    const auto nested = source->list("dir");
    REQUIRE(nested && nested->size() == 1 && nested->front().name == "inside.mp3");
    REQUIRE(!source->list("../other"));
    REQUIRE(!source->list("dir//sub"));
    REQUIRE(!source->list("redirect"));
    REQUIRE(!source->list("dtd"));
    REQUIRE(!source->list("external-dtd"));
    REQUIRE(!source->list("utf16-dtd"));
    REQUIRE(!source->list("badxml"));
    REQUIRE(!source->list("huge"));
    REQUIRE(!source->list("chunked-huge"));
    const auto atLimit = source->list("max");
    REQUIRE(atLimit && atLimit->size() == 500);
    REQUIRE(!source->list("many"));
    REQUIRE(!source->list("many-invalid"));
    REQUIRE(!source->open(""));
    REQUIRE(!source->open("https://127.0.0.1/file"));
    REQUIRE(!source->open("%2Fsecret"));
    REQUIRE(!source->open("badhead"));
    REQUIRE(!source->open("unknown-length"));
    REQUIRE(!source->open("redirect-head"));

    auto file = source->open(audioName);
    REQUIRE(file && file->size == 8);
    std::string bytes;
    const auto sink = [&](std::string_view chunk) { bytes += chunk; return true; };
    REQUIRE(file->read(2, 3, sink) && bytes == "cde");
    bytes.clear();
    REQUIRE(file->read(6, UINT64_MAX, sink) && bytes == "gh");
    bytes.clear();
    REQUIRE(file->read(8, 10, {}) && file->read(1, 0, {}) && !file->read(9, 0, sink) && bytes.empty());
    REQUIRE(!file->read(0, 4, [](std::string_view) { return false; }));
    REQUIRE(!file->read(0, 4, [](std::string_view) -> bool { throw 1; }));
    const auto empty = source->open("empty");
    REQUIRE(empty && empty->size == 0 && empty->read(0, 5, {}));
    const auto full = source->open("full-200");
    REQUIRE(full && !full->read(1, 2, sink) && bytes.empty());
    REQUIRE(full->read(0, 9, sink) && bytes == "abcdefgh");
    bytes.clear();
    const auto chunked = source->open("chunked");
    REQUIRE(chunked && chunked->read(2, 3, sink) && bytes == "cde");
    bytes.clear();
    for (const auto* path : {"wrong-range", "wrong-total", "missing-range", "wrong-length", "encoded",
                             "multipart", "ambiguous-length", "ambiguous-framing", "404", "redirect-read"}) {
        const auto rejection = source->open(path);
        REQUIRE(rejection && !rejection->read(2, 3, sink) && bytes.empty());
    }
    const auto truncated = source->open("truncated");
    REQUIRE(truncated && !truncated->read(2, 3, sink) && bytes.size() < 3);
    bytes.clear();
    const auto oversized = source->open("oversized");
    REQUIRE(oversized && !oversized->read(2, 3, sink) && bytes.size() <= 3);
    source.reset();  // A File owns the connection settings it needs to outlive its Source.
    bytes.clear();
    REQUIRE(file->read(0, 8, sink) && bytes == "abcdefgh");
    std::cout << "WebDAV TLS fixture passed\n";
#else
    (void)argc;
    (void)argv;
    REQUIRE(!webDavSource("https://127.0.0.1/dav", "u", "p"));
    std::cout << "WebDAV disabled stub passed\n";
#endif
}
