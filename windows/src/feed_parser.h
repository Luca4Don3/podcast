#ifndef PODCAST_FEED_PARSER_H
#define PODCAST_FEED_PARSER_H

#include <cstdint>
#include <string>
#include <vector>

namespace podcast {

struct ParsedEpisode {
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

// Strict, DTD-free XML scan. Returns false on DOCTYPE/ENTITY or malformed markup.
bool parse_rss(const std::string& xml, ParsedFeed& out);
bool parse_atom(const std::string& xml, ParsedFeed& out);
bool parse_opml(const std::string& xml, std::vector<OpmlEntry>& out);

// Detect the feed format from the root element and dispatch.
bool parse_feed(const std::string& xml, ParsedFeed& out);

// Helpers exposed for unit tests.
std::string xml_unescape(const std::string& text);
std::int64_t parse_date(const std::string& text);

} // namespace podcast

#endif
