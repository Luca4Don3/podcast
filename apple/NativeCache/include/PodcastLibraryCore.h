#ifndef PODCAST_LIBRARY_CORE_H
#define PODCAST_LIBRARY_CORE_H

#include <cstdint>
#include <string>
#include <vector>

namespace podcast {

/// One locally stored subscription. Public metadata only; never holds credentials.
struct Feed {
    std::string id;          // canonical feed key (sha256 of source URL)
    std::string url;         // original RSS/Atom URL
    std::string title;
    std::vector<std::string> episode_ids; // newest first
};

struct Episode {
    std::string id;
    std::string feed_id;
    std::string title;
    std::string audio_url;
    std::int64_t published_at = 0; // unix seconds, 0 = unknown
    std::int64_t duration_ms = 0;  // 0 = unknown
};

struct Progress {
    std::string episode_id;
    std::int64_t position_ms = 0;
    bool completed = false;
};

enum class CachePolicy : std::int32_t { none = 0, latest5 = 5, latest10 = 10, all = -1 };

/// Deterministically reorder episodes by publication time (fall back to feed order),
/// drop duplicate ids, then keep at most @ count (negative = all).
std::vector<std::string> plan_episodes(const std::vector<Episode>& episodes,
                                       const std::string& feed_id,
                                       CachePolicy policy);

/// A playable queue. Entries are episode ids; first is now playing.
class PlayQueue {
public:
    void play_next(const std::string& id);   // insert right after current item
    void append(const std::string& id);      // insert at tail, no duplicates
    void remove(const std::string& id);
    void move(const std::string& id, bool up);
    void clear();
    void set_current(const std::string& id);
    const std::vector<std::string>& items() const { return items_; }
    std::size_t size() const { return items_.size(); }

private:
    std::vector<std::string> items_;
};

/// Mark an episode finished and compute its resume position.
/// Completed episodes report position 0 so the home list can exclude them.
Progress resume(const Progress& saved, bool finished);

} // namespace podcast

#endif
