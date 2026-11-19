#include "PodcastNativeCache.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "failed at line %d: %s\n", __LINE__, #e); return 1; } } while (0)

int main() {
    CHECK(podcast_cache_target_count(0, 20) == 0);
    CHECK(podcast_cache_target_count(5, 20) == 5);
    CHECK(podcast_cache_target_count(10, 4) == 4);
    CHECK(podcast_cache_target_count(-1, 20) == 20);
    CHECK(podcast_cache_target_count(5, -4) == 0);
    CHECK(podcast_cache_target_count(3, 20) == 0);

    char path[] = "/tmp/podcast-cache-test.XXXXXX";
    CHECK(mkdtemp(path) != nullptr);
    const std::string a = std::string(64, 'a') + ".mp3";
    const std::string b = std::string(64, 'b') + ".m4a";
    const std::string part = a + ".part";
    const std::string directory(path);
    const auto make = [&](const std::string& name, const std::string& value) {
        auto *file = std::fopen((directory + "/" + name).c_str(), "wb");
        if (!file) return false;
        auto written = std::fwrite(value.data(), 1, value.size(), file);
        return std::fclose(file) == 0 && written == value.size();
    };
    CHECK(make(part, "abc"));
    CHECK(podcast_cache_file_size(path, a.c_str()) == 0);
    CHECK(podcast_cache_commit(path, part.c_str(), a.c_str(), 5) == -1);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 0);
    CHECK(podcast_cache_commit(path, part.c_str(), a.c_str(), 3) == 0);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 3);
    CHECK(make(part, ""));
    CHECK(podcast_cache_commit(path, part.c_str(), a.c_str(), -1) == -1);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 3);
    CHECK(make(b, "second"));
    CHECK(make("unrelated.txt", "leave me"));
    CHECK(podcast_cache_prune(path, "../outside") == -1);
    CHECK(podcast_cache_file_size(path, b.c_str()) == 6);
    CHECK(podcast_cache_prune(path, a.c_str()) == 1);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 3);
    CHECK(podcast_cache_file_size(path, b.c_str()) == 0);
    CHECK(symlink((directory + "/unrelated.txt").c_str(), (directory + "/" + b).c_str()) == 0);
    CHECK(podcast_cache_file_size(path, b.c_str()) == 0);
    CHECK(podcast_cache_file_size(path, "../unrelated.txt") == 0);
    CHECK(podcast_cache_prune(path, "") == 1);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 0);
    CHECK(unlink((directory + "/" + b).c_str()) == 0);
    CHECK(unlink((directory + "/" + part).c_str()) == 0);
    CHECK(unlink((directory + "/unrelated.txt").c_str()) == 0);
    CHECK(rmdir(path) == 0);
    CHECK(podcast_cache_prune(path, "") == 0);
    return 0;
}
