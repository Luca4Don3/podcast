#include "feed_parser.h"

#include <windows.h>
#include <shlwapi.h>
#include <wrl/client.h>
#include <xmllite.h>

#include <charconv>
#include <cstdio>
#include <string_view>
#include <utility>

#pragma comment(lib, "xmllite.lib")
#pragma comment(lib, "shlwapi.lib")

namespace podcast {
namespace {

constexpr std::size_t kMaxXmlBytes = 4 * 1024 * 1024;
constexpr std::size_t kMaxOpmlEntries = 100;
constexpr LONG_PTR kMaxElementDepth = 256;
constexpr std::wstring_view kAtomNamespace = L"http://www.w3.org/2005/Atom";
constexpr std::string_view kWhitespace = " \t\r\n";

std::string_view trim(std::string_view s) {
    const auto first = s.find_first_not_of(kWhitespace);
    if (first == std::string_view::npos) return {};
    return s.substr(first, s.find_last_not_of(kWhitespace) - first + 1);
}

bool append_value(IXmlReader& reader, std::string& text) {
    const wchar_t* value = nullptr;
    UINT length = 0;
    if (reader.GetValue(&value, &length) != S_OK) return false;
    if (length == 0) return true;
    // XmlLite's UTF-16 text is bounded by the input limit; the Win32 API uses int lengths.
    const int count = static_cast<int>(length);
    const int bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, count, nullptr, 0, nullptr, nullptr);
    if (bytes == 0) return false;
    const auto offset = text.size();
    text.resize(offset + bytes);
    return WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, count,
                               text.data() + offset, bytes, nullptr, nullptr) == bytes;
}

bool read_attribute(IXmlReader& reader, const wchar_t* name, std::string& out) {
    const HRESULT hr = reader.MoveToAttributeByName(name, L"");
    if (hr == S_FALSE) return true;
    if (hr != S_OK || !append_value(reader, out) || reader.MoveToElement() != S_OK) return false;
    out = trim(out);
    return true;
}

std::string make_id(char source, std::string_view value) {
    // FNV-1a, with disjoint seed domains for RSS GUID, Atom id and enclosure URL.
    std::uint64_t hash = (14695981039346656037ULL ^ static_cast<unsigned char>(source)) * 1099511628211ULL;
    for (unsigned char c : value) { hash ^= c; hash *= 1099511628211ULL; }
    char buffer[17];
    std::snprintf(buffer, sizeof(buffer), "%016llx", static_cast<unsigned long long>(hash));
    return buffer;
}

enum class Format { Feed, Rss, Atom, Opml };
enum class Node {
    Ignore, Rss, Channel, Atom, Opml, Body, Outline, Episode, Enclosure,
    FeedTitle, EpisodeTitle, SourceId, Published, Updated
};

bool is_text_field(Node node) {
    return node == Node::FeedTitle || node == Node::EpisodeTitle || node == Node::SourceId ||
           node == Node::Published || node == Node::Updated;
}

Node child_node(Node parent, std::wstring_view name, std::wstring_view ns, bool atom) {
    if (ns != (atom ? kAtomNamespace : std::wstring_view{})) return Node::Ignore;
    switch (parent) {
    case Node::Rss:     return name == L"channel" ? Node::Channel : Node::Ignore;
    case Node::Opml:    return name == L"body" ? Node::Body : Node::Ignore;
    case Node::Body:
    case Node::Outline: return name == L"outline" ? Node::Outline : Node::Ignore;
    case Node::Channel:
    case Node::Atom:
        if (name == L"title") return Node::FeedTitle;
        if (name == (atom ? L"entry" : L"item")) return Node::Episode;
        break;
    case Node::Episode:
        if (name == L"title") return Node::EpisodeTitle;
        if (name == (atom ? L"id" : L"guid")) return Node::SourceId;
        if (name == (atom ? L"published" : L"pubDate")) return Node::Published;
        if (atom && name == L"updated") return Node::Updated;
        if (name == (atom ? L"link" : L"enclosure")) return Node::Enclosure;
        break;
    default: break;
    }
    return Node::Ignore;
}

struct Document {
    ParsedFeed feed;
    std::vector<OpmlEntry> entries;

