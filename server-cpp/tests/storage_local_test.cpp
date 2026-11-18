#include "../src/storage/source.hpp"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>

#include <sys/stat.h>

namespace fs = std::filesystem;
using podcast::storage::Sink;
using podcast::storage::localSource;
using podcast::storage::validRelativePath;

namespace {

void check(bool ok, std::string_view message) {
    if (!ok) throw std::runtime_error(std::string(message));
}

class TempDirectory {
public:
    TempDirectory() {
        char path[] = "/tmp/podcast-storage-local-XXXXXX";
        check(::mkdtemp(path) != nullptr, "create temporary test directory");
        path_ = path;
    }
    ~TempDirectory() {
        std::error_code ignored;
        fs::remove_all(path_, ignored);
    }
    const fs::path& path() const { return path_; }

private:
    fs::path path_;
};

void writeFile(const fs::path& path, std::string_view bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    out.close();
    check(static_cast<bool>(out), "write test fixture");
}

void testLocal(const fs::path& temp) {
    const auto root = temp / "media";
    const auto outside = temp / "outside";
    fs::create_directories(root / "nested");
    fs::create_directory(outside);
    writeFile(outside / "private", "outside root");

    const std::string utf8 = "\xe6\x92\xad\xe5\xae\xa2\xf0\x9f\x8e\xa7.mp3";
    std::string data(170'000, '\0');
    for (std::size_t i = 0; i < data.size(); ++i) data[i] = static_cast<char>((i / 5 + i) % 127);
    writeFile(root / "nested" / utf8, data);
    writeFile(root / "track", "track");
    writeFile(root / "empty", "");
    writeFile(root / "bad\xff", "invalid name");
    writeFile(root / "bad\n", "invalid name");
    writeFile(root / "bad\\", "invalid name");
    fs::create_symlink(outside / "private", root / "file-link");
    fs::create_directory_symlink(outside, root / "dir-link");
    fs::create_directory_symlink(root, temp / "root-link");
    check(::mkfifo((root / "fifo").c_str(), 0600) == 0, "create FIFO fixture");

    check(!localSource("") && !localSource("media") && !localSource("/") &&
          !localSource("///") && !localSource("/tmp/.."), "reject invalid and system roots");
    check(!localSource((root / "track").string()) && !localSource((root / "missing").string()),
          "reject file and nonexistent roots");
    const std::string link = (temp / "root-link").string();
    check(!localSource(link) && !localSource(link + "/") && !localSource(link + "/."),
          "do not follow final root symlink via suffixes");
    check(!localSource(root.string() + std::string("\0shadow", 7)), "reject NUL in configured root");
    auto source = localSource(root.string() + "/");
    check(static_cast<bool>(source), "open real absolute root");

    check(validRelativePath("", true) && !validRelativePath("", false), "root path contract");
    const std::string nested = "nested/" + utf8;
    check(validRelativePath(nested, false) && validRelativePath("a..b", false), "allow valid UTF-8 paths");
    const std::string_view badPaths[] = {
        "/track", "//track", "track/", "nested//track", ".", "..", "./track", "nested/../track",
        "nested/.", "../outside/private", "nested\\track", "bad\n", "bad\x7f", "bad\xc2\x85",
        std::string_view("track\0suffix", 12), "\x80", "\xc0\xaf", "\xe0\x80\xaf", "\xed\xa0\x80",
        "\xf0\x9f\x8e", "\xf4\x90\x80\x80", "\xff",
    };
    for (const auto path : badPaths) {
        check(!validRelativePath(path, true) && !validRelativePath(path, false), "reject invalid path");
        check(!source->list(path) && !source->open(path), "operations must reject invalid path");
    }

    const auto entries = source->list("");
    check(entries && entries->size() == 3, "root listing filters symlinks, FIFO and invalid names");
    check((*entries)[0].name == "empty" && !(*entries)[0].directory && (*entries)[0].size == 0 &&
          (*entries)[1].name == "nested" && (*entries)[1].directory && (*entries)[1].size == 0 &&
          (*entries)[2].name == "track" && !(*entries)[2].directory && (*entries)[2].size == 5,
          "file and directory metadata");
    const auto again = source->list("");
    check(again && again->size() == entries->size(), "closing a listing leaves root usable");
    const auto child = source->list("nested");
    check(child && child->size() == 1 && child->front().name == utf8 &&
          !child->front().directory && child->front().size == data.size(), "list nested UTF-8 file");
    check(!source->list("track") && !source->list("dir-link") && !source->list("missing") &&
          !source->open("") && !source->open("nested") && !source->open("file-link") &&
          !source->open("dir-link/private") && !source->open("missing") && !source->open("fifo"),
          "reject incorrect types, missing paths, symlinks and FIFO");

    auto file = source->open(nested);
    check(file && file->size == data.size(), "open nested UTF-8 file");
    std::string copied;
    int calls = 0;
    Sink append = [&](std::string_view part) {
        ++calls;
        copied += part;
        return true;
    };
    check(file->read(37, 140'000, append) && copied == data.substr(37, 140'000) && calls > 1,
          "pread by chunks with exact offset and length");
    copied.clear();
    check(file->read(data.size() - 9, std::numeric_limits<std::uint64_t>::max(), append) &&
          copied == data.substr(data.size() - 9), "clamp range to EOF without overflow");
    check(file->read(file->size, 1, {}) && file->read(2, 0, {}) &&
          !file->read(file->size + 1, 1, {}) && !file->read(0, 1, {}), "empty and invalid ranges");
    const auto empty = source->open("empty");
    check(empty && empty->size == 0 && empty->read(0, 1, {}), "read empty file");
    calls = 0;
    check(!file->read(0, file->size, [&](std::string_view) { ++calls; return false; }) && calls == 1,
          "sink refusal stops immediately");

    fs::create_directory(root / "large");
    for (int i = 0; i < 500; ++i) writeFile(root / "large" / std::to_string(i), "");
    const auto full = source->list("large");
    check(full && full->size() == 500, "allow exactly 500 entries");
    fs::create_symlink(outside / "private", root / "large/overflow");
    check(!source->list("large"), "fail instead of silently truncating a directory scan");

    const auto moved = temp / "moved";
    fs::rename(root, moved);
    fs::create_directory_symlink(outside, root);
    check(source->open("track").has_value() && !source->open("private"),
          "root descriptor stays pinned when configured path is replaced");
    source.reset();
    fs::rename(moved / "nested" / utf8, moved / "original");
    writeFile(moved / "nested" / utf8, "replacement");
    copied.clear();
    check(file->read(3, 12, append) && copied == data.substr(3, 12),
          "file descriptor survives replacement and source destruction");
    fs::resize_file(moved / "original", 1);
    check(!file->read(0, 2, append), "report premature EOF when opened file shrinks");
}

}  // namespace

int main() {
    try {
        const TempDirectory temp;
        testLocal(temp.path());
        std::cout << "storage_local_test: PASS\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "storage_local_test: " << e.what() << '\n';
        return 1;
    }
}
