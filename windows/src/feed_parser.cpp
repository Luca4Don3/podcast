#include "feed_parser.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>

namespace podcast {
namespace {

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string trim(const std::string& s) {
    std::size_t a = 0, b = s.size();
    while (a < b && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
    while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
    return s.substr(a, b - a);
}

// Locate the byte range of each element with the given local name.
// Depth-aware so nested same-name elements (e.g. Atom links) do not break scanning.
struct Element { std::string attrs; std::string body; };

bool find_elements(const std::string& xml, const std::string& name, std::vector<Element>& out) {
    const std::string open = "<" + name;
    const std::string close = "</" + name + ">";
    std::size_t from = 0;
    while (true) {
        std::size_t start = xml.find(open, from);
        if (start == std::string::npos) return true;
        const std::size_t after = start + open.size();
        // The char after the name must terminate it: '>', ' ', '\t', '\r', '\n' or '/'.
        // This rejects longer names such as <itemGroup> when looking for <item>.
        if (after < xml.size() && xml[after] != '>' && xml[after] != '/' &&
            !std::isspace(static_cast<unsigned char>(xml[after]))) { from = after; continue; }
        std::size_t gt = xml.find('>', start);
        if (gt == std::string::npos) return false;
        Element e;
        e.attrs = xml.substr(start + open.size(), gt - start - open.size());
        if (gt > start && xml[gt - 1] == '/') {
            // Self-closing (e.g. <enclosure/>): attributes matter, body is empty.
            from = gt + 1;
        } else {
            std::size_t end = xml.find(close, gt);
            if (end == std::string::npos) return false;
            e.body = xml.substr(gt + 1, end - gt - 1);
            // Continue scanning inside the element so nested same-name tags are found too.
            from = start + 1;
        }
        out.push_back(e);
    }
}

std::string first_text(const std::string& xml, const std::string& name) {
    std::vector<Element> found;
    if (!find_elements(xml, name, found) || found.empty()) return {};
    const std::string& body = found[0].body;
    const std::size_t cdata = body.find("<![CDATA[");
    if (cdata != std::string::npos) {
        const std::size_t begin = cdata + 9;
        const std::size_t finish = body.find("]]>", begin);
        if (finish != std::string::npos) return trim(body.substr(begin, finish - begin));
    }
    const std::size_t lt = body.find('<');
    return trim(lt == std::string::npos ? body : body.substr(0, lt));
}

std::string attr_value(const Element& e, const std::string& key) {
    const std::string pattern = key + "=";
    const std::string text = e.attrs;
    for (std::size_t i = 0; i + pattern.size() < text.size(); ++i) {
        if (text.compare(i, pattern.size(), pattern) != 0) continue;
        std::size_t p = i + pattern.size();
        while (p < text.size() && std::isspace(static_cast<unsigned char>(text[p]))) ++p;
        if (p >= text.size() || (text[p] != '"' && text[p] != '\'')) continue;
        const char quote = text[p++];
        const std::size_t end = text.find(quote, p);
        if (end == std::string::npos) return {};
        return xml_unescape(text.substr(p, end - p));
    }
    return {};
}

bool safe_markup(const std::string& xml) {
    const std::string low = lower(xml);
    return low.find("<!doctype") == std::string::npos &&
           low.find("<!entity") == std::string::npos;
}

std::string make_id(const std::string& seed) {
    // FNV-1a 64-bit; stable across runs and platforms.
    std::uint64_t hash = 1469598103934665603ULL;
    for (unsigned char c : seed) { hash ^= c; hash *= 1099511628211ULL; }
    char buffer[17];
    std::snprintf(buffer, sizeof(buffer), "%016llx", static_cast<unsigned long long>(hash));
    return std::string(buffer);
}

} // namespace

std::string xml_unescape(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '&') { out += text[i]; continue; }
        const std::size_t semi = text.find(';', i);
        if (semi == std::string::npos || semi - i > 12) { out += text[i]; continue; }
        const std::string entity = text.substr(i + 1, semi - i - 1);
        if (entity == "amp") out += '&';
        else if (entity == "lt") out += '<';
        else if (entity == "gt") out += '>';
        else if (entity == "quot") out += '"';
        else if (entity == "apos") out += '\'';
        else if (entity.size() > 1 && entity[0] == '#') {
            const bool hex = entity[1] == 'x' || entity[1] == 'X';
            const std::string digits = entity.substr(hex ? 2 : 1);
            char* end = nullptr;
            const unsigned long value = std::strtoul(digits.c_str(), &end, hex ? 16 : 10);
            if (end && *end == '\0' && value <= 0x10ffff) {
                if (value < 0x80) out += static_cast<char>(value);
                else out += '?'; // Non-ASCII requires UTF-8 encoding by caller.
            } else out += '&' + entity + ';';
        } else out += '&' + entity + ';';
        i = semi;
    }
    return out;
}

