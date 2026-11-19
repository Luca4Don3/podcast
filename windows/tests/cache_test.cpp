#include "PodcastNativeCache.h"

#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <string>

static int failures = 0;
#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #e); ++failures; } } while (0)

static std::wstring utf8_to_wide(const std::string &s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    std::wstring out(static_cast<size_t>(n > 0 ? n - 1 : 0), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &out[0], n);
    return out;
}

static std::string wide_to_utf8(const std::wstring &s) {
    int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(n > 0 ? n : 0), '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n, nullptr, nullptr);
    return out;
}

static std::wstring unique_dir(const std::wstring &parent) {
    wchar_t buffer[MAX_PATH];
    swprintf(buffer, MAX_PATH, L"%s\\podcast-cache-test-%llu-%lu", parent.c_str(),
                  static_cast<unsigned long long>(GetTickCount64()),
                  static_cast<unsigned long>(GetCurrentProcessId()));
    CreateDirectoryW(buffer, nullptr);
    return buffer;
}

static bool write_file(const std::wstring &dir, const std::string &name, const char *bytes) {
    std::wstring path = utf8_to_wide(wide_to_utf8(dir) + "\\" + name);
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool ok = WriteFile(h, bytes, static_cast<DWORD>(std::strlen(bytes)), &written, nullptr) != FALSE;
    CloseHandle(h);
    return ok && written == std::strlen(bytes);
}

static bool exists(const std::wstring &path) {
    return GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES;
}

static void clean_dir(const std::wstring &dir) {
    std::wstring pattern = dir + L"\\*";
    WIN32_FIND_DATAW found;
    HANDLE h = FindFirstFileW(pattern.c_str(), &found);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        if (found.cFileName[0] == L'.') continue;
        DeleteFileW((dir + L"\\" + found.cFileName).c_str());
    } while (FindNextFileW(h, &found));
    FindClose(h);
    RemoveDirectoryW(dir.c_str());
}

int wmain() {
    CHECK(podcast_cache_target_count(0, 20) == 0);
    CHECK(podcast_cache_target_count(5, 20) == 5);
    CHECK(podcast_cache_target_count(10, 4) == 4);
    CHECK(podcast_cache_target_count(-1, 20) == 20);
    CHECK(podcast_cache_target_count(5, -4) == 0);
    CHECK(podcast_cache_target_count(3, 20) == 0);
    const uint8_t mp3[] = {'I', 'D', '3', 4};
    const uint8_t html[] = {'<', '!', 'd', 'o', 'c'};
    CHECK(podcast_cache_has_media_signature(mp3, sizeof(mp3)) == 1);
    CHECK(podcast_cache_has_media_signature(html, sizeof(html)) == 0);
    CHECK(podcast_cache_has_media_signature(nullptr, 16) == 0);

    wchar_t temp[MAX_PATH];
    GetTempPathW(MAX_PATH, temp);
    // Unicode directory exercises the UTF-8 path conversion at the C ABI boundary.
    std::wstring dir = unique_dir(std::wstring(temp) + L"\\podcast-中文-缓存");
    CreateDirectoryW((std::wstring(temp) + L"\\podcast-中文-缓存").c_str(), nullptr);
    const std::string a = std::string(64, 'a') + ".mp3";
    const std::string b = std::string(64, 'b') + ".m4a";
    const std::string part = a + ".part";
    const std::string utf8_dir = wide_to_utf8(dir);

    CHECK(write_file(dir, part, "abc"));
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), a.c_str()) == 0);
    CHECK(podcast_cache_commit(utf8_dir.c_str(), part.c_str(), a.c_str(), 5) == -1);
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), a.c_str()) == 0);
    CHECK(podcast_cache_commit(utf8_dir.c_str(), part.c_str(), a.c_str(), 3) == 0);
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), a.c_str()) == 3);
    CHECK(!exists(utf8_to_wide(wide_to_utf8(dir) + "\\" + part)));
    CHECK(write_file(dir, b, "second"));
    CHECK(write_file(dir, "unrelated.txt", "keep me"));
    CHECK(write_file(dir, "unrelated.part", "orphan"));
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), "../unrelated.txt") == 0);
    CHECK(podcast_cache_prune(utf8_dir.c_str(), "../outside") == -1);
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), b.c_str()) == 6);
    const int removed = podcast_cache_prune(utf8_dir.c_str(), a.c_str());
    CHECK(removed == 2); // b and the orphan .part; the unrelated text file stays.
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), a.c_str()) == 3);
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), b.c_str()) == 0);
    CHECK(exists(utf8_to_wide(wide_to_utf8(dir) + "\\unrelated.txt")));
    CHECK(podcast_cache_prune(utf8_dir.c_str(), "") == 1);
    CHECK(podcast_cache_file_size(utf8_dir.c_str(), a.c_str()) == 0);

    const std::string missing = wide_to_utf8(std::wstring(temp) + L"\\podcast-no-such-directory");
    CHECK(podcast_cache_file_size(missing.c_str(), a.c_str()) == 0);
    CHECK(podcast_cache_prune(missing.c_str(), "") == 0);

    clean_dir(dir);
    RemoveDirectoryW((std::wstring(temp) + L"\\podcast-中文-缓存").c_str());
    std::printf("Windows cache tests: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
