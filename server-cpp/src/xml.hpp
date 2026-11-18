#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace podcast::xml {

// 极简 XML 事件解析器：只处理 RSS/Atom 常见结构，不依赖第三方库。
struct Node {
    std::string name;      // 带前缀的原始名，如 itunes:duration
    std::string localName; // 去前缀，如 duration
    std::string text;      // 直接文本内容
    std::vector<std::pair<std::string, std::string>> attrs;
    std::vector<Node> children;

    std::string attr(std::string_view key) const;
    const Node* child(std::string_view name) const;
    std::vector<const Node*> childrenNamed(std::string_view name) const;
    std::string childText(std::string_view name) const;
};

struct ParseResult {
    bool ok = false;
    Node root;
    std::string error;
};

ParseResult parse(std::string_view input);

// 转码为 UTF-8（支持 GBK/GB18030/Big5/ISO-8859-1，依据 XML 声明或 Content-Type）
std::string toUtf8(std::string_view body, std::string_view contentType);

// HTML/XML 实体解码
std::string decodeEntities(std::string_view text);

}  // namespace podcast::xml
