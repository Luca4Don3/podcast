#include "local_store.h"

#include <windows.h>
#include <shlobj.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>

namespace podcast {
namespace {

std::string esc(const std::string& value) {
    std::string out;
    for (char c : value) {
        if (c == '\\' || c == '\t' || c == '\n') out += '\\';
        if (c == '\t') out += 't';
        else if (c == '\n') out += 'n';
        else out += c;
    }
    return out;
}

std::string unesc(const std::string& value) {
    std::string out;
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (value[i] != '\\' || i + 1 >= value.size()) { out += value[i]; continue; }
        const char next = value[++i];
        out += next == 't' ? '\t' : next == 'n' ? '\n' : next;
    }
    return out;
}

std::vector<std::string> split(const std::string& line) {
    std::vector<std::string> fields;
    std::string current;
    for (std::size_t i = 0; i < line.size(); ++i) {
        if (line[i] == '\\' && i + 1 < line.size()) { current += line[i]; current += line[++i]; continue; }
        if (line[i] == '\t') { fields.push_back(current); current.clear(); continue; }
        current += line[i];
    }
    fields.push_back(current);
    return fields;
}

std::wstring default_path() {
    wchar_t* base = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &base)) || !base)
        return L"podcast-library.dat";
    std::wstring path = base;
    CoTaskMemFree(base);
    path += L"\\Podcast\\library.dat";
    return path;
}

} // namespace

LocalStore::LocalStore() : path_(default_path()) {}

bool LocalStore::load() {
    std::ifstream file(path_);
    if (!file) return false;
    feeds_.clear(); episodes_.clear(); progress_.clear(); queue_.clear();
    std::string line;
    while (std::getline(file, line)) {
        if (line.empty()) continue;
        const auto fields = split(line);
        if (fields.empty()) continue;
        if (fields[0] == "F" && fields.size() >= 4) {
            Feed feed; feed.id = unesc(fields[1]); feed.url = unesc(fields[2]); feed.title = unesc(fields[3]);
            feeds_.push_back(feed);
        } else if (fields[0] == "E" && fields.size() >= 7) {
            Episode e; e.id = unesc(fields[1]); e.feed_id = unesc(fields[2]); e.title = unesc(fields[3]);
            e.audio_url = unesc(fields[4]); e.published_at = std::strtoll(fields[5].c_str(), nullptr, 10);
            e.duration_ms = std::strtoll(fields[6].c_str(), nullptr, 10);
            episodes_.push_back(e);
        } else if (fields[0] == "P" && fields.size() >= 4) {
            Progress p; p.episode_id = unesc(fields[1]); p.position_ms = std::strtoll(fields[2].c_str(), nullptr, 10);
            p.completed = fields[3] == "1"; progress_.push_back(p);
        } else if (fields[0] == "Q" && fields.size() >= 2) {
            queue_.push_back(unesc(fields[1]));
        }
    }
    return true;
}

bool LocalStore::save() const {
    std::wstring directory = path_;
    const std::size_t slash = directory.find_last_of(L"\\/");
    if (slash != std::wstring::npos) directory.resize(slash);
    CreateDirectoryW(directory.c_str(), nullptr);
    // Write to a sibling temp then replace atomically.
    const std::wstring tmp = path_ + L".tmp";
    {
        std::ofstream file(tmp, std::ios::binary | std::ios::trunc);
        if (!file) return false;
        for (const auto& feed : feeds_)
            file << "F\t" << esc(feed.id) << '\t' << esc(feed.url) << '\t' << esc(feed.title) << '\n';
        for (const auto& e : episodes_)
            file << "E\t" << esc(e.id) << '\t' << esc(e.feed_id) << '\t' << esc(e.title) << '\t'
                 << esc(e.audio_url) << '\t' << e.published_at << '\t' << e.duration_ms << '\n';
        for (const auto& p : progress_)
            file << "P\t" << esc(p.episode_id) << '\t' << p.position_ms << '\t' << (p.completed ? 1 : 0) << '\n';
        for (const auto& id : queue_)
            file << "Q\t" << esc(id) << '\n';
        if (!file.good()) return false;
    }
    return MoveFileExW(tmp.c_str(), path_.c_str(), MOVEFILE_REPLACE_EXISTING) != FALSE;
}

void LocalStore::addOrUpdateFeed(const Feed& feed) {
    auto it = std::find_if(feeds_.begin(), feeds_.end(), [&](const Feed& f) { return f.id == feed.id; });
    if (it == feeds_.end()) feeds_.push_back(feed); else *it = feed;
}

void LocalStore::removeFeed(const std::string& id) {
    feeds_.erase(std::remove_if(feeds_.begin(), feeds_.end(), [&](const Feed& f) { return f.id == id; }), feeds_.end());
    episodes_.erase(std::remove_if(episodes_.begin(), episodes_.end(), [&](const Episode& e) { return e.feed_id == id; }), episodes_.end());
}

void LocalStore::setEpisodes(const std::string& feed_id, const std::vector<Episode>& episodes) {
    episodes_.erase(std::remove_if(episodes_.begin(), episodes_.end(), [&](const Episode& e) { return e.feed_id == feed_id; }), episodes_.end());
    episodes_.insert(episodes_.end(), episodes.begin(), episodes.end());
}

void LocalStore::setProgress(const std::string& episode_id, std::int64_t position_ms, bool completed) {
    auto it = std::find_if(progress_.begin(), progress_.end(), [&](const Progress& p) { return p.episode_id == episode_id; });
    if (it == progress_.end()) progress_.push_back({episode_id, position_ms, completed});
    else { it->position_ms = position_ms; it->completed = completed; }
}

void LocalStore::setQueue(const std::vector<std::string>& queue) { queue_ = queue; }

} // namespace podcast
