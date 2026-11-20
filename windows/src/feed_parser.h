#ifndef PODCAST_FEED_PARSER_H
#define PODCAST_FEED_PARSER_H

#include <cstdint>
#include <string>
#include <vector>

namespace podcast {

struct ParsedEpisode {
    // Stable hash of RSS GUID / Atom id, or the enclosure URL when no source ID exists.
    // This is not the legacy URL+title hash; persistent record migration is up to the caller.
    std::string id;
    std::string title;
    std::string audio_url;
    std::int64_t published_at = 0;
    std::int64_t duration_ms = 0;
};

struct ParsedFeed {
    std::string title;
    std::vector<ParsedEpisode> episodes;
};

struct OpmlEntry {
    std::string xml_url;
    std::string title;
};

// Parse up to 4 MiB of XML bytes (UTF-8 or UTF-16); DTDs are forbidden.
// Return false without changing out on invalid input. Successful calls replace out.
bool parse_rss(const std::string& xml, ParsedFeed& out);
bool parse_atom(const std::string& xml, ParsedFeed& out);
// Reject documents with more than 100 outlines containing nonempty xmlUrl attributes.
bool parse_opml(const std::string& xml, std::vector<OpmlEntry>& out);

// Detect the feed format from the root element and dispatch.
bool parse_feed(const std::string& xml, ParsedFeed& out);

// Helpers exposed for unit tests.
std::string xml_unescape(const std::string& text);
std::int64_t parse_date(const std::string& text);

} // namespace podcast

#endif