    bool read(IXmlReader& reader, Format expected) {
        std::vector<Node> path;
        path.reserve(kMaxElementDepth);
        XmlNodeType type;
        HRESULT hr;
        while ((hr = reader.Read(&type)) == S_OK) {
            switch (type) {
            case XmlNodeType_Element: {
                const wchar_t *name = nullptr, *ns = nullptr;
                UINT name_length = 0, ns_length = 0;
                if (reader.GetLocalName(&name, &name_length) != S_OK ||
                    reader.GetNamespaceUri(&ns, &ns_length) != S_OK) return false;
                const std::wstring_view local(name, name_length);
                const auto uri = ns_length ? std::wstring_view(ns, ns_length) : std::wstring_view{};
                Node node;
                if (path.empty()) {
                    if (root_seen) return false;
                    root_seen = true;
                    if (local == L"rss" && uri.empty() && (expected == Format::Rss || expected == Format::Feed)) {
                        node = Node::Rss;
                    } else if (local == L"feed" && uri == kAtomNamespace &&
                               (expected == Format::Atom || expected == Format::Feed)) {
                        node = Node::Atom;
                        atom = true;
                    } else if (local == L"opml" && uri.empty() && expected == Format::Opml) {
                        node = Node::Opml;
                    } else {
                        return false;
                    }
                } else {
                    node = child_node(path.back(), local, uri, atom);
                }
                const bool empty = reader.IsEmptyElement() != FALSE;
                if (!start(reader, node)) return false;
                if (empty) finish(node);
                else path.push_back(node);
                break;
            }
            case XmlNodeType_EndElement:
                if (path.empty()) return false;
                finish(path.back());
                path.pop_back();
                break;
            case XmlNodeType_Text:
            case XmlNodeType_CDATA:
                if (path.empty()) return false;
                [[fallthrough]];
            case XmlNodeType_Whitespace:
                if (!path.empty() && is_text_field(path.back()) && !append_value(reader, text)) return false;
                break;
            case XmlNodeType_DocumentType:
                return false;
            default: break;
            }
        }
        return hr == S_FALSE && root_seen && path.empty() && (atom || container_seen);
    }

private:
    bool atom = false;
    bool root_seen = false;
    bool container_seen = false;
    ParsedEpisode episode;
    std::string text, source_id, published, updated;

    bool start(IXmlReader& reader, Node node) {
        if (is_text_field(node)) text.clear();
        switch (node) {
        case Node::Channel:
        case Node::Body:
            if (container_seen) return false;
            container_seen = true;
            break;
        case Node::Episode:
            episode = {};
            source_id.clear();
            published.clear();
            updated.clear();
            break;
        case Node::Enclosure:
            if (episode.audio_url.empty()) {
                if (atom) {
                    std::string relation;
                    if (!read_attribute(reader, L"rel", relation)) return false;
                    if (relation != "enclosure" && relation != "http://www.iana.org/assignments/relation/enclosure") {
                        break;
                    }
                }
                return read_attribute(reader, atom ? L"href" : L"url", episode.audio_url);
            }
            break;
        case Node::Outline: {
            OpmlEntry entry;
            if (!read_attribute(reader, L"xmlUrl", entry.xml_url)) return false;
            if (entry.xml_url.empty()) break;
            if (entries.size() == kMaxOpmlEntries || !read_attribute(reader, L"title", entry.title)) return false;
            if (entry.title.empty() && !read_attribute(reader, L"text", entry.title)) return false;
            entries.push_back(std::move(entry));
            break;
        }
        default: break;
        }
        return true;
    }

    void finish(Node node) {
        switch (node) {
        case Node::FeedTitle:    if (feed.title.empty()) feed.title = trim(text); break;
        case Node::EpisodeTitle: if (episode.title.empty()) episode.title = trim(text); break;
        case Node::SourceId:     if (source_id.empty()) source_id = trim(text); break;
        case Node::Published:    if (published.empty()) published = trim(text); break;
        case Node::Updated:      if (updated.empty()) updated = trim(text); break;
        case Node::Episode:
            if (!episode.audio_url.empty()) {
                episode.id = source_id.empty() ? make_id('u', episode.audio_url) : make_id(atom ? 'i' : 'g', source_id);
                episode.published_at = parse_date(published);
                if (atom && episode.published_at == 0) episode.published_at = parse_date(updated);
                feed.episodes.push_back(std::move(episode));
            }
            break;
        default: break;
        }
    }
};

bool read_document(const std::string& xml, Format format, Document& document) {
    if (xml.empty() || xml.size() > kMaxXmlBytes) return false;
    Microsoft::WRL::ComPtr<IStream> stream;
    stream.Attach(SHCreateMemStream(reinterpret_cast<const BYTE*>(xml.data()), static_cast<UINT>(xml.size())));
    Microsoft::WRL::ComPtr<IXmlReader> reader;
    if (!stream || FAILED(CreateXmlReader(__uuidof(IXmlReader), reinterpret_cast<void**>(reader.GetAddressOf()), nullptr)) ||
        FAILED(reader->SetProperty(XmlReaderProperty_ConformanceLevel, XmlConformanceLevel_Document)) ||
        FAILED(reader->SetProperty(XmlReaderProperty_DtdProcessing, DtdProcessing_Prohibit)) ||
        FAILED(reader->SetProperty(XmlReaderProperty_MaxElementDepth, kMaxElementDepth)) ||
        FAILED(reader->SetInput(stream.Get()))) return false;
    return document.read(*reader.Get(), format);
}