// RFC 822 / RFC 3339: extract YYYY-MM-DD or a weekday date; caller can refine.
std::int64_t parse_date(const std::string& text) {
    const std::string t = trim(text);
    if (t.size() < 10) return 0;
    std::size_t y = t.find_first_not_of(" ,");
    if (y == std::string::npos) return 0;
    // ISO: 2025-02-02T10:00:00Z
    if (t.size() >= y + 10 && t[y + 4] == '-' && t[y + 7] == '-') {
        const int year = std::atoi(t.substr(y, 4).c_str());
        const int month = std::atoi(t.substr(y + 5, 2).c_str());
        const int day = std::atoi(t.substr(y + 8, 2).c_str());
        // Days since epoch via Howard Hinnant's formula.
        const int era = (year >= 0 ? year : year - 399) / 400;
        const unsigned yoe = static_cast<unsigned>(year - era * 400);
        const unsigned doy = static_cast<unsigned>((153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1);
        const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        const std::int64_t days = static_cast<std::int64_t>(era) * 146097 + static_cast<std::int64_t>(doe) - 719468;
        return days * 86400;
    }
    return 0;
}

bool parse_rss(const std::string& xml, ParsedFeed& out) {
    if (!safe_markup(xml)) return false;
    std::vector<Element> channels;
    if (!find_elements(xml, "channel", channels) || channels.empty()) return false;
    out.title = xml_unescape(first_text(channels[0].body, "title"));
    std::vector<Element> items;
    if (!find_elements(channels[0].body, "item", items)) return false;
    for (const auto& item : items) {
        std::vector<Element> links;
        std::vector<Element> encs;
        find_elements(item.body, "enclosure", encs);
        std::string url = encs.empty() ? std::string() : attr_value(encs[0], "url");
        if (url.empty()) {
            find_elements(item.body, "link", links);
            for (const auto& link : links) {
                const std::string type = lower(attr_value(link, "type"));
                const std::string href = attr_value(link, "href");
                if (!href.empty() && type.find("audio") != std::string::npos) { url = href; break; }
            }
        }
        if (url.empty() && !links.empty()) url = trim(first_text(item.body, "link"));
        if (url.empty()) continue;
        ParsedEpisode e;
        e.title = xml_unescape(first_text(item.body, "title"));
        e.audio_url = url;
        e.published_at = parse_date(first_text(item.body, "pubDate"));
        e.id = make_id(e.audio_url + "|" + e.title);
        out.episodes.push_back(e);
    }
    return true;
}

bool parse_atom(const std::string& xml, ParsedFeed& out) {
    if (!safe_markup(xml)) return false;
    std::vector<Element> feeds;
    if (!find_elements(xml, "feed", feeds) || feeds.empty()) return false;
    out.title = xml_unescape(first_text(feeds[0].body, "title"));
    std::vector<Element> entries;
    if (!find_elements(feeds[0].body, "entry", entries)) return false;
    for (const auto& entry : entries) {
        std::vector<Element> links;
        if (!find_elements(entry.body, "link", links)) return false;
        std::string url;
        std::string alternate;
        for (const auto& link : links) {
            const std::string href = attr_value(link, "href");
            if (href.empty()) continue;
            const std::string rel = lower(attr_value(link, "rel"));
            const std::string type = lower(attr_value(link, "type"));
            if (rel == "enclosure" || type.find("audio") != std::string::npos) { url = href; break; }
            if (rel.empty() || rel == "alternate") alternate = href;
        }
        if (url.empty()) url = alternate;
        if (url.empty()) continue;
        ParsedEpisode e;
        e.title = xml_unescape(first_text(entry.body, "title"));
        e.audio_url = url;
        e.published_at = parse_date(first_text(entry.body, "published"));
        if (e.published_at == 0) e.published_at = parse_date(first_text(entry.body, "updated"));
        e.id = make_id(e.audio_url + "|" + e.title);
        out.episodes.push_back(e);
    }
    return true;
}

bool parse_opml(const std::string& xml, std::vector<OpmlEntry>& out) {
    if (!safe_markup(xml)) return false;
    std::vector<Element> outlines;
    if (!find_elements(xml, "outline", outlines)) return false;
    for (const auto& outline : outlines) {
        const std::string url = attr_value(outline, "xmlUrl");
        if (url.empty()) continue;
        OpmlEntry entry;
        entry.xml_url = url;
        entry.title = attr_value(outline, "title");
        if (entry.title.empty()) entry.title = attr_value(outline, "text");
        out.push_back(entry);
    }
    return true;
}

bool parse_feed(const std::string& xml, ParsedFeed& out) {
    const std::string low = lower(xml);
    if (low.find("<rss") != std::string::npos) return parse_rss(xml, out);
    if (low.find("<feed") != std::string::npos) return parse_atom(xml, out);
    return false;
}

} // namespace podcast
