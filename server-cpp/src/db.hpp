#pragma once

#include <sqlite3.h>

#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "json.hpp"

namespace podcast {

// 单行查询结果
using Row = std::vector<std::string>;  // 按列顺序；NULL 用空串

class Database {
public:
    Database() = default;
    ~Database();
    Database(const Database&) = delete;
    Database& operator=(const Database&) = delete;

    bool open(const std::string& path, std::string* error);

    // 执行写操作
    bool exec(std::string_view sql, std::string* error = nullptr);

    // 带参数查询（参数按 ? 顺序绑定，全部按文本绑定）
    std::vector<Row> query(std::string_view sql, const std::vector<std::string>& params,
                           bool* ok = nullptr);

    // 单行单列
    std::optional<std::string> queryScalar(std::string_view sql,
                                           const std::vector<std::string>& params = {});

    // 执行并返回受影响行数
    int run(std::string_view sql, const std::vector<std::string>& params,
            std::string* error = nullptr);

    // 单条语句包装事务
    template <typename Fn>
    bool transaction(Fn&& fn) {
        std::lock_guard<std::mutex> lock(mu_);
        if (sqlite3_exec(db_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
            return false;
        }
        bool ok = false;
        try {
            ok = fn();
        } catch (...) {
            ok = false;
        }
        const char* sql = ok ? "COMMIT" : "ROLLBACK";
        sqlite3_exec(db_, sql, nullptr, nullptr, nullptr);
        return ok;
    }

    sqlite3* handle() const { return db_; }
    bool isOpen() const { return db_ != nullptr; }

    // 健康检查
    bool ping();

private:
    sqlite3* db_ = nullptr;
    std::mutex mu_;
};

// 统一时间
std::string nowIso();
std::string normalizeTime(std::string_view input);

}  // namespace podcast
