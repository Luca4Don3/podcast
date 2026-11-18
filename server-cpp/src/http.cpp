#include "http.hpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstring>
#include <sstream>
#include <thread>

#include "util.hpp"

namespace podcast::http {

namespace {
constexpr std::size_t kMaxHeaderBytes = 64 * 1024;
constexpr std::size_t kMaxBodyBytes = 8 * 1024 * 1024;
using Clock = std::chrono::steady_clock;
constexpr auto kReadTimeout = std::chrono::seconds(15);
constexpr auto kWriteTimeout = std::chrono::seconds(15);

struct Connection {
    int fd;
    ~Connection() { ::close(fd); }
};

bool waitFor(int fd, short events, Clock::time_point deadline) {
    pollfd pfd{fd, events, 0};
    for (;;) {
        const auto now = Clock::now();
        if (now >= deadline) return false;
        const auto ms = std::chrono::ceil<std::chrono::milliseconds>(deadline - now);
        const int rc = ::poll(&pfd, 1, static_cast<int>(ms.count()));
        if (rc > 0) return (pfd.revents & events) && !(pfd.revents & (POLLERR | POLLNVAL));
        if (rc == 0 || errno != EINTR) return false;
    }
}

class RequestReader {
public:
    explicit RequestReader(int fd) : fd_(fd), deadline_(Clock::now() + kReadTimeout) {}

    bool readLine(std::string& out, std::size_t& budget) {
        out.clear();
        while (budget > 0 && refill()) {
            const char c = buf_[pos_++];
            --budget;
            if (c == '\n') {
                if (!out.empty() && out.back() == '\r') out.pop_back();
                return true;
            }
            if (out.size() == kMaxHeaderBytes) return false;
            out.push_back(c);
        }
        return false;
    }

    bool readExact(char* out, std::size_t count) {
        while (count > 0 && refill()) {
            const auto n = std::min(count, end_ - pos_);
            std::memcpy(out, buf_.data() + pos_, n);
            out += n;
            pos_ += n;
            count -= n;
        }
        return count == 0;
    }

private:
    bool refill() {
        if (pos_ < end_) return true;
        while (Clock::now() < deadline_) {
            const auto n = ::recv(fd_, buf_.data(), buf_.size(), MSG_DONTWAIT);
            if (n > 0) {
                pos_ = 0;
                end_ = static_cast<std::size_t>(n);
                return true;
            }
            if (n == 0) return false;
            if (errno == EINTR) continue;
            if ((errno != EAGAIN && errno != EWOULDBLOCK) || !waitFor(fd_, POLLIN, deadline_)) {
                return false;
            }
        }
        return false;
    }

