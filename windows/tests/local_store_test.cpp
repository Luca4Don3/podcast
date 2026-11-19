#include "local_store.h"
#include <windows.h>
#include <cstdio>

static int failures = 0;
#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #e); ++failures; } } while (0)

int main() {
    using namespace podcast;
    LocalStore store;
    wchar_t temp[MAX_PATH] = {};
    GetTempPathW(MAX_PATH, temp);
    const std::wstring test_path = std::wstring(temp) + L"podcast-store-test.dat";
    store.set_path_for_test(test_path);
    Feed feed; feed.id = "f1"; feed.url = "https://a.test/feed.xml"; feed.title = "My Show";
    store.addOrUpdateFeed(feed);
    Episode e; e.id = "e1"; e.feed_id = "f1"; e.title = "Hello"; e.audio_url = "https://a.test/a.mp3"; e.published_at = 123;
    store.setEpisodes("f1", {e});
    store.setProgress("e1", 5000, false);
    store.setQueue({"e1", "e2"});
    CHECK(store.save());
    LocalStore loaded; loaded.set_path_for_test(test_path);
    CHECK(loaded.load());
    CHECK(loaded.feeds().size() == 1 && loaded.feeds()[0].title == "My Show");
    CHECK(loaded.episodes().size() == 1 && loaded.episodes()[0].audio_url == "https://a.test/a.mp3");
    CHECK(loaded.progress().size() == 1 && loaded.progress()[0].position_ms == 5000);
    CHECK(loaded.queue().size() == 2 && loaded.queue()[1] == "e2");
    std::printf("Local store: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
