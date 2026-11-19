#include "PodcastLibraryCore.h"
#include <cstdio>

static int failures = 0;
#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #e); ++failures; } } while (0)

int main() {
    using namespace podcast;
    std::vector<Episode> eps;
    auto add = [&](const char* id, std::int64_t t) {
        Episode e; e.id = id; e.feed_id = "f1"; e.published_at = t; eps.push_back(e);
    };
    add("a", 100); add("b", 300); add("c", 200); add("dup", 300);
    Episode other; other.id = "x"; other.feed_id = "f2"; other.published_at = 999; eps.push_back(other);
    auto plan = plan_episodes(eps, "f1", CachePolicy::all);
    CHECK(plan.size() == 3); // duplicates removed
    CHECK(plan[0] == "b" || plan[0] == "dup");
    CHECK(plan_episodes(eps, "f1", CachePolicy::latest5).size() == 3);
    CHECK(plan_episodes(eps, "f1", CachePolicy::none).empty());

    PlayQueue q;
    q.append("one"); q.append("two"); q.append("three");
    CHECK(q.size() == 3);
    q.append("two"); CHECK(q.size() == 3); // no duplicates
    q.play_next("four");
    CHECK(q.items()[0] == "one" && q.items()[1] == "four");
    q.move("four", false);
    CHECK(q.items()[2] == "four");
    q.remove("one"); CHECK(q.items()[0] == "two");
    q.set_current("four"); CHECK(q.items()[0] == "four");

    Progress p; p.episode_id = "e"; p.position_ms = 5000;
    CHECK(resume(p, false).position_ms == 5000);
    CHECK(resume(p, true).completed && resume(p, true).position_ms == 0);

    std::printf("Library core: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