bool read_feed(const std::string& xml, Format format, ParsedFeed& out) {
    Document document;
    if (!read_document(xml, format, document)) return false;
    out = std::move(document.feed);
    return true;
}

int decimal(std::string_view s) {
    if (s.empty() || s.find_first_not_of("0123456789") != std::string_view::npos) return -1;
    int value = 0;
    const auto end = s.data() + s.size();
    const auto result = std::from_chars(s.data(), end, value);
    return result.ec == std::errc{} ? value : -1;
}

std::string_view date_word(std::string_view& s) {
    const auto stop = s.find_first_of(kWhitespace);
    const auto word = s.substr(0, stop);
    s = stop == std::string_view::npos ? std::string_view{} : trim(s.substr(stop));
    return word;
}

std::int64_t day_timestamp(int year, int month, int day) {
    constexpr int month_lengths[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1) return 0;
    const bool leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    if (day > month_lengths[month - 1] + (month == 2 && leap ? 1 : 0)) return 0;
    // Howard Hinnant's civil-date formula numbers January/February in the previous year.
    year -= month <= 2;
    const int era = year / 400;
    const int yoe = year - era * 400;
    const int doy = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
    const int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return (static_cast<std::int64_t>(era) * 146097 + doe - 719468) * 86400;
}

} // namespace

std::string xml_unescape(const std::string& text) {
    // Legacy standalone helper; feed content is already decoded by XmlLite.
    std::string out;
    out.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '&') { out += text[i]; continue; }
        const auto semi = std::string_view(text).substr(i, 13).find(';');
        if (semi == std::string_view::npos || semi == 0) { out += text[i]; continue; }
        const auto entity = std::string_view(text).substr(i + 1, semi - 1);
        if (entity == "amp") out += '&';
        else if (entity == "lt") out += '<';
        else if (entity == "gt") out += '>';
        else if (entity == "quot") out += '"';
        else if (entity == "apos") out += '\'';
        else if (entity.size() > 1 && entity[0] == '#') {
            const bool hex = entity[1] == 'x' || entity[1] == 'X';
            const char* end = entity.data() + entity.size();
            unsigned value = 0;
            const auto result = std::from_chars(entity.data() + (hex ? 2 : 1), end, value, hex ? 16 : 10);
            if (result.ec != std::errc{} || result.ptr != end || value > 0x10ffff) {
                out.append(text, i, semi + 1);
            } else if (value < 0x80) {
                out += static_cast<char>(value);
            } else {
                out += '?';
            }
        } else out.append(text, i, semi + 1);
        i += semi;
    }
    return out;
}

std::int64_t parse_date(const std::string& text) {
    // Keep day-level precision; accept ISO date prefixes and common RFC 822/1123 dates.
    auto date = trim(text);
    if (date.size() >= 10 && date[4] == '-' && date[7] == '-') {
        return day_timestamp(decimal(date.substr(0, 4)), decimal(date.substr(5, 2)), decimal(date.substr(8, 2)));
    }
    if (date.size() >= 4 && date[3] == ',') date = trim(date.substr(4));
    const int day = decimal(date_word(date));
    const auto name = date_word(date);
    const auto year_word = date_word(date);
    int year = decimal(year_word);
    if (year >= 0 && year_word.size() == 2) year += year < 50 ? 2000 : 1900;
    constexpr std::string_view months[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                           "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    for (int i = 0; i < 12; ++i) if (months[i] == name) return day_timestamp(year, i + 1, day);
    return 0;
}

bool parse_rss(const std::string& xml, ParsedFeed& out) { return read_feed(xml, Format::Rss, out); }
bool parse_atom(const std::string& xml, ParsedFeed& out) { return read_feed(xml, Format::Atom, out); }
bool parse_feed(const std::string& xml, ParsedFeed& out) { return read_feed(xml, Format::Feed, out); }

bool parse_opml(const std::string& xml, std::vector<OpmlEntry>& out) {
    Document document;
    if (!read_document(xml, Format::Opml, document)) return false;
    out = std::move(document.entries);
    return true;
}

} // namespace podcast
