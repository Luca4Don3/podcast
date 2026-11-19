#include "feed_parser.h"
#include <cstdio>

static int failures = 0;
#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #e); ++failures; } } while (0)

int main() {
    using namespace podcast;
    const std::string rss = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
      "<title>Test &amp; Show</title>"
      "<item><title>Ep 2</title><pubDate>2025-02-02T10:00:00Z</pubDate>"
      "<enclosure url=\"https://cdn.test/a.mp3\" type=\"audio/mpeg\"/></item>"
      "<item><title>Ep 1</title><enclosure url=\"https://cdn.test/b.mp3\"/></item>"
      "</channel></rss>";
    ParsedFeed feed;
    CHECK(parse_feed(rss, feed));
    CHECK(feed.title == "Test & Show");
    CHECK(feed.episodes.size() == 2);
    CHECK(feed.episodes[0].audio_url == "https://cdn.test/a.mp3");
    CHECK(feed.episodes[0].published_at > feed.episodes[1].published_at);

    const std::string atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>A</title>"
      "<entry><title>X</title><published>2025-01-01T00:00:00Z</published>"
      "<link rel=\"enclosure\" href=\"https://cdn.test/x.m4a\" type=\"audio/mp4\"/></entry>"
      "</feed>";
    ParsedFeed atomFeed;
    CHECK(parse_feed(atom, atomFeed));
    CHECK(atomFeed.episodes.size() == 1);
    CHECK(atomFeed.episodes[0].audio_url == "https://cdn.test/x.m4a");

    const std::string opml = "<opml><body>"
      "<outline text=\"One\" title=\"One\" xmlUrl=\"https://a.test/feed.xml\"/>"
      "<outline text=\"Group\"><outline text=\"Two\" xmlUrl=\"https://b.test/rss\"/></outline>"
      "</body></opml>";
    std::vector<OpmlEntry> entries;
    CHECK(parse_opml(opml, entries));
    CHECK(entries.size() == 2);
    CHECK(entries[0].xml_url == "https://a.test/feed.xml");

    ParsedFeed bad;
    CHECK(!parse_feed("<!DOCTYPE foo [<!ENTITY x \"y\">]><rss><channel></channel></rss>", bad));
    CHECK(xml_unescape("a&amp;b&lt;c") == "a&b<c");

    std::printf("Feed parser: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
