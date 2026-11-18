#include "source.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <utility>

#include <dirent.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

namespace podcast::storage {
namespace {

constexpr std::size_t kEntryLimit = 500;
constexpr int kDirectoryPathFlags = O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;

class Fd {
public:
    explicit Fd(int fd = -1) noexcept : fd_(fd) {}
    ~Fd() { if (fd_ >= 0) ::close(fd_); }  // Do not retry close on Linux.

    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd(Fd&& other) noexcept : fd_(other.release()) {}
    Fd& operator=(Fd&& other) noexcept {
        Fd replacement(std::move(other));
        std::swap(fd_, replacement.fd_);
        return *this;
    }

    int get() const noexcept { return fd_; }
    int release() noexcept { return std::exchange(fd_, -1); }
    explicit operator bool() const noexcept { return fd_ >= 0; }

private:
    int fd_;
};

struct DirCloser {
    void operator()(DIR* dir) const noexcept { ::closedir(dir); }
};

bool validUtf8(std::string_view path) {
    for (std::size_t i = 0; i < path.size();) {
        std::uint32_t code = static_cast<unsigned char>(path[i++]);
        std::uint32_t minimum = 0;
        std::size_t tail = 0;
        if (code >= 0x80) {
            if ((code & 0xe0) == 0xc0) { code &= 0x1f; minimum = 0x80; tail = 1; }
            else if ((code & 0xf0) == 0xe0) { code &= 0x0f; minimum = 0x800; tail = 2; }
            else if ((code & 0xf8) == 0xf0) { code &= 0x07; minimum = 0x10000; tail = 3; }
            else return false;
            if (tail > path.size() - i) return false;
            while (tail != 0) {
                --tail;
                const auto byte = static_cast<unsigned char>(path[i++]);
                if ((byte & 0xc0) != 0x80) return false;
                code = (code << 6) | (byte & 0x3f);
            }
        }

        // Reject non-scalars, overlong encodings and Unicode C0/C1 controls.
        if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff) ||
            code < 0x20 || (code >= 0x7f && code <= 0x9f)) return false;
    }
    return true;
}

bool sameInode(const struct stat& a, const struct stat& b) {
    return a.st_dev == b.st_dev && a.st_ino == b.st_ino;
}

Fd openRegular(int parent, const char* name, std::uint64_t& size) {
    // O_PATH pins the inode without invoking a device or FIFO's open operation.
    // Do not reopen the original name after the type check: it could have changed.
    Fd pinned(::openat(parent, name, O_PATH | O_NOFOLLOW | O_CLOEXEC));
    struct stat original{};
    if (!pinned || ::fstat(pinned.get(), &original) != 0 || !S_ISREG(original.st_mode)) {
        return Fd();
    }

    // Follow only the procfs magic link for our own pinned descriptor.
    Fd proc(::open("/proc/self/fd", kDirectoryPathFlags));
    struct statfs filesystem{};
    if (!proc || ::fstatfs(proc.get(), &filesystem) != 0 || filesystem.f_type != PROC_SUPER_MAGIC) {
        return Fd();
    }
    const std::string descriptor = std::to_string(pinned.get());
    Fd file(::openat(proc.get(), descriptor.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOCTTY));
    struct stat actual{};
    if (!file || ::fstat(file.get(), &actual) != 0 || !sameInode(original, actual) ||
        !S_ISREG(actual.st_mode) || actual.st_size < 0) return Fd();
    size = static_cast<std::uint64_t>(actual.st_size);
    return file;
}

bool readRange(std::shared_ptr<const Fd> file, std::uint64_t size, std::uint64_t offset,
               std::uint64_t length, const Sink& sink) {
    if (offset > size) return false;
    auto remaining = std::min(length, size - offset);  // Avoid offset + length overflow.
    if (remaining == 0) return true;
    if (!sink) return false;

    std::array<char, 64 * 1024> buffer;
    while (remaining != 0) {
        const auto want = static_cast<std::size_t>(std::min<std::uint64_t>(remaining, buffer.size()));
        ssize_t count;
        do {
            // offset is bounded by the size from fstat, which fits off_t.
            count = ::pread(file->get(), buffer.data(), want, static_cast<off_t>(offset));
        } while (count < 0 && errno == EINTR);
        if (count <= 0 || !sink(std::string_view(buffer.data(), static_cast<std::size_t>(count)))) {
            return false;
        }
        const auto bytesRead = static_cast<std::uint64_t>(count);
        offset += bytesRead;
        remaining -= bytesRead;
    }
    return true;
}

