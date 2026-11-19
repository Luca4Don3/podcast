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

    const auto sniff = [](const std::string& value) {
        return podcast_cache_has_media_signature(reinterpret_cast<const uint8_t*>(value.data()),
                                                  static_cast<int32_t>(value.size()));
    };
    CHECK(podcast_cache_has_media_signature(nullptr, 16) == 0);
    CHECK(sniff("ID3") == 0);
    CHECK(sniff("ID3x") == 1);
    CHECK(sniff("OggS") == 1);
    CHECK(sniff("fLaC") == 1);
    CHECK(sniff("caff") == 1);
    CHECK(sniff("0000ftyp") == 1);
    CHECK(sniff("0000moov") == 1);
    CHECK(sniff("RIFFxxxxWAVE") == 1);
    CHECK(sniff("RF64xxxxWAVE") == 1);
    CHECK(sniff("FORMxxxxAIFC") == 1);
    CHECK(sniff("RIFFxxxxWEBP") == 0);
    CHECK(sniff("RIFFxxxxWAV") == 0);
    CHECK(sniff("<html>sign in</html>") == 0);
    CHECK(sniff("#EXTM3U\n#EXTINF:10,") == 0);
    const uint8_t adts[] = {0xff, 0xf1, 0x50, 0x80};
    const uint8_t mpeg[] = {0xff, 0xfb, 0x90, 0x00};
    const uint8_t invalid[] = {0xff, 0xff, 0xff, 0xff};
    const uint8_t asf[] = {0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11,
                           0xa6, 0xd9, 0x00, 0xaa, 0x00, 0x62, 0xce, 0x6c};
    CHECK(podcast_cache_has_media_signature(adts, sizeof(adts)) == 1);
    CHECK(podcast_cache_has_media_signature(mpeg, sizeof(mpeg)) == 1);
    CHECK(podcast_cache_has_media_signature(invalid, sizeof(invalid)) == 0);
    CHECK(podcast_cache_has_media_signature(asf, sizeof(asf)) == 1);
    CHECK(podcast_cache_has_media_signature(asf, sizeof(asf) - 1) == 0);

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
    CHECK(access((directory + "/" + part).c_str(), F_OK) == 0);
    CHECK(make("unrelated.part", "not cache data"));
    CHECK(podcast_cache_prune(path, a.c_str()) == 2);
    CHECK(access((directory + "/" + part).c_str(), F_OK) != 0);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 3);
    CHECK(podcast_cache_file_size(path, b.c_str()) == 0);
    CHECK(symlink((directory + "/unrelated.txt").c_str(), (directory + "/" + b).c_str()) == 0);
    CHECK(podcast_cache_file_size(path, b.c_str()) == 0);
    CHECK(podcast_cache_file_size(path, "../unrelated.txt") == 0);
    CHECK(podcast_cache_prune(path, "") == 1);
    CHECK(podcast_cache_file_size(path, a.c_str()) == 0);
    CHECK(unlink((directory + "/" + b).c_str()) == 0);
    CHECK(unlink((directory + "/unrelated.part").c_str()) == 0);
    CHECK(unlink((directory + "/unrelated.txt").c_str()) == 0);
    CHECK(rmdir(path) == 0);
    CHECK(podcast_cache_prune(path, "") == 0);
    return 0;
}
