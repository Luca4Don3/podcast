#include "xml.hpp"

#include <iconv.h>

#include <cctype>
#include <cstring>

#include "util.hpp"

namespace podcast::xml {

namespace {

std::string stripPrefix(std::string_view name) {
    const auto colon = name.find(':');
    return std::string(colon == std::string_view::npos ? name : name.substr(colon + 1));
}

struct Cursor {
    std::string_view src;
    std::size_t pos = 0;

    bool eof() const { return pos >= src.size(); }
    char peek(std::size_t offset = 0) const {
        return pos + offset < src.size() ? src[pos + offset] : '\0';
    }
    bool startsWith(std::string_view token) const {
        return src.size() >= pos + token.size() && src.compare(pos, token.size(), token) == 0;
    }
    void skipTo(std::string_view token) {
        const auto found = src.find(token, pos);
        pos = (found == std::string_view::npos) ? src.size() : found + token.size();
    }
    void skipSpace() {
        while (!eof() && std::isspace(static_cast<unsigned char>(peek()))) ++pos;
    }
};

std::string readName(Cursor& cur) {
    const std::size_t start = cur.pos;
    while (!cur.eof()) {
        const char c = cur.peek();
        if (std::isalnum(static_cast<unsigned char>(c)) || c == ':' || c == '_' || c == '-' ||
            c == '.') {
            ++cur.pos;
        } else {
            break;
        }
    }
    return std::string(cur.src.substr(start, cur.pos - start));
}

void skipCommentOrDecl(Cursor& cur) {
    if (cur.startsWith("<!--")) {
        cur.skipTo("-->");
    } else if (cur.startsWith("<?")) {
        cur.skipTo("?>");
    } else if (cur.startsWith("<!")) {
        // DOCTYPE / CDATA 声明块（CDATA 单独处理）
        cur.skipTo(">");
    }
}

// 解析单个元素（调用时 cur 位于 '<'）
bool parseElement(Cursor& cur, Node& out) {
    if (cur.peek() != '<') return false;
    ++cur.pos;  // '<'
    out.name = readName(cur);
    if (out.name.empty()) return false;
    out.localName = stripPrefix(out.name);

    // 属性
    while (!cur.eof()) {
        cur.skipSpace();
        if (cur.peek() == '/' || cur.peek() == '>') break;
        auto key = readName(cur);
        if (key.empty()) { ++cur.pos; continue; }
        cur.skipSpace();
        std::string value;
        if (cur.peek() == '=') {
            ++cur.pos;
            cur.skipSpace();
            const char quote = cur.peek();
            if (quote == '"' || quote == '\'') {
                ++cur.pos;
                const auto start = cur.pos;
                while (!cur.eof() && cur.peek() != quote) ++cur.pos;
                value = decodeEntities(cur.src.substr(start, cur.pos - start));
                if (!cur.eof()) ++cur.pos;
            }
        }
        out.attrs.emplace_back(std::move(key), std::move(value));
    }

    if (cur.startsWith("/>")) {
        cur.pos += 2;
        return true;
    }
    if (cur.peek() != '>') return false;
    ++cur.pos;

    // 内容
    while (!cur.eof()) {
        if (cur.startsWith("</")) {
            cur.pos += 2;
            readName(cur);
            cur.skipSpace();
            if (cur.peek() == '>') ++cur.pos;
            return true;
        }
        if (cur.startsWith("<![CDATA[")) {
            cur.pos += 9;
            const auto start = cur.pos;
            cur.skipTo("]]>");
            const auto end = cur.pos >= 3 ? cur.pos - 3 : cur.pos;
            out.text += std::string(cur.src.substr(start, end - start));
            continue;
        }
        if (cur.startsWith("<!--") || cur.startsWith("<?")) {
            skipCommentOrDecl(cur);
            continue;
        }
        if (cur.peek() == '<') {
            Node child;
            const std::size_t before = cur.pos;
            if (!parseElement(cur, child)) {
                // 容错：跳过这个标签
                cur.pos = before + 1;
                continue;
            }
            out.children.push_back(std::move(child));
            continue;
        }
        const auto start = cur.pos;
        while (!cur.eof() && cur.peek() != '<') ++cur.pos;
        out.text += decodeEntities(cur.src.substr(start, cur.pos - start));
    }
    return true;
}

using Converter = const char* (*)(std::string_view, std::size_t&);

std::string convert(std::string_view input, const char* toCharset) {
    iconv_t cd = iconv_open("UTF-8", toCharset);
    if (cd == reinterpret_cast<iconv_t>(-1)) return std::string(input);
    std::string out;
    out.resize(input.size() * 3 + 16);
    char* inPtr = const_cast<char*>(input.data());
    std::size_t inLeft = input.size();
    char* outPtr = out.data();
    std::size_t outLeft = out.size();
    iconv(cd, &inPtr, &inLeft, &outPtr, &outLeft);
    out.resize(out.size() - outLeft);
    iconv_close(cd);
    return out;
}

std::string detectCharset(std::string_view body, std::string_view contentType) {
    const auto loweredType = util::toLower(contentType);
    const auto charsetPos = loweredType.find("charset=");
    if (charsetPos != std::string::npos) {
        auto value = loweredType.substr(charsetPos + 8);
        const auto semi = value.find(';');
        if (semi != std::string::npos) value = value.substr(0, semi);
        return util::trim(value);
    }
    const auto head = body.substr(0, std::min<std::size_t>(body.size(), 512));
    const auto lowered = util::toLower(head);
    const auto pos = lowered.find("encoding=");
    if (pos != std::string::npos) {
        auto value = head.substr(pos + 9);
        const auto begin = value.find_first_not_of("\"' ");
        if (begin != std::string_view::npos) value = value.substr(begin);
        else value = std::string_view{};
        const auto end = value.find_first_of("\"' ?>");
        if (end != std::string_view::npos) value = value.substr(0, end);
        return util::toLower(util::trim(value));
    }
    return {};
}

}  // namespace

std::string Node::attr(std::string_view key) const {
    for (const auto& [k, v] : attrs) {
        if (k == key) return v;
    }
    return {};
}

const Node* Node::child(std::string_view name) const {
    for (const auto& c : children) {
        if (c.name == name || c.localName == name) return &c;
    }
    return nullptr;
}

std::vector<const Node*> Node::childrenNamed(std::string_view name) const {
    std::vector<const Node*> out;
    for (const auto& c : children) {
        if (c.name == name || c.localName == name) out.push_back(&c);
    }
    return out;
}

std::string Node::childText(std::string_view name) const {
    const auto* node = child(name);
    return node ? util::trim(node->text) : std::string();
}

std::string decodeEntities(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '&') {
            out.push_back(text[i]);
            continue;
        }
        const auto semi = text.find(';', i);
        if (semi == std::string_view::npos || semi - i > 10) {
            out.push_back('&');
            continue;
        }
        const auto entity = text.substr(i + 1, semi - i - 1);
        if (entity == "amp") out.push_back('&');
        else if (entity == "lt") out.push_back('<');
        else if (entity == "gt") out.push_back('>');
        else if (entity == "quot") out.push_back('"');
        else if (entity == "apos") out.push_back('\'');
        else if (!entity.empty() && entity[0] == '#') {
            unsigned int code = 0;
            const std::string digits(entity.substr(entity.size() > 1 && (entity[1] == 'x' || entity[1] == 'X') ? 2 : 1));
            const int base = (entity.size() > 1 && (entity[1] == 'x' || entity[1] == 'X')) ? 16 : 10;
            code = static_cast<unsigned int>(std::strtoul(digits.c_str(), nullptr, base));
            if (code < 0x80) {
                out.push_back(static_cast<char>(code));
            } else if (code < 0x800) {
                out.push_back(static_cast<char>(0xC0 | (code >> 6)));
                out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
            } else {
                out.push_back(static_cast<char>(0xE0 | (code >> 12)));
                out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
            }
        } else {
            out.push_back('&');
            continue;
        }
        i = semi;
    }
    return out;
}