class LocalSource final : public Source {
public:
    explicit LocalSource(Fd root) : root_(std::move(root)) {}

    std::optional<std::vector<Entry>> list(std::string_view relativePath) const override {
        if (!validRelativePath(relativePath, true)) return std::nullopt;
        Fd directory = resolveDirectory(relativePath);
        if (!directory) return std::nullopt;

        // A new open file description gives each listing its own readdir position.
        Fd scan(::openat(directory.get(), ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW |
                         O_NONBLOCK | O_CLOEXEC));
        if (!scan) return std::nullopt;
        std::unique_ptr<DIR, DirCloser> stream(::fdopendir(scan.get()));
        if (!stream) return std::nullopt;
        scan.release();  // fdopendir owns the descriptor only on success.

        std::vector<Entry> entries;
        std::size_t scanned = 0;
        for (;;) {
            errno = 0;
            const auto* item = ::readdir(stream.get());
            if (!item) {
                if (errno != 0) return std::nullopt;
                break;
            }
            const std::string_view name(item->d_name);
            if (name == "." || name == "..") continue;
            if (++scanned > kEntryLimit) return std::nullopt;
            if (!validRelativePath(name, false)) continue;

            struct stat info{};
            if (::fstatat(directory.get(), item->d_name, &info, AT_SYMLINK_NOFOLLOW) != 0) {
                return std::nullopt;
            }
            if (S_ISDIR(info.st_mode)) {
                entries.push_back({std::string(name), true, 0});
            } else if (S_ISREG(info.st_mode)) {
                if (info.st_size < 0) return std::nullopt;
                entries.push_back({std::string(name), false, static_cast<std::uint64_t>(info.st_size)});
            }
        }
        std::sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& b) {
            return a.name < b.name;
        });
        return entries;
    }

    std::optional<File> open(std::string_view relativePath) const override {
        if (!validRelativePath(relativePath, false)) return std::nullopt;
        const auto slash = relativePath.rfind('/');
        Fd parent = resolveDirectory(slash == std::string_view::npos ? std::string_view{} :
                                     relativePath.substr(0, slash));
        if (!parent) return std::nullopt;
        const std::string name(slash == std::string_view::npos ? relativePath : relativePath.substr(slash + 1));
        std::uint64_t size = 0;
        Fd file = openRegular(parent.get(), name.c_str(), size);
        if (!file) return std::nullopt;

        return File{size, [fd = std::make_shared<const Fd>(std::move(file)), size](
                              std::uint64_t offset, std::uint64_t length, const Sink& sink) {
            return readRange(fd, size, offset, length, sink);
        }};
    }

private:
    // path was validated by the caller, so each syscall handles just one component.
    Fd resolveDirectory(std::string_view path) const {
        if (path.empty()) return Fd(::openat(root_.get(), ".", kDirectoryPathFlags));
        Fd directory;
        for (;;) {
            const auto slash = path.find('/');
            const std::string name(path.substr(0, slash));
            const int parent = directory ? directory.get() : root_.get();
            directory = Fd(::openat(parent, name.c_str(), kDirectoryPathFlags));
            if (!directory || slash == std::string_view::npos) return directory;
            path.remove_prefix(slash + 1);
        }
    }

    const Fd root_;
};

}  // namespace

bool validRelativePath(std::string_view path, bool allowRoot) {
    if (path.empty()) return allowRoot;
    if (path.find('\\') != std::string_view::npos || !validUtf8(path)) return false;
    for (;;) {
        const auto slash = path.find('/');
        const auto part = path.substr(0, slash);
        if (part.empty() || part == "." || part == "..") return false;
        if (slash == std::string_view::npos) return true;
        path.remove_prefix(slash + 1);
    }
}

std::shared_ptr<const Source> localSource(const std::string& root) {
    std::string_view path(root);
    if (path.empty() || path.front() != '/') return {};
    // Trailing slash could make open resolve a symlink as a directory; strip it first.
    while (!path.empty() && path.back() == '/') path.remove_suffix(1);
    if (path.empty() || !validRelativePath(path.substr(1), false)) return {};
    const std::string normalized(path);
    Fd directory(::open(normalized.c_str(), kDirectoryPathFlags));
    struct stat configured{};
    struct stat filesystemRoot{};
    if (!directory || ::fstat(directory.get(), &configured) != 0 ||
        ::stat("/", &filesystemRoot) != 0 || sameInode(configured, filesystemRoot)) return {};
    return std::make_shared<LocalSource>(std::move(directory));
}

}  // namespace podcast::storage
