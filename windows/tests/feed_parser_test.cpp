#include "feed_parser.h"

#include <cstdio>
#include <string_view>

namespace {
using namespace podcast;

int failures = 0;
#define CHECK(e) do { if (!(e)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #e); ++failures; } } while (0)

void test_rss() {
    const std::string rss = R"xml(<?xml version="1.0"?>
        <!-- <feed xmlns="http://www.w3.org/2005/Atom"/> -->
        <rss version="2.0" xmlns:x="urn:extension"><channel>
          <x:title>Wrong namespace</x:title>
          <title>Test &amp; <![CDATA[<!DOCTYPE fake> &amp;]]> &#x4E2D;<!-- split --> Show</title>
          <item>
            <description><![CDATA[<enclosure url="https://fake.test/text"/>]]></description>
            <!-- <enclosure url="https://fake.test/comment"/> -->
            <x:enclosure url="https://fake.test/namespace"/>
            <wrapper><enclosure url="https://fake.test/nested"/></wrapper>
            <enclosure fakeurl="https://fake.test/prefix" x:url="https://fake.test/attribute"/>
            <title>Ep 1</title><guid isPermaLink="false">source&amp;stable</guid>
            <pubDate>Sun, 02 Feb 2025 10:00:00 GMT</pubDate>
            <enclosure url = 'https://cdn.test/a.mp3?x=1&amp;y=>'/>
          </item>
          <item><title>Ep 2</title><enclosure url="https://cdn.test/b.mp3"/></item>
          <item><link>https://fake.test/page</link></item>
          <wrapper><item><enclosure url="https://fake.test/hidden-item"/></item></wrapper>
        </channel></rss>)xml";
    ParsedFeed feed;
    CHECK(parse_feed(rss, feed));
    CHECK(feed.title == u8"Test & <!DOCTYPE fake> &amp; 中 Show");
    CHECK(feed.episodes.size() == 2);
    if (feed.episodes.size() != 2) return;
    CHECK(feed.episodes[0].audio_url == "https://cdn.test/a.mp3?x=1&y=>");
    CHECK(feed.episodes[0].published_at == 1738454400);
    CHECK(feed.episodes[1].published_at == 0);
    CHECK(feed.episodes[0].duration_ms == 0);

