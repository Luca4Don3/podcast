#include "json.hpp"

#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace podcast {

namespace {
const Json kNull{};

void escapeTo(std::string& out, const std::string& s) {
    out.push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

void numberTo(std::string& out, double value) {
    if (std::isfinite(value) && value == static_cast<double>(static_cast<std::int64_t>(value)) &&
        std::abs(value) < 1e15) {
        out += std::to_string(static_cast<std::int64_t>(value));
        return;
    }
    char buf[40];
    std::snprintf(buf, sizeof(buf), "%.10g", value);
    out += buf;
}
}  // namespace

Json Json::makeObject() {
    Json j;
    j.type_ = Type::Object;
    return j;
}

Json Json::makeArray() {
    Json j;
    j.type_ = Type::Array;
    return j;
}

std::string Json::asString(std::string_view fallback) const {
    if (type_ == Type::String) return string_;
    if (type_ == Type::Bool) return bool_ ? "true" : "false";
    if (type_ == Type::Number) {
        char buf[40];
        std::snprintf(buf, sizeof(buf), "%.10g", number_);
        return buf;
    }
    return std::string(fallback);
}

bool Json::asBool(bool fallback) const {
    if (type_ == Type::Bool) return bool_;
    if (type_ == Type::String) return string_ == "true" || string_ == "1" || string_ == "yes";
    if (type_ == Type::Number) return number_ != 0;
    return fallback;
}

double Json::asNumber(double fallback) const {
    if (type_ == Type::Number) return number_;
    if (type_ == Type::String) return std::strtod(string_.c_str(), nullptr);
    return fallback;
}

std::int64_t Json::asInt64(std::int64_t fallback) const {
    if (type_ == Type::Number) return static_cast<std::int64_t>(number_);
    if (type_ == Type::String) return std::strtoll(string_.c_str(), nullptr, 10);
    return fallback;
}

int Json::asInt(int fallback) const {
    return static_cast<int>(asInt64(fallback));
}

Json& Json::set(const std::string& key, const Json& value) {
    if (type_ != Type::Object) {
        type_ = Type::Object;
        object_.clear();
    }
    object_[key] = value;
    return *this;
}

bool Json::has(const std::string& key) const {
    return type_ == Type::Object && object_.find(key) != object_.end();
}

const Json& Json::get(const std::string& key) const {
    if (type_ != Type::Object) return kNull;
    const auto it = object_.find(key);
    return it == object_.end() ? kNull : it->second;
}

Json& Json::push(const Json& value) {
    if (type_ != Type::Array) {
        type_ = Type::Array;
        array_.clear();
    }
    array_.push_back(value);
    return *this;
}

std::size_t Json::size() const {
    if (type_ == Type::Array) return array_.size();
    if (type_ == Type::Object) return object_.size();
    return 0;
}

std::string Json::dump() const {
    std::string out;
    dumpTo(out);
    return out;
}

void Json::dumpTo(std::string& out) const {
    switch (type_) {
        case Type::Null: out += "null"; break;
        case Type::Bool: out += bool_ ? "true" : "false"; break;
        case Type::Number: numberTo(out, number_); break;
        case Type::String: escapeTo(out, string_); break;
        case Type::Array: {
            out.push_back('[');
            for (std::size_t i = 0; i < array_.size(); ++i) {
                if (i) out.push_back(',');
                array_[i].dumpTo(out);
            }
            out.push_back(']');
            break;
        }
        case Type::Object: {
            out.push_back('{');
            bool first = true;
            for (const auto& [key, value] : object_) {
                if (!first) out.push_back(',');
                first = false;
                escapeTo(out, key);
                out.push_back(':');
                value.dumpTo(out);
            }
            out.push_back('}');
            break;
        }
    }
}

namespace {
struct Parser {
    std::string_view text;
    std::size_t pos = 0;
    std::string error;

