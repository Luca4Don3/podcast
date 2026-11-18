#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace podcast {

// 轻量 JSON（无第三方依赖）：支持 object/array/string/number/bool/null
class Json {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Json() = default;
    Json(std::nullptr_t) {}
    Json(bool value) : type_(Type::Bool), bool_(value) {}
    Json(int value) : type_(Type::Number), number_(value) {}
    Json(std::int64_t value) : type_(Type::Number), number_(static_cast<double>(value)) {}
    Json(double value) : type_(Type::Number), number_(value) {}
    Json(const char* value) : type_(Type::String), string_(value) {}
    Json(std::string value) : type_(Type::String), string_(std::move(value)) {}

    static Json makeObject();
    static Json makeArray();

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isObject() const { return type_ == Type::Object; }
    bool isArray() const { return type_ == Type::Array; }
    bool isString() const { return type_ == Type::String; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isBool() const { return type_ == Type::Bool; }

    std::string asString(std::string_view fallback = {}) const;
    bool asBool(bool fallback = false) const;
    double asNumber(double fallback = 0) const;
    std::int64_t asInt64(std::int64_t fallback = 0) const;
    int asInt(int fallback = 0) const;

    Json& set(const std::string& key, const Json& value);
    Json& set(const std::string& key, bool value) { return set(key, Json(value)); }
    Json& set(const std::string& key, int value) { return set(key, Json(value)); }
    Json& set(const std::string& key, std::int64_t value) { return set(key, Json(value)); }
    Json& set(const std::string& key, double value) { return set(key, Json(value)); }
    Json& set(const std::string& key, const std::string& value) { return set(key, Json(value)); }
    Json& set(const std::string& key, const char* value) { return set(key, Json(value)); }
    bool has(const std::string& key) const;
    const Json& get(const std::string& key) const;
    const std::map<std::string, Json>& objectMap() const { return object_; }

    Json& push(const Json& value);
    const std::vector<Json>& arrayItems() const { return array_; }
    std::size_t size() const;

    std::string dump() const;
    static bool parse(std::string_view text, Json& out, std::string* error = nullptr);

private:
    void dumpTo(std::string& out) const;

    Type type_ = Type::Null;
    bool bool_ = false;
    double number_ = 0;
    std::string string_;
    std::vector<Json> array_;
    std::map<std::string, Json> object_;
};

}  // namespace podcast