    ParsedFeed changed;
    CHECK(parse_rss(R"(<rss><channel>
        <item><title>Renamed</title><guid><![CDATA[source&stable]]></guid>
          <enclosure url="https://new-cdn.test/new.mp3"/></item>
        <item><title>Fallback renamed</title><guid> </guid><enclosure url="https://cdn.test/b.mp3"/></item>
        <item><title>New guid</title><guid>other</guid><enclosure url="https://new-cdn.test/new.mp3"/></item>
    </channel></rss>)", changed));
    CHECK(changed.episodes.size() == 3);
    if (changed.episodes.size() == 3) {
        CHECK(!feed.episodes[0].id.empty());
        CHECK(changed.episodes[0].id == feed.episodes[0].id);
        CHECK(changed.episodes[1].id == feed.episodes[1].id);
        CHECK(changed.episodes[2].id != changed.episodes[0].id);
    }
}

void test_atom() {
    ParsedFeed original;
    CHECK(parse_atom(R"(<feed xmlns="http://www.w3.org/2005/Atom"><title>Original</title>
        <entry><title>Ep</title><id>urn:ep:1</id><published>2025-02-02T10:00:00Z</published>
          <link rel="alternate" type="audio/mp4" href="https://fake.test/alternate"/>
          <link rel="enclosure" href="https://cdn.test/old.m4a"/>
        </entry>
        <entry><link href="https://fake.test/default-alternate"/></entry>
    </feed>)", original));
    CHECK(original.episodes.size() == 1);
    if (original.episodes.size() != 1) return;
    CHECK(original.episodes[0].audio_url == "https://cdn.test/old.m4a");
    CHECK(original.episodes[0].published_at == 1738454400);

    ParsedFeed prefixed;
    CHECK(parse_feed(R"(<a:feed xmlns:a="http://www.w3.org/2005/Atom" xmlns:x="urn:extension">
        <title>Wrong namespace</title><a:title>Updated</a:title>
        <a:entry><a:title>Renamed</a:title><a:id>urn:ep:1</a:id><a:updated>2025-02-03T10:00:00Z</a:updated>
          <a:link x:rel="enclosure" href="https://fake.test/prefixed-rel"/>
          <a:link rel="enclosure" x:href="https://fake.test/prefixed-href"/>
          <x:link rel="enclosure" href="https://fake.test/wrong-namespace"/>
          <a:content><a:link rel="enclosure" href="https://fake.test/nested-link"/></a:content>
          <a:link rel="enclosure" href="https://cdn.test/new.m4a"/>
        </a:entry>
        <x:entry><a:link rel="enclosure" href="https://fake.test/wrong-entry"/></x:entry>
    </a:feed>)", prefixed));
    CHECK(prefixed.title == "Updated");
    CHECK(prefixed.episodes.size() == 1);
    if (prefixed.episodes.size() == 1) {
        CHECK(prefixed.episodes[0].id == original.episodes[0].id);
        CHECK(prefixed.episodes[0].audio_url == "https://cdn.test/new.m4a");
        CHECK(prefixed.episodes[0].published_at == original.episodes[0].published_at + 86400);
    }
}

void test_invalid_xml() {
    ParsedFeed feed{"unchanged", {{"saved", "saved title", "saved audio"}}};
    const char* invalid[] = {
        "<wrapper><rss><channel/></rss></wrapper>",
        "<rss><wrapper><channel/></wrapper></rss>",
        "<rss xmlns='urn:wrong'><channel/></rss>",
        "<feed xmlns='urn:wrong'/>",
        "<feed/>",
        "<rss><channel/></rss><rss><channel/></rss>",
        "<rss><channel/></rss><!-- incomplete",
        "<rss><channel><title>unfinished</channel></rss>",
        "<rss><channel><title>&notDeclared;</title></channel></rss>",
        "<!DOCTYPE rss [<!ENTITY title 'blocked'>]><rss><channel><title>&title;</title></channel></rss>",
        "<!DOCTYPE rss SYSTEM 'https://example.test/blocked.dtd'><rss><channel/></rss>",
        "<!DOCTYPE rss><rss><channel/></rss>",
    };
    for (const char* xml : invalid) CHECK(!parse_feed(xml, feed));
    CHECK(!parse_atom("<rss><channel/></rss>", feed));
    CHECK(!parse_rss("<feed xmlns='http://www.w3.org/2005/Atom'/>", feed));
    CHECK(feed.title == "unchanged");
    CHECK(feed.episodes.size() == 1 && feed.episodes[0].id == "saved");
}

void test_encodings_and_size() {
    ParsedFeed feed;
    CHECK(parse_feed("\xEF\xBB\xBF<rss><channel><title>UTF-8</title></channel></rss>", feed));
    CHECK(feed.title == "UTF-8");
    std::string utf16le = "\xff\xfe";
    for (char16_t ch : std::u16string_view(u"<?xml version='1.0' encoding='UTF-16'?><rss><channel>"
                                           u"<title>中文 &#x1F3A7;</title></channel></rss>")) {
        utf16le.push_back(static_cast<char>(ch & 0xff));
        utf16le.push_back(static_cast<char>(ch >> 8));
    }
    CHECK(parse_feed(utf16le, feed));
    CHECK(feed.title == u8"中文 🎧");
    CHECK(feed.episodes.empty());

    std::string bounded = "<rss><channel><title>Boundary</title></channel></rss>";
    bounded.resize(4 * 1024 * 1024, ' ');
    CHECK(parse_feed(bounded, feed));
    CHECK(feed.title == "Boundary");
    bounded.push_back(' ');
    CHECK(!parse_feed(bounded, feed));
    CHECK(feed.title == "Boundary");
}

void test_opml() {
    const std::string opml = R"(<opml xmlns:x="urn:extension">
      <head><outline xmlUrl="https://fake.test/head"/></head>
      <body>
        <outline title="First" text="Ignored" xmlUrl = "https://a.test/feed?a=1&amp;b=2"/>
        <outline text="Group"><outline text="Second" xmlUrl="https://b.test/rss"/></outline>
        <outline x:xmlUrl="https://fake.test/prefix" notxmlUrl="https://fake.test/suffix"/>
        <outline xmlUrl=" &#x20; "/>
        <x:outline xmlUrl="https://fake.test/element"/>
        <wrapper><outline xmlUrl="https://fake.test/wrapper"/></wrapper>
      </body></opml>)";
    std::vector<OpmlEntry> entries{{"prefilled", "prefilled"}};
    CHECK(parse_opml(opml, entries));
    CHECK(entries.size() == 2);
    if (entries.size() == 2) {
        CHECK(entries[0].title == "First" && entries[0].xml_url == "https://a.test/feed?a=1&b=2");
        CHECK(entries[1].title == "Second" && entries[1].xml_url == "https://b.test/rss");
    }

    std::string boundary = "<opml><body><outline text='Folder'>";
    for (int i = 0; i < 100; ++i) {
        boundary += "<outline text='Not a subscription'/><outline xmlUrl='https://feed.test/";
        boundary += std::to_string(i) + "'/>";
    }
    const std::string end = "</outline></body></opml>";
    CHECK(parse_opml(boundary + end, entries));
    CHECK(entries.size() == 100 && entries.back().xml_url == "https://feed.test/99");

    entries = {{"saved", "saved title"}};
    CHECK(!parse_opml(boundary + "<outline xmlUrl='https://feed.test/100'/>" + end, entries));
    CHECK(!parse_opml("<wrapper><opml><body/></opml></wrapper>", entries));
    CHECK(!parse_opml("<opml xmlns='urn:wrong'><body/></opml>", entries));
    CHECK(!parse_opml("<opml><outline xmlUrl='https://feed.test/'/></opml>", entries));
    CHECK(!parse_opml("<opml><body><outline xmlUrl='https://feed.test/'/></body> junk", entries));
    CHECK(!parse_opml("<!DOCTYPE opml><opml><body/></opml>", entries));
    CHECK(entries.size() == 1 && entries[0].xml_url == "saved" && entries[0].title == "saved title");
    CHECK(parse_opml("<opml><body/></opml>", entries) && entries.empty());
}

} // namespace

int main() {
    test_rss();
    test_atom();
    test_invalid_xml();
    test_encodings_and_size();
    test_opml();
    CHECK(xml_unescape("a&amp;b&lt;c") == "a&b<c");
    CHECK(parse_date("1970-03-01") == 59 * 86400);
    CHECK(parse_date("2024-03-01") - parse_date("2024-02-29") == 86400);
    CHECK(parse_date("2025-02-29") == 0);
    CHECK(parse_date("202X-01-01") == 0);
    std::printf("Feed parser: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