    void skipWs() {
        while (pos < text.size() &&
               (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
            ++pos;
        }
    }

    bool fail(const std::string& msg) {
        if (error.empty()) error = msg + " at offset " + std::to_string(pos);
        return false;
    }

    bool parseValue(Json& out) {
        skipWs();
        if (pos >= text.size()) return fail("unexpected end");
        const char c = text[pos];
        switch (c) {
            case '{': return parseObject(out);
            case '[': return parseArray(out);
            case '"': {
                std::string s;
                if (!parseString(s)) return false;
                out = Json(std::move(s));
                return true;
            }
            case 't':
                if (text.substr(pos, 4) == "true") { pos += 4; out = Json(true); return true; }
                return fail("invalid literal");
            case 'f':
                if (text.substr(pos, 5) == "false") { pos += 5; out = Json(false); return true; }
                return fail("invalid literal");
            case 'n':
                if (text.substr(pos, 4) == "null") { pos += 4; out = Json(); return true; }
                return fail("invalid literal");
            default: return parseNumber(out);
        }
    }

    bool parseNumber(Json& out) {
        const std::size_t start = pos;
        if (pos < text.size() && (text[pos] == '-' || text[pos] == '+')) ++pos;
        while (pos < text.size() &&
               (std::isdigit(static_cast<unsigned char>(text[pos])) || text[pos] == '.' ||
                text[pos] == 'e' || text[pos] == 'E' || text[pos] == '-' || text[pos] == '+')) {
            ++pos;
        }
        if (start == pos) return fail("invalid number");
        const std::string token(text.substr(start, pos - start));
        out = Json(std::strtod(token.c_str(), nullptr));
        return true;
    }

    bool parseString(std::string& out) {
        if (pos >= text.size() || text[pos] != '"') return fail("expected string");
        ++pos;
        while (pos < text.size()) {
            const char c = text[pos++];
            if (c == '"') return true;
            if (c != '\\') {
                out.push_back(c);
                continue;
            }
            if (pos >= text.size()) return fail("bad escape");
            const char esc = text[pos++];
            switch (esc) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    if (pos + 4 > text.size()) return fail("bad unicode escape");
                    unsigned int cp = 0;
                    for (int i = 0; i < 4; ++i) {
                        const char h = text[pos++];
                        cp <<= 4;
                        if (h >= '0' && h <= '9') cp |= static_cast<unsigned>(h - '0');
                        else if (h >= 'a' && h <= 'f') cp |= static_cast<unsigned>(h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F') cp |= static_cast<unsigned>(h - 'A' + 10);
                        else return fail("bad hex");
                    }
                    if (cp < 0x80) {
                        out.push_back(static_cast<char>(cp));
                    } else if (cp < 0x800) {
                        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                    } else {
                        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                    }
                    break;
                }
                default: return fail("unknown escape");
            }
        }
        return fail("unterminated string");
    }

    bool parseArray(Json& out) {
        out = Json::makeArray();
        ++pos;  // [
        skipWs();
        if (pos < text.size() && text[pos] == ']') { ++pos; return true; }
        while (true) {
            Json item;
            if (!parseValue(item)) return false;
            out.push(std::move(item));
            skipWs();
            if (pos >= text.size()) return fail("unterminated array");
            if (text[pos] == ',') { ++pos; continue; }
            if (text[pos] == ']') { ++pos; return true; }
            return fail("expected , or ]");
        }
    }

    bool parseObject(Json& out) {
        out = Json::makeObject();
        ++pos;  // {
        skipWs();
        if (pos < text.size() && text[pos] == '}') { ++pos; return true; }
        while (true) {
            skipWs();
            std::string key;
            if (!parseString(key)) return false;
            skipWs();
            if (pos >= text.size() || text[pos] != ':') return fail("expected :");
            ++pos;
            Json value;
            if (!parseValue(value)) return false;
            out.set(key, std::move(value));
            skipWs();
            if (pos >= text.size()) return fail("unterminated object");
            if (text[pos] == ',') { ++pos; continue; }
            if (text[pos] == '}') { ++pos; return true; }
            return fail("expected , or }");
        }
    }
};
}  // namespace

bool Json::parse(std::string_view text, Json& out, std::string* error) {
    Parser parser{text, 0, {}};
    if (!parser.parseValue(out)) {
        if (error) *error = parser.error;
        return false;
    }
    parser.skipWs();
    if (parser.pos != text.size()) {
        if (error) *error = "trailing characters";
        return false;
    }
    return true;
}

}  // namespace podcast
