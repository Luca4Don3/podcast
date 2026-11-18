#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace podcast::util {

// 十六进制随机 ID（默认 32 字符）
std::string randomHex(std::size_t bytes = 16);

// SHA-256 十六进制摘要
std::string sha256Hex(std::string_view input);

// SHA-256 原始字节
std::string sha256Raw(std::string_view input);

// Base64URL（无填充）
std::string base64UrlEncode(std::string_view input);
std::string base64UrlDecode(std::string_view input);

// 标准 Base64
std::string base64Encode(std::string_view input);

// URL 编解码
std::string urlDecode(std::string_view input);
std::string urlEncode(std::string_view input);

// 字符串工具
std::string toLower(std::string_view s);
std::string trim(std::string_view s);
std::vector<std::string> split(std::string_view s, char delim);
bool startsWith(std::string_view s, std::string_view prefix);
bool endsWith(std::string_view s, std::string_view suffix);
std::string join(const std::vector<std::string>& parts, std::string_view sep);

// 时间
std::string nowIso();                                  // UTC RFC3339Nano
std::string normalizeTime(std::string_view input);     // 兼容多种格式
std::int64_t nowUnix();

// 数字
int toInt(std::string_view s, int fallback);
std::int64_t toInt64(std::string_view s, std::int64_t fallback);

// UTF-8 校验/处理
bool isValidUtf8(std::string_view s);
std::string sanitizeUtf8(std::string_view s);

}  // namespace podcast::util
