#include "PodcastLibraryCore.h"

#include <algorithm>

namespace podcast {

std::vector<std::string> plan_episodes(const std::vector<Episode>& episodes,
                                       const std::string& feed_id,
                                       CachePolicy policy) {
    std::vector<std::size_t> order;
    order.reserve(episodes.size());
    for (std::size_t i = 0; i < episodes.size(); ++i)
        if (episodes[i].feed_id == feed_id) order.push_back(i);

    std::stable_sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) {
        const std::int64_t ta = episodes[a].published_at, tb = episodes[b].published_at;
        if (ta != tb) return ta > tb;
        return a < b;
    });

    std::vector<std::string> result;
    result.reserve(order.size());
    for (std::size_t index : order) {
        const auto& id = episodes[index].id;
        if (std::find(result.begin(), result.end(), id) != result.end()) continue;
        result.push_back(id);
    }
    if (policy == CachePolicy::none) return {};
    if (policy == CachePolicy::all) return result;
    const std::size_t limit = static_cast<std::size_t>(policy);
    if (result.size() > limit) result.resize(limit);
    return result;
}

void PlayQueue::play_next(const std::string& id) {
    if (id.empty()) return;
    remove(id);
    if (items_.empty()) { items_.push_back(id); return; }
    items_.insert(items_.begin() + 1, id);
}

void PlayQueue::append(const std::string& id) {
    if (id.empty()) return;
    if (std::find(items_.begin(), items_.end(), id) != items_.end()) return;
    items_.push_back(id);
}

void PlayQueue::remove(const std::string& id) {
    items_.erase(std::remove(items_.begin(), items_.end(), id), items_.end());
}

void PlayQueue::move(const std::string& id, bool up) {
    auto it = std::find(items_.begin(), items_.end(), id);
    if (it == items_.end()) return;
    const std::size_t at = static_cast<std::size_t>(it - items_.begin());
    if (up && at > 0) std::swap(items_[at], items_[at - 1]);
    else if (!up && at + 1 < items_.size()) std::swap(items_[at], items_[at + 1]);
}

void PlayQueue::clear() { items_.clear(); }

void PlayQueue::set_current(const std::string& id) {
    if (id.empty()) return;
    auto it = std::find(items_.begin(), items_.end(), id);
    if (it != items_.end()) items_.erase(it);
    items_.insert(items_.begin(), id);
}

Progress resume(const Progress& saved, bool finished) {
    Progress next = saved;
    if (finished) { next.position_ms = 0; next.completed = true; }
    else next.completed = false;
    return next;
}

} // namespace podcast
