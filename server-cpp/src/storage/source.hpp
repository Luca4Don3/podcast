#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace podcast::storage {

struct Entry {
    std::string name;  // One path component, not a full path.
    bool directory = false;
    std::uint64_t size = 0;
};

// The view is valid for the duration of the call. Return false to stop reading.
using Sink = std::function<bool(std::string_view)>;

struct File {
    std::uint64_t size = 0;
    // Read at most length bytes, clamped to size - offset. An offset beyond size,
    // a read failure (including premature EOF), or a sink refusal returns false.
    // An empty range at a valid offset succeeds without calling the sink.
    std::function<bool(std::uint64_t offset, std::uint64_t length, const Sink& sink)> read;
};

class Source {
public:
    virtual ~Source() = default;
    virtual std::optional<std::vector<Entry>> list(std::string_view relativePath) const = 0;
    virtual std::optional<File> open(std::string_view relativePath) const = 0;
};

// Paths are UTF-8 and slash-separated. Empty is valid only when allowRoot is true.
bool validRelativePath(std::string_view path, bool allowRoot);

// Linux: provide a dedicated absolute directory and a trusted /proc mount.
// The administrator must control the configured root's ancestor paths and
// mounts. The configured directory itself must not be / or a symlink.
std::shared_ptr<const Source> localSource(const std::string& root);
std::shared_ptr<const Source> webDavSource(const std::string& baseUrl,
                                          const std::string& username,
                                          const std::string& password);

}  // namespace podcast::storage
