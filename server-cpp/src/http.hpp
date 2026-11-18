#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "json.hpp"

namespace podcast::http {

struct Request {
    std::string method;
    std::string path;                       // 已解码，不含 query
    std::string rawPath;
    std::string queryString;
    std::map<std::string, std::string> query;
    std::map<std::string, std::string> headers;  // key 已转小写
    std::string body;
    std::string remoteAddr;

    std::string header(std::string_view name) const;
    std::string queryParam(std::string_view name, std::string_view fallback = {}) const;
    std::string bearerToken() const;
};

struct Response {
    int status = 200;
    std::string contentType = "application/json; charset=utf-8";
    std::map<std::string, std::string> headers;
    std::string body;

    using StreamSink = std::function<bool(std::string_view)>;
    using StreamBody = std::function<bool(const StreamSink&)>;
    // 设置长度后使用流而非 body；HEAD 仅使用长度，不调用生产者。
    // 生产者同步调用 sink，不得保留它；sink 返回 false 时立即停止并返回 false。
    std::optional<std::uint64_t> streamLength;
    StreamBody streamBody;

    void json(int code, const Json& payload);
    void text(int code, std::string_view text);
    void empty(int code = 204);
};

// 处理器：返回 false 表示未匹配
using Handler = std::function<bool(const Request&, Response&)>;

class Server {
public:
    Server(std::string host, int port, int workers);
    ~Server();

    // 注册路由（支持 /path/:param 形式）
    void addRoute(std::string_view method, std::string_view pattern, Handler handler);
    // 全局中间件（CORS、日志等）
    void use(std::function<void(const Request&, Response&, const std::function<void()>&)> mw);

    bool start(std::string* error);
    void stop();
    // 阻塞运行直到 stop()
    void run();
    int port() const { return port_; }

private:
    struct Route {
        std::string method;
        std::vector<std::string> segments;
        Handler handler;
    };

    bool dispatch(const Request& req, Response& res);
    void handleConnection(int fd);

    std::string host_;
    int port_;
    int workers_;
    int listenFd_ = -1;
    std::atomic<bool> running_{false};
    std::vector<Route> routes_;
    std::vector<std::function<void(const Request&, Response&, const std::function<void()>&)>> middlewares_;
    std::vector<std::thread> threads_;
    std::mutex threadsMu_;
};

// 工具
std::string statusText(int code);
std::string urlDecode(std::string_view input);
std::vector<std::string> splitPath(std::string_view path);

}  // namespace podcast::http