std::string toUtf8(std::string_view body, std::string_view contentType) {
    if (util::isValidUtf8(body)) return std::string(body);
    auto charset = detectCharset(body, contentType);
    if (charset.empty()) charset = "gb18030";
    if (charset == "utf-8" || charset == "utf8") return util::sanitizeUtf8(body);
    if (charset == "gbk" || charset == "gb2312" || charset == "gb-2312" || charset == "gb18030") {
        return convert(body, "GB18030");
    }
    if (charset == "big5" || charset == "big-5" || charset == "cp950") {
        return convert(body, "BIG5");
    }
    if (charset == "iso-8859-1" || charset == "latin1" || charset == "ascii" ||
        charset == "us-ascii") {
        return convert(body, "ISO-8859-1");
    }
    if (charset == "windows-1252" || charset == "cp1252") {
        return convert(body, "CP1252");
    }
    return util::sanitizeUtf8(body);
}

ParseResult parse(std::string_view input) {
    ParseResult result;
    Cursor cur{input};
    while (!cur.eof()) {
        if (cur.startsWith("<!--") || cur.startsWith("<?" ) || cur.startsWith("<!")) {
            skipCommentOrDecl(cur);
            continue;
        }
        if (cur.peek() == '<') {
            Node root;
            if (parseElement(cur, root)) {
                result.root = std::move(root);
                result.ok = true;
                return result;
            }
            result.error = "invalid element";
            return result;
        }
        ++cur.pos;
    }
    result.error = "no root element";
    return result;
}

}  // namespace podcast::xml
