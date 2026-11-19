#include "PodcastNativeCache.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
constexpr size_t digest_length = 64;
constexpr size_t max_name = digest_length + sizeof(".audio") - 1;
constexpr char part_suffix[] = ".part";
constexpr const char *extensions[] = {".mp3", ".m4a", ".mp4", ".aac", ".ogg", ".opus",
    ".wav", ".flac", ".aiff", ".caf", ".webm", ".m4b", ".wma", ".audio"};

bool final_name_ok(const char *name, size_t length) {
    if (!name || length <= digest_length || length > max_name) return false;
    for (size_t i = 0; i < digest_length; ++i) {
        char ch = name[i];
        if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
    }
    for (const char *suffix : extensions) {
        size_t n = strlen(suffix);
        if (length == digest_length + n && memcmp(name + digest_length, suffix, n) == 0) return true;
    }
    return false;
}

bool partial_name_ok(const char *name, size_t length) {
    constexpr size_t suffix_length = sizeof(part_suffix) - 1;
    return length > suffix_length && final_name_ok(name, length - suffix_length) &&
        memcmp(name + length - suffix_length, part_suffix, suffix_length) == 0;
}

bool matches(const uint8_t *bytes, size_t size, size_t offset,
             const void *prefix, size_t length) {
    return offset <= size && length <= size - offset && memcmp(bytes + offset, prefix, length) == 0;
}

bool has_frame_sync(const uint8_t *bytes) {
    if (bytes[0] != 0xff || (bytes[1] & 0xe0) != 0xe0) return false;
    const unsigned layer = (bytes[1] >> 1) & 3;
    if (!layer) return ((bytes[2] >> 2) & 15) <= 12; // AAC ADTS: valid sample-rate index.
    const unsigned version = (bytes[1] >> 3) & 3;
    const unsigned bitrate = bytes[2] >> 4;
    const unsigned sample_rate = (bytes[2] >> 2) & 3;
    return version != 1 && bitrate != 15 && sample_rate != 3; // MPEG audio.
}

int open_directory(const char *directory) {
    if (!directory || !*directory) { errno = EINVAL; return -1; }
    return open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

bool regular_file(int directory, const char *name, struct stat *info) {
    return fstatat(directory, name, info, AT_SYMLINK_NOFOLLOW) == 0 &&
        S_ISREG(info->st_mode) && info->st_nlink == 1;
}

bool keep_list_matches(const char *names, const char *needle, size_t length) {
    for (const char *start = names; *start;) {
        const char *end = strchr(start, '\n');
        size_t count = end ? static_cast<size_t>(end - start) : strlen(start);
        if (length == count && memcmp(start, needle, count) == 0) return true;
        if (!end) break;
        start = end + 1;
    }
    return false;
}

bool valid_keep_list(const char *names) {
    if (!names) return false;
    for (const char *start = names; *start;) {
        const char *end = strchr(start, '\n');
        size_t length = end ? static_cast<size_t>(end - start) : strlen(start);
        if (!final_name_ok(start, length)) return false;
        if (!end) return true;
        start = end + 1;
    }
    return true;
}
} // namespace

int32_t podcast_cache_target_count(int32_t policy, int32_t available) {
    if (available <= 0) return 0;
    if (policy == -1) return available;
    if (policy != 5 && policy != 10) return 0;
    return policy < available ? policy : available;
}

int32_t podcast_cache_has_media_signature(const uint8_t *bytes, int32_t length) {
    if (!bytes || length < 4) return 0;
    const size_t size = static_cast<size_t>(length > 16 ? 16 : length);
    constexpr uint8_t asf[] = {0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11,
                              0xa6, 0xd9, 0x00, 0xaa, 0x00, 0x62, 0xce, 0x6c};
    constexpr uint8_t ebml[] = {0x1a, 0x45, 0xdf, 0xa3};
    return matches(bytes, size, 0, "ID3", 3) || matches(bytes, size, 0, "OggS", 4) ||
        matches(bytes, size, 0, "fLaC", 4) || matches(bytes, size, 0, "caff", 4) ||
        matches(bytes, size, 4, "ftyp", 4) || matches(bytes, size, 4, "moov", 4) ||
        matches(bytes, size, 4, "mdat", 4) ||
        ((matches(bytes, size, 0, "RIFF", 4) || matches(bytes, size, 0, "RF64", 4)) &&
            matches(bytes, size, 8, "WAVE", 4)) ||
        (matches(bytes, size, 0, "FORM", 4) &&
            (matches(bytes, size, 8, "AIFF", 4) || matches(bytes, size, 8, "AIFC", 4))) ||
        matches(bytes, size, 0, ebml, sizeof(ebml)) || matches(bytes, size, 0, asf, sizeof(asf)) ||
        has_frame_sync(bytes);
}

int64_t podcast_cache_file_size(const char *directory, const char *name) {
    if (!name || !final_name_ok(name, strlen(name))) return 0;
    int fd = open_directory(directory);
    if (fd < 0) return 0;
    struct stat info;
    int64_t size = regular_file(fd, name, &info) && info.st_size > 0 ? info.st_size : 0;
    close(fd);
    return size;
}

int32_t podcast_cache_commit(const char *directory, const char *part_name,
                             const char *final_name, int64_t expected_size) {
    if (!part_name || !final_name || expected_size < -1) return -1;
    size_t length = strlen(final_name);
    if (!final_name_ok(final_name, length) || strlen(part_name) != length + sizeof(part_suffix) - 1 ||
        memcmp(part_name, final_name, length) != 0 || strcmp(part_name + length, part_suffix) != 0) return -1;
    int fd = open_directory(directory);
    if (fd < 0) return -1;
    struct stat info;
    int32_t result = -1;
    if (regular_file(fd, part_name, &info) && info.st_size > 0 &&
        (expected_size == -1 || info.st_size == expected_size)) {
        // AT_SYMLINK_NOFOLLOW on the source, renameat never follows the destination.
        result = renameat(fd, part_name, fd, final_name);
    }
    close(fd);
    return result;
}

int32_t podcast_cache_prune(const char *directory, const char *keep_names) {
    if (!valid_keep_list(keep_names)) return -1;
    int fd = open_directory(directory);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    DIR *stream = fdopendir(fd);
    if (!stream) { close(fd); return -1; }
    int32_t count = 0;
    bool failed = false;
    for (;;) {
        errno = 0;
        auto *entry = readdir(stream);
        if (!entry) { failed = failed || errno != 0; break; }
        const char *name = entry->d_name;
        size_t length = strlen(name);
        const bool finalized = final_name_ok(name, length);
        if (finalized ? keep_list_matches(keep_names, name, length) : !partial_name_ok(name, length)) continue;
        struct stat info;
        if (!regular_file(fd, name, &info)) continue;
        if (unlinkat(fd, name, 0) == 0) { if (count < INT32_MAX) ++count; }
        else if (errno != ENOENT) failed = true;
    }
    if (closedir(stream) != 0) failed = true;
    return failed ? -1 : count;
}
