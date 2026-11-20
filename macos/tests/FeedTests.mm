#import "Feed.h"
#include <cstdio>

#define CHECK(test) do { if (!(test)) { std::fprintf(stderr, "line %d: %s\n", __LINE__, #test); ++failures; } } while (0)

static PDImport *parse(NSString *xml, NSURL *base = nil) {
    return PDParseImport([xml dataUsingEncoding:NSUTF8StringEncoding], base);
}

int main() {
    @autoreleasepool {
        int failures = 0;
        NSURL *base = PDWebURL(@"https://example.test:8443/pod/feed.xml?format=xml", nil);
        CHECK([base.scheme isEqualToString:@"https"] && [base.host isEqualToString:@"example.test"]);
        CHECK(base.port.integerValue == 8443 && [base.query isEqualToString:@"format=xml"]);
        CHECK([PDWebURL(@"  http://example.test/a  ", nil).host isEqualToString:@"example.test"]);
        CHECK(!PDWebURL(@"https://user:pass@example.test:8443/a", nil));
        CHECK(!PDWebURL(@"file:///tmp/audio.mp3", nil));
        CHECK(!PDWebURL(@"../audio.mp3", nil));
        CHECK(!PDWebURL(@"http:audio.mp3", nil));
        CHECK(!PDWebURL(@"", base));

        PDImport *rss = parse(@"<rss version='2.0' xmlns:ext='urn:test'><channel><title>Music &amp; Talk</title>"
                             "<item><title><![CDATA[First & Best]]></title><guid>one</guid>"
                             "<ext:enclosure url='https://example.test/wrong.mp3'/><enclosure url='ep.mp3?x=1&amp;y=2' type='audio/mpeg'/></item>"
                             "<item><guid>one</guid><enclosure url='duplicate.mp3'/></item>"
                             "<item><enclosure url='file:///private.mp3'/><enclosure url='two.m4a' type='audio/mp4'/></item>"
                             "<item><enclosure url='https://example.test/video.mp4' type='video/mp4'/></item></channel></rss>", base);
        CHECK([rss.title isEqualToString:@"Music & Talk"]);
        CHECK(rss.episodes.count == 2 && !rss.isOPML);
        CHECK([rss.episodes.firstObject.title isEqualToString:@"First & Best"]);
        CHECK([rss.episodes.firstObject.url.absoluteString isEqualToString:@"https://example.test:8443/pod/ep.mp3?x=1&y=2"]);
        CHECK([rss.episodes.lastObject.title isEqualToString:@"two.m4a"]);

        PDImport *atom = parse(@"<a:feed xmlns:a='http://www.w3.org/2005/Atom' xml:base='../media/'>"
                              "<a:title>Atom</a:title><a:entry><a:id>x</a:id><a:title>First atom</a:title>"
                              "<a:link href='show.html'/><a:link rel='enclosure' type='audio/mpeg' href='episode.mp3'/>"
                              "</a:entry></a:feed>", base);
        CHECK([atom.title isEqualToString:@"Atom"]);
        CHECK(atom.episodes.count == 1);
        CHECK([atom.episodes.firstObject.url.absoluteString isEqualToString:@"https://example.test:8443/media/episode.mp3"]);
        CHECK(!parse(@"<feed><entry/></feed>"));

        PDImport *opml = parse(@"<opml version='2.0'><head/><body><outline text='Group'>"
                              "<outline xmlUrl='https://example.test/rss'/><outline xmlUrl='https://example.test/rss'/>"
                              "<outline xmlUrl='../feed'/><outline xmlUrl='file:///private.xml'/></outline></body></opml>", base);
        CHECK(opml.isOPML && opml.feeds.count == 2);
        CHECK([opml.feeds.lastObject.absoluteString isEqualToString:@"https://example.test:8443/feed"]);
        NSMutableString *large = [NSMutableString stringWithString:@"<opml><body>"];
        for (int i = 0; i < 100; ++i) [large appendString:@"<outline xmlUrl='https://example.test/rss'/>"];
        CHECK(parse([large stringByAppendingString:@"</body></opml>"]).isOPML);
        CHECK(!parse([large stringByAppendingString:@"<outline xmlUrl='https://example.test/extra'/></body></opml>"]));

        CHECK(PDParseImport([@"<?xml version='1.0' encoding='UTF-16'?><rss><channel><title>播客</title></channel></rss>"
            dataUsingEncoding:NSUTF16StringEncoding], nil) != nil);
        CHECK(!parse(@"<rss><channel><item></channel></rss>"));
        CHECK(!parse(@"<rss/>"));
        CHECK(!parse(@"<!DOCTYPE rss [<!ENTITY note 'replacement'>]><rss><channel><title>&note;</title></channel></rss>"));
        CHECK(!parse(@"<!DOCTYPE rss SYSTEM 'file:///no-such-file.xml'><rss><channel/></rss>"));
        CHECK(!PDParseImport([NSMutableData dataWithLength:PDMaxFeedBytes + 1], nil));
        if (!failures) std::puts("macos feed tests ok");
        return failures != 0;
    }
}
