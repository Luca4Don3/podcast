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
        if (!final_name_ok(name, length) || keep_list_matches(keep_names, name, length)) continue;
        struct stat info;
        if (!regular_file(fd, name, &info)) continue;
        if (unlinkat(fd, name, 0) == 0) { if (count < INT32_MAX) ++count; }
        else if (errno != ENOENT) failed = true;
    }
    if (closedir(stream) != 0) failed = true;
    return failed ? -1 : count;
}
