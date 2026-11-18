#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace podcast {

struct Config {
    std::string addr = ":8080";
    std::string env = "development";
    std::string dataDir = "./data";
    std::string databasePath;
    std::string publicBaseUrl;
    std::string jwtSecret;
    std::int64_t accessTokenTtlSec = 15 * 60;
    std::int64_t refreshTokenTtlSec = 30 * 24 * 3600;
    std::vector<std::string> allowedOrigins{"*"};
    std::string inviteCode;
    std::string rssUserAgent = "PodcastApp/1.0";
    std::size_t rssMaxBytes = 20u * 1024u * 1024u;
    int rssTimeoutSec = 20;
    // 外部目录须由管理员显式启用；空值禁用，不回退全球服务。
    std::string itunesSearchUrl;
    std::string radioBrowserUrl;
    int workerThreads = 0;  // 0 = 自动
    // Opt-in, scoped to one explicitly provisioned user ID.
    std::string fileMediaOwnerId;
    std::string fileMediaRoot;
    std::string fileMediaDavUrl;
    std::string fileMediaDavUser;
    std::string fileMediaDavPassword;

    // 从环境变量装载；返回错误信息（空表示成功）
    static std::string loadFromEnv(Config& out);
    // 解析 host / port
    std::pair<std::string, int> listenAddress() const;
};

// 读取 .env（不覆盖已存在的环境变量）
bool loadDotEnv(const std::string& path);

}  // namespace podcast
