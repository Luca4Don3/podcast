#import "Feed.h"
#include "PodcastLibraryCore.h"
#include <libxml/xmlreader.h>
#include <memory>
#include <utility>
#include <vector>

@implementation PDEpisode
- (instancetype)initWithTitle:(NSString *)title URL:(NSURL *)url {
    if ((self = [super init])) { _title = [title copy]; _url = [url copy]; }
    return self;
}
@end

@interface PDImport ()
@property(nonatomic, readwrite, copy) NSString *title;
@property(nonatomic, readwrite, copy) NSArray<PDEpisode *> *episodes;
@property(nonatomic, readwrite, copy) NSArray<NSURL *> *feeds;
@property(nonatomic, readwrite, getter=isOPML) BOOL OPML;
@end
@implementation PDImport
@end

static NSString *trim(NSString *value) {
    return [value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

NSURL *PDWebURL(NSString *text, NSURL *base) {
    NSString *value = trim(text);
    if (!value.length) return nil;
    NSURL *url = [NSURL URLWithString:value relativeToURL:base].absoluteURL;
    NSString *scheme = url.scheme.lowercaseString;
    if (!([scheme isEqualToString:@"http"] || [scheme isEqualToString:@"https"]) ||
        !url.host.length || url.user || url.password) return nil;
    return url;
}

namespace {
NSString *xmlString(const xmlChar *text) {
    return text ? [NSString stringWithUTF8String:reinterpret_cast<const char *>(text)] : nil;
}
NSString *takeString(xmlChar *text) {
    NSString *result = xmlString(text);
    xmlFree(text);
    return result;
}
NSString *attribute(xmlTextReaderPtr reader, const char *name) {
    return takeString(xmlTextReaderGetAttribute(reader, BAD_CAST name));
}
NSURL *link(xmlTextReaderPtr reader, NSString *value, NSURL *base) {
    if (!trim(value).length) return nil;
    NSString *xmlBase = xmlString(xmlTextReaderConstBaseUri(reader));
    return PDWebURL(value, xmlBase.length ? PDWebURL(xmlBase, base) : base);
}
bool audioMIME(NSString *value) {
    NSString *type = trim(value).lowercaseString;
    return !type.length || [type hasPrefix:@"audio/"] || [type isEqualToString:@"application/octet-stream"] ||
           [type isEqualToString:@"application/vnd.apple.mpegurl"] || [type isEqualToString:@"application/x-mpegurl"];
}

enum class Format { unknown, rss, atom, opml };
constexpr NSUInteger maxOPMLLinks = 100;
constexpr NSUInteger maxFeedEpisodes = 500;
}

PDImport *PDParseImport(NSData *data, NSURL *base) {
    if (!data.length || data.length > PDMaxFeedBytes) return nil;
    // No NOENT, DTDLOAD, RECOVER or HUGE flags. Reject DTD/entity events below.
    std::unique_ptr<xmlTextReader, decltype(&xmlFreeTextReader)> reader(xmlReaderForMemory(
        static_cast<const char *>(data.bytes), static_cast<int>(data.length), base.absoluteString.UTF8String,
        nullptr, XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING), xmlFreeTextReader);
    if (!reader) return nil;
    auto r = reader.get();
    Format format = Format::unknown;
    BOOL inSection = NO;
    BOOL sawSection = NO;
    int episodeDepth = -1;
    NSString *title = nil, *episodeTitle = nil, *sourceID = nil;
    NSURL *audio = nil;
    NSMutableArray<NSURL *> *feeds = [NSMutableArray array];
    NSMutableSet<NSString *> *seenFeeds = [NSMutableSet set];
    NSMutableDictionary<NSString *, PDEpisode *> *byID = [NSMutableDictionary dictionary];
    std::vector<podcast::Episode> candidates;
    NSUInteger outlineLinks = 0;
    int status;

    auto addEpisode = [&] {
        if (!audio) return;
        NSString *key = sourceID.length ? [@"id:" stringByAppendingString:sourceID] :
                                         [@"url:" stringByAppendingString:audio.absoluteString];
        if (byID[key] || byID.count >= maxFeedEpisodes) return;
        NSString *name = episodeTitle.length ? episodeTitle : (audio.lastPathComponent.length ? audio.lastPathComponent : @"Untitled episode");
        byID[key] = [[PDEpisode alloc] initWithTitle:name URL:audio];
        podcast::Episode episode;
        episode.id = key.UTF8String;
        episode.feed_id = "import";
        candidates.push_back(std::move(episode));
    };

    while ((status = xmlTextReaderRead(r)) == 1) {
        int depth = xmlTextReaderDepth(r);
        int node = xmlTextReaderNodeType(r);
        if (depth > 128 || node == XML_READER_TYPE_DOCUMENT_TYPE || node == XML_READER_TYPE_ENTITY_REFERENCE) return nil;
        if (node != XML_READER_TYPE_ELEMENT && node != XML_READER_TYPE_END_ELEMENT) continue;
        @autoreleasepool {
            const BOOL opening = node == XML_READER_TYPE_ELEMENT;
            const BOOL closing = !opening || xmlTextReaderIsEmptyElement(r) == 1;
            NSString *name = xmlString(xmlTextReaderConstLocalName(r));
            NSString *space = xmlString(xmlTextReaderConstNamespaceUri(r));
            if (depth == 0 && opening) {
                if ([name isEqualToString:@"rss"] && !space.length) format = Format::rss;
                else if ([name isEqualToString:@"feed"] && [space isEqualToString:@"http://www.w3.org/2005/Atom"]) format = Format::atom;
                else if ([name isEqualToString:@"opml"] && !space.length) format = Format::opml;
                else return nil;
            }
            if (format == Format::opml) {
                if (space.length) continue;
                if (depth == 1 && [name isEqualToString:@"body"]) {
                    if (opening) { inSection = YES; sawSection = YES; }
                    if (closing) inSection = NO;
                } else if (inSection && depth >= 2 && opening && [name isEqualToString:@"outline"]) {
                    NSString *value = attribute(r, "xmlUrl");
                    if (trim(value).length && ++outlineLinks > maxOPMLLinks) return nil;
                    NSURL *url = link(r, value, base);
                    if (url && ![seenFeeds containsObject:url.absoluteString]) {
                        [seenFeeds addObject:url.absoluteString];
                        [feeds addObject:url];
                    }
                }
                continue;
            }
            const BOOL rss = format == Format::rss;
            if (rss ? space.length != 0 : ![space isEqualToString:@"http://www.w3.org/2005/Atom"]) continue;
            if (rss && depth == 1 && [name isEqualToString:@"channel"]) {
                if (opening) { inSection = YES; sawSection = YES; }
                if (closing) inSection = NO;
                continue;
            }
            if (rss && !inSection) continue;
            int childDepth = rss ? 2 : 1;
            if (depth == childDepth && [name isEqualToString:rss ? @"item" : @"entry"]) {
                if (opening) { episodeDepth = depth; episodeTitle = nil; sourceID = nil; audio = nil; }
                if (closing) { addEpisode(); episodeDepth = -1; }
            } else if (opening && episodeDepth < 0 && depth == childDepth && [name isEqualToString:@"title"]) {
                title = trim(takeString(xmlTextReaderReadString(r)));
            } else if (opening && episodeDepth >= 0 && depth == episodeDepth + 1) {
                if ([name isEqualToString:@"title"]) episodeTitle = trim(takeString(xmlTextReaderReadString(r)));
                else if ([name isEqualToString:rss ? @"guid" : @"id"]) sourceID = trim(takeString(xmlTextReaderReadString(r)));
                else if (!audio && audioMIME(attribute(r, "type"))) {
                    if (rss && [name isEqualToString:@"enclosure"]) audio = link(r, attribute(r, "url"), base);
                    else if (!rss && [name isEqualToString:@"link"] && [attribute(r, "rel") isEqualToString:@"enclosure"])
                        audio = link(r, attribute(r, "href"), base);
                }
            }
        }
    }
    if (status != 0 || format == Format::unknown || (format != Format::atom && !sawSection)) return nil;
    NSMutableArray<PDEpisode *> *episodes = [NSMutableArray array];
    // Timestamps are not imported in this preview; the shared planner keeps source order.
    for (const auto& id : podcast::plan_episodes(candidates, "import", podcast::CachePolicy::all))
        [episodes addObject:byID[[NSString stringWithUTF8String:id.c_str()]]];
    PDImport *result = [PDImport new];
    result.title = title.length ? title : @"Untitled feed";
    result.episodes = episodes;
    result.feeds = feeds;
    result.OPML = format == Format::opml;
    return result;
}