    int fd_;
    Clock::time_point deadline_;
    std::array<char, 8192> buf_;
    std::size_t pos_ = 0;
    std::size_t end_ = 0;
};

bool parseSize(std::string_view text, int base, std::size_t& out) {
    if (text.empty()) return false;
    const auto* end = text.data() + text.size();
    const auto result = std::from_chars(text.data(), end, out, base);
    return result.ec == std::errc{} && result.ptr == end;
}

bool sendAll(int fd, std::string_view data) {
    auto deadline = Clock::now() + kWriteTimeout;
    while (!data.empty() && Clock::now() < deadline) {
        const auto n = ::send(fd, data.data(), data.size(), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) {
            data.remove_prefix(static_cast<std::size_t>(n));
            deadline = Clock::now() + kWriteTimeout;
        } else {
            if (n == 0) return false;
            if (errno == EINTR) continue;
            if ((errno != EAGAIN && errno != EWOULDBLOCK) || !waitFor(fd, POLLOUT, deadline)) {
                return false;
            }
        }
    }
    return data.empty();
}

bool statusAllowsBody(int status) {
    return !(status >= 100 && status < 200) && status != 204 && status != 304;
}

bool sendResponse(int fd, const Response& res, bool head) {
    const bool allowsBody = statusAllowsBody(res.status);
    std::string header = "HTTP/1.1 " + std::to_string(res.status) + " " + statusText(res.status) + "\r\n";
    header += "Content-Type: " + res.contentType + "\r\n";
    if (allowsBody || (res.status == 304 && res.streamLength)) {
        header += "Content-Length: " + std::to_string(res.streamLength.value_or(res.body.size())) + "\r\n";
    }
    header += "Connection: close\r\n";
    for (const auto& [key, value] : res.headers) {
        const auto name = util::toLower(key);
        if (name == "content-length" || name == "transfer-encoding" || name == "connection") continue;
        header += key + ": " + value + "\r\n";
    }
    header += "\r\n";
    if (!sendAll(fd, header)) return false;
    if (head || !allowsBody) return true;
    if (!res.streamLength) return sendAll(fd, res.body);
    if (!res.streamBody) return *res.streamLength == 0;

    auto remaining = *res.streamLength;
    bool failed = false;
    const Response::StreamSink sink = [&](std::string_view chunk) {
        if (failed) return false;
        if (chunk.size() > remaining || !sendAll(fd, chunk)) {
            failed = true;
            return false;
        }
        remaining -= chunk.size();
        return true;
    };
    // 出错时响应头已发出，只关闭连接，绝不补写另一条 HTTP 响应。
    try {
        return res.streamBody(sink) && !failed && remaining == 0;
    } catch (...) {
        return false;
    }
}

std::string trimmedLower(std::string_view s) { return util::toLower(util::trim(s)); }
}  // namespace

std::string Request::header(std::string_view name) const {
    const auto it = headers.find(util::toLower(name));
    return it == headers.end() ? std::string() : it->second;
}

std::string Request::queryParam(std::string_view name, std::string_view fallback) const {
    const auto it = query.find(std::string(name));
    return it == query.end() ? std::string(fallback) : it->second;
}

std::string Request::bearerToken() const {
    const auto value = header("authorization");
    if (value.size() > 7 && util::toLower(value.substr(0, 7)) == "bearer ") {
        return util::trim(value.substr(7));
    }
    return {};
}

void Response::json(int code, const Json& payload) {
    streamLength.reset();
    streamBody = {};
    status = code;
    contentType = "application/json; charset=utf-8";
    body = payload.dump();
}

void Response::text(int code, std::string_view text) {
    streamLength.reset();
    streamBody = {};
    status = code;
    contentType = "text/plain; charset=utf-8";
    body = std::string(text);
}

void Response::empty(int code) {
    streamLength.reset();
    streamBody = {};
    status = code;
    body.clear();
}

std::string statusText(int code) {
    switch (code) {
        case 200: return "OK";
        case 201: return "Created";
        case 204: return "No Content";
        case 206: return "Partial Content";
        case 301: return "Moved Permanently";
        case 302: return "Found";
        case 304: return "Not Modified";
        case 400: return "Bad Request";
        case 401: return "Unauthorized";
        case 403: return "Forbidden";
        case 404: return "Not Found";
        case 405: return "Method Not Allowed";
        case 409: return "Conflict";
        case 413: return "Payload Too Large";
        case 416: return "Range Not Satisfiable";
        case 429: return "Too Many Requests";
        case 500: return "Internal Server Error";
        case 502: return "Bad Gateway";
        case 503: return "Service Unavailable";
        default: return "OK";
    }
}

std::string urlDecode(std::string_view input) { return util::urlDecode(input); }

std::vector<std::string> splitPath(std::string_view path) {
    std::vector<std::string> out;
    std::size_t start = 0;
    while (start < path.size()) {
        const auto pos = path.find('/', start);
        const auto end = (pos == std::string_view::npos) ? path.size() : pos;
        if (end > start) out.emplace_back(path.substr(start, end - start));
        if (pos == std::string_view::npos) break;
        start = pos + 1;
    }
    return out;
}

Server::Server(std::string host, int port, int workers)
    : host_(std::move(host)), port_(port), workers_(workers > 0 ? workers : 4) {}

Server::~Server() { stop(); }

void Server::addRoute(std::string_view method, std::string_view pattern, Handler handler) {
    Route route;
    route.method = util::toLower(method);
    route.segments = splitPath(pattern);
    route.handler = std::move(handler);
    routes_.push_back(std::move(route));
}

void Server::use(std::function<void(const Request&, Response&, const std::function<void()>&)> mw) {
    middlewares_.push_back(std::move(mw));
}

bool Server::start(std::string* error) {
    // 判断目标地址族：显式 IPv4 用 AF_INET，其余（:: / 0.0.0.0 / 主机名）优先 IPv6 双栈
    bool wantIpv6 = true;
    if (!host_.empty() && host_ != "::" && host_ != "0.0.0.0" && host_ != "[::]") {
        in_addr probe4{};
        wantIpv6 = (::inet_pton(AF_INET, host_.c_str(), &probe4) != 1);
    }
    bool ipv6 = wantIpv6;
    listenFd_ = ::socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM, 0);
    if (listenFd_ < 0 && ipv6) {
        listenFd_ = ::socket(AF_INET, SOCK_STREAM, 0);
        ipv6 = false;
    }
    if (listenFd_ < 0) {
        if (error) *error = std::strerror(errno);
        return false;
    }
    int one = 1;
    ::setsockopt(listenFd_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    int rc = -1;
    if (ipv6) {
        sockaddr_in6 addr{};
        addr.sin6_family = AF_INET6;
        addr.sin6_port = htons(static_cast<uint16_t>(port_));
        if (host_.empty() || host_ == "::" || host_ == "0.0.0.0") {
            addr.sin6_addr = in6addr_any;
        } else {
            ::inet_pton(AF_INET6, host_.c_str(), &addr.sin6_addr);
        }
        ::setsockopt(listenFd_, IPPROTO_IPV6, IPV6_V6ONLY, &one, sizeof(one));
        rc = ::bind(listenFd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    } else {
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<uint16_t>(port_));
        if (host_.empty() || host_ == "0.0.0.0") {
            addr.sin_addr.s_addr = htonl(INADDR_ANY);
        } else if (::inet_pton(AF_INET, host_.c_str(), &addr.sin_addr) != 1) {
            addr.sin_addr.s_addr = htonl(INADDR_ANY);
        }
        rc = ::bind(listenFd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    }
    if (rc != 0) {
        ::close(listenFd_);
        listenFd_ = -1;
        if (error) *error = std::strerror(errno);
        return false;
    }
    if (::listen(listenFd_, 256) != 0) {
        if (error) *error = std::strerror(errno);
        ::close(listenFd_);
        listenFd_ = -1;
        return false;
    }
    running_ = true;
    return true;
}

void Server::stop() {
    running_ = false;
    if (listenFd_ >= 0) {
        ::shutdown(listenFd_, SHUT_RDWR);
        ::close(listenFd_);
        listenFd_ = -1;
    }
    // 同一组线程可能被 stop() 与 run() 同时 join，用互斥保证只 join 一次
    std::lock_guard<std::mutex> lock(threadsMu_);
    for (auto& t : threads_) {
        if (t.joinable()) {
            if (std::this_thread::get_id() == t.get_id()) continue;
            t.join();
        }
    }
    threads_.clear();
}

void Server::run() {
    // 简单的 accept + 线程池：每个连接一个线程（足够单机播客后端）
    {
        std::lock_guard<std::mutex> lock(threadsMu_);
        for (int i = 0; i < workers_; ++i) {
            threads_.emplace_back([this] {
            while (running_) {
                sockaddr_storage peer{};
                socklen_t len = sizeof(peer);
                const int fd = ::accept(listenFd_, reinterpret_cast<sockaddr*>(&peer), &len);
                if (fd < 0) {
                    if (!running_) break;
                    continue;
                }
                    handleConnection(fd);
                }
            });
        }
    }
    std::lock_guard<std::mutex> lock(threadsMu_);
    for (auto& t : threads_) {
        if (t.joinable() && std::this_thread::get_id() != t.get_id()) t.join();
    }
    threads_.clear();
}

void Server::handleConnection(int fd) {
    const Connection connection{fd};
    int one = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    RequestReader reader(fd);
    std::size_t headerBudget = kMaxHeaderBytes;
    Request req;
    std::string line;
    if (!reader.readLine(line, headerBudget)) return;
    std::istringstream requestLine(line);
    std::string target;
    std::string version;
    requestLine >> req.method >> target >> version;
    if (req.method.empty() || target.empty()) return;
    req.rawPath = target;
    const auto qpos = target.find('?');
    req.path = urlDecode(qpos == std::string::npos ? target : target.substr(0, qpos));
    if (qpos != std::string::npos) {
        req.queryString = target.substr(qpos + 1);
        for (const auto& pair : util::split(req.queryString, '&')) {
            if (pair.empty()) continue;
            const auto eq = pair.find('=');
            const auto key = util::urlDecode(eq == std::string::npos ? pair : pair.substr(0, eq));
            const auto value =
                util::urlDecode(eq == std::string::npos ? std::string() : pair.substr(eq + 1));
            req.query[key] = value;
        }
    }

    // 只在完整且有界的请求读完后派发。
    for (;;) {
        if (!reader.readLine(line, headerBudget)) return;
        if (line.empty()) break;
        const auto colon = line.find(':');
        if (colon == std::string::npos) return;
        auto name = trimmedLower(line.substr(0, colon));
        if ((name == "content-length" || name == "transfer-encoding") && req.headers.contains(name)) {
            return;
        }
        req.headers[std::move(name)] = util::trim(line.substr(colon + 1));
    }

    const auto encoding = req.headers.find("transfer-encoding");
    const auto length = req.headers.find("content-length");
    if (encoding != req.headers.end()) {
        if (length != req.headers.end() || util::toLower(encoding->second) != "chunked") return;
        std::size_t chunkBudget = kMaxBodyBytes;  // 块大小行与分隔符的独立总上限
        for (;;) {
            if (!reader.readLine(line, chunkBudget)) return;
            const auto sizeText = std::string_view(line).substr(0, line.find(';'));
            std::size_t size = 0;
            if (!parseSize(util::trim(sizeText), 16, size) || size > kMaxBodyBytes - req.body.size()) {
                return;
            }
            if (size == 0) {
                do {
                    if (!reader.readLine(line, headerBudget)) return;
                } while (!line.empty());
                break;
            }
            const auto offset = req.body.size();
            req.body.resize(offset + size);
            if (!reader.readExact(req.body.data() + offset, size)) return;
            char separator[2];
            if (chunkBudget < sizeof(separator) || !reader.readExact(separator, sizeof(separator)) ||
                std::string_view(separator, sizeof(separator)) != "\r\n") {
                return;
            }
            chunkBudget -= sizeof(separator);
        }
    } else if (length != req.headers.end()) {
        std::size_t size = 0;
        if (!parseSize(length->second, 10, size) || size > kMaxBodyBytes) return;
        req.body.resize(size);
        if (!reader.readExact(req.body.data(), size)) return;
    }

    const bool head = util::toLower(req.method) == "head";
    Response res;
    try {
        if (!dispatch(req, res)) {
            res.json(404, Json::makeObject().set("code", "not_found").set("message", "接口不存在"));
        }
    } catch (const std::exception&) {
        res.json(500, Json::makeObject().set("code", "internal").set("message", "服务器内部错误"));
    }
    if ((res.streamBody && !res.streamLength) ||
        (!head && statusAllowsBody(res.status) && res.streamLength && *res.streamLength != 0 &&
         !res.streamBody)) {
        res.json(500, Json::makeObject().set("code", "internal").set("message", "服务器内部错误"));
    }
    sendResponse(fd, res, head);
}

bool Server::dispatch(const Request& req, Response& res) {
    // 中间件链：从后往前包裹
    std::size_t mwIndex = 0;
    std::function<void()> callNext = [&] {
        if (mwIndex < middlewares_.size()) {
            auto mw = middlewares_[mwIndex++];
            mw(req, res, callNext);
            return;
        }
        const auto reqSegments = splitPath(req.path);
        bool methodMatched = false;
        for (const auto& route : routes_) {
            if (route.segments.size() != reqSegments.size() &&
                !(route.segments.empty() && reqSegments.empty())) {
                continue;
            }
            const bool methodOk = route.method == "all" || route.method == util::toLower(req.method);
            if (!methodOk) {
                continue;
            }
            bool match = true;
            for (std::size_t i = 0; i < route.segments.size(); ++i) {
                const auto& seg = route.segments[i];
                if (!seg.empty() && seg[0] == ':') continue;
                if (seg != reqSegments[i]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            methodMatched = true;
            if (route.handler(req, res)) return;
        }
        if (!methodMatched) res.status = 404;
    };
    callNext();
    return res.status != 404 || !res.body.empty() || res.streamLength || res.streamBody;
}

}  // namespace podcast::http
