#ifndef PODCAST_LOCAL_STORE_H
#define PODCAST_LOCAL_STORE_H

#include "PodcastLibraryCore.h"
#include <string>
#include <vector>

namespace podcast {

// Device-local library. No credentials, no network, no app account.
// Data lives under %LOCALAPPDATA%\Podcast.
class LocalStore {
public:
    LocalStore();

    bool load();
    bool save() const;

    const std::vector<Feed>& feeds() const { return feeds_; }
    const std::vector<Episode>& episodes() const { return episodes_; }
    const std::vector<Progress>& progress() const { return progress_; }
    const std::vector<std::string>& queue() const { return queue_; }

    void addOrUpdateFeed(const Feed& feed);
    void removeFeed(const std::string& id);
    void setEpisodes(const std::string& feed_id, const std::vector<Episode>& episodes);
    void setProgress(const std::string& episode_id, std::int64_t position_ms, bool completed);
    void setQueue(const std::vector<std::string>& queue);

    // Override the data file path for tests.
    void set_path_for_test(const std::wstring& path) { path_ = path; }

private:
    std::wstring path_;
    std::vector<Feed> feeds_;
    std::vector<Episode> episodes_;
    std::vector<Progress> progress_;
    std::vector<std::string> queue_;
};

} // namespace podcast

#endif
