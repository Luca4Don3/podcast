#include "PodcastNativeCache.h"

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <string>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

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

#ifdef _WIN32
namespace {
// The cache directory is UTF-8 from the app; file names are strict ASCII hashes.
bool utf8_to_wide(const char *text, std::wstring &out) {
    if (!text || !*text) return false;
    const int need = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, nullptr, 0);
    if (need <= 0) return false;
    out.assign(static_cast<size_t>(need) - 1, L'\0');
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, &out[0], need) == need;
}

bool wide_to_utf8(const std::wstring &text, std::string &out) {
    const int need = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
                                         static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (need <= 0) return false;
    out.assign(static_cast<size_t>(need), '\0');
    return WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
                               static_cast<int>(text.size()), &out[0], need, nullptr, nullptr) == need;
}

std::wstring join(const std::wstring &dir, const std::wstring &name) {
    std::wstring path = dir;
    if (!path.empty() && path.back() != L'\\' && path.back() != L'/') path += L'\\';
    path += name;
    return path;
}

struct Probe { bool valid = false; int64_t size = 0; };

// FILE_FLAG_OPEN_REPARSE_POINT prevents following links; nNumberOfLinks==1 rejects hard links.
Probe probe_file(const std::wstring &path) {
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (handle == INVALID_HANDLE_VALUE) return {};
    BY_HANDLE_FILE_INFORMATION info;
    const bool got = GetFileInformationByHandle(handle, &info) != FALSE;
    CloseHandle(handle);
    if (!got) return {};
    if (info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) return {};
    if (info.nNumberOfLinks != 1) return {};
    Probe result;
    result.valid = true;
    result.size = (static_cast<int64_t>(info.nFileSizeHigh) << 32) | info.nFileSizeLow;
    return result;
}

bool is_private_directory(const std::wstring &dir) {
    std::wstring probe = join(dir, L".");
    HANDLE handle = CreateFileW(probe.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (handle == INVALID_HANDLE_VALUE) return false;
    BY_HANDLE_FILE_INFORMATION info;
    const bool got = GetFileInformationByHandle(handle, &info) != FALSE;
    CloseHandle(handle);
    return got && (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) &&
        !(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT);
}
} // namespace

int64_t podcast_cache_file_size(const char *directory, const char *name) {
    if (!name || !final_name_ok(name, strlen(name))) return 0;
    std::wstring dir, leaf;
    if (!utf8_to_wide(directory, dir) || !utf8_to_wide(name, leaf)) return 0;
    if (!is_private_directory(dir)) return 0;
    Probe file = probe_file(join(dir, leaf));
    return file.valid && file.size > 0 ? file.size : 0;
}

int32_t podcast_cache_commit(const char *directory, const char *part_name,
                             const char *final_name, int64_t expected_size) {
    if (!part_name || !final_name || expected_size < -1) return -1;
    const size_t length = strlen(final_name);
    if (!final_name_ok(final_name, length) || strlen(part_name) != length + sizeof(part_suffix) - 1 ||
        memcmp(part_name, final_name, length) != 0 || strcmp(part_name + length, part_suffix) != 0) return -1;
    std::wstring dir, from, to;
    if (!utf8_to_wide(directory, dir) || !utf8_to_wide(part_name, from) || !utf8_to_wide(final_name, to)) return -1;
    if (!is_private_directory(dir)) return -1;
    Probe source = probe_file(join(dir, from));
    if (!source.valid || source.size <= 0 || (expected_size != -1 && source.size != expected_size)) return -1;
    // Never overwrite an existing link or directory with the published file.
    std::wstring target = join(dir, to);
    const DWORD attributes = GetFileAttributesW(target.c_str());
    if (attributes != INVALID_FILE_ATTRIBUTES &&
        (attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))) return -1;
    return MoveFileExW(join(dir, from).c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING) ? 0 : -1;
}

int32_t podcast_cache_prune(const char *directory, const char *keep_names) {
    if (!valid_keep_list(keep_names)) return -1;
    std::wstring dir;
    if (!utf8_to_wide(directory, dir)) return -1;
    if (!is_private_directory(dir)) {
        const DWORD attributes = GetFileAttributesW(dir.c_str());
        return attributes == INVALID_FILE_ATTRIBUTES ? 0 : -1;
    }
    WIN32_FIND_DATAW found;
    HANDLE listing = FindFirstFileW(join(dir, L"*").c_str(), &found);
    if (listing == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
        return error == ERROR_FILE_NOT_FOUND ? 0 : -1;
    }
    int32_t removed = 0;
    bool failed = false;
    do {
        std::string name;
        if (!wide_to_utf8(found.cFileName, name)) { failed = true; continue; }
        const size_t length = name.size();
        const bool finalized = final_name_ok(name.c_str(), length);
        if (finalized ? keep_list_matches(keep_names, name.c_str(), length)
                      : !partial_name_ok(name.c_str(), length)) continue;
        if (found.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) continue;
        Probe file = probe_file(join(dir, found.cFileName));
        if (!file.valid) continue;
        if (DeleteFileW(join(dir, found.cFileName).c_str())) {
            if (removed < INT32_MAX) ++removed;
        } else if (GetLastError() != ERROR_FILE_NOT_FOUND) failed = true;
    } while (FindNextFileW(listing, &found));
    if (GetLastError() != ERROR_NO_MORE_FILES) failed = true;
    FindClose(listing);
    return failed ? -1 : removed;
}

#else
namespace {
int open_directory(const char *directory) {
    if (!directory || !*directory) { errno = EINVAL; return -1; }
    return open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

bool regular_file(int directory, const char *name, struct stat *info) {
    return fstatat(directory, name, info, AT_SYMLINK_NOFOLLOW) == 0 &&
        S_ISREG(info->st_mode) && info->st_nlink == 1;
}
} // namespace

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
#endif
