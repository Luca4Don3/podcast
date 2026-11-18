#include <signal.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <exception>
#include <string>
#include <thread>

#include "config.hpp"
#include "crypto.hpp"
#include "http.hpp"
#include "json.hpp"
#include "router.hpp"
#include "util.hpp"

namespace {

std::atomic<bool> g_running{true};

void onSignal(int) { g_running = false; }

void addCommonMiddleware(podcast::http::Server& server, const podcast::Config& config) {
    const bool allowAll = config.allowedOrigins.size() == 1 && config.allowedOrigins[0] == "*";
    server.use([&config, allowAll](const podcast::http::Request& req,
                                   podcast::http::Response& res,
                                   const std::function<void()>& next) {
        const auto origin = req.header("origin");
        if (!origin.empty()) {
            bool allowed = allowAll;
            if (!allowed) {
                for (const auto& item : config.allowedOrigins) {
                    if (item == origin) { allowed = true; break; }
                }
            }
            if (allowed) {
                res.headers["Access-Control-Allow-Origin"] = allowAll ? "*" : origin;
                res.headers["Vary"] = "Origin";
            }
            res.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-Device-Id";
            res.headers["Access-Control-Allow-Methods"] = "GET, POST, PATCH, PUT, DELETE, OPTIONS";
            res.headers["Access-Control-Max-Age"] = "600";
        }
        if (req.method == "OPTIONS") {
            res.empty(204);
            return;
        }
        const auto started = std::chrono::steady_clock::now();
        next();
        const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - started);
        std::fprintf(stdout, "{\"level\":\"INFO\",\"msg\":\"http\",\"method\":\"%s\","
                             "\"path\":\"%s\",\"status\":%d,\"dur_us\":%lld}\n",
                     req.method.c_str(), req.path.c_str(), res.status,
                     static_cast<long long>(elapsed.count()));
        std::fflush(stdout);
    });
}

}  // namespace

int main(int argc, char** argv) {
    std::string envFile = ".env";
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if ((arg == "-env-file" || arg == "--env-file") && i + 1 < argc) {
            envFile = argv[++i];
        } else if (arg == "-h" || arg == "--help") {
            std::printf("usage: %s [-env-file path]\n", argv[0]);
            return 0;
        }
    }

    podcast::loadDotEnv(envFile);
    podcast::Config config;
    if (const auto err = podcast::Config::loadFromEnv(config); !err.empty()) {
        std::fprintf(stderr, "config error: %s\n", err.c_str());
        return 1;
    }

    podcast::AppContext ctx;
    ctx.config = config;
    ctx.startedAt = podcast::util::nowIso();

    std::string dbError;
    if (!ctx.db.open(config.databasePath, &dbError)) {
        std::fprintf(stderr, "database error: %s\n", dbError.c_str());
        return 1;
    }

    const auto [host, port] = config.listenAddress();
    const int workers = config.workerThreads > 0 ? config.workerThreads : 8;
    podcast::http::Server server(host, port, workers);

    addCommonMiddleware(server, config);

    // 健康检查与版本
    server.addRoute("GET", "/healthz", [&ctx](const podcast::http::Request&,
                                              podcast::http::Response& res) {
        const bool dbOk = ctx.db.ping();
        res.json(dbOk ? 200 : 503,
                 podcast::Json::makeObject()
                     .set("status", dbOk ? "ok" : "degraded")
                     .set("database", dbOk)
                     .set("version", "1.0.0")
                     .set("api", "v1")
                     .set("startedAt", ctx.startedAt));
        return true;
    });
    server.addRoute("GET", "/api/v1/healthz", [&ctx](const podcast::http::Request&,
                                                     podcast::http::Response& res) {
        const bool dbOk = ctx.db.ping();
        res.json(dbOk ? 200 : 503,
                 podcast::Json::makeObject()
                     .set("status", dbOk ? "ok" : "degraded")
                     .set("database", dbOk)
                     .set("version", "1.0.0"));
        return true;
    });
    server.addRoute("GET", "/api/v1/version", [](const podcast::http::Request&,
                                                 podcast::http::Response& res) {
        podcast::Json features = podcast::Json::makeArray();
        for (const auto* f : {"auth", "podcasts", "episodes", "subscriptions", "progress",
                              "actions", "playlists", "settings", "sync", "discover"}) {
            features.push(podcast::Json(f));
        }
        res.json(200, podcast::Json::makeObject()
                          .set("version", "1.0.0")
                          .set("api", "v1")
                          .set("minClient", "1.0.0")
                          .set("features", features));
        return true;
    });

    podcast::registerAuthRoutes(server, ctx);
    podcast::registerContentRoutes(server, ctx);
    podcast::registerLibraryRoutes(server, ctx);
    podcast::registerSyncRoutes(server, ctx);
    podcast::registerStationRoutes(server, ctx);
    try {
        podcast::registerMediaRoutes(server, ctx, workers);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "media setup error: %s\n", error.what());
        return 1;
    }

    std::string startError;
    if (!server.start(&startError)) {
        std::fprintf(stderr, "listen error: %s\n", startError.c_str());
        return 1;
    }
    std::fprintf(stdout, "{\"level\":\"INFO\",\"msg\":\"server started\",\"addr\":\"%s:%d\"}\n",
                 host.c_str(), port);
    std::fflush(stdout);

    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);

    std::thread watchdog([&server] {
        while (g_running) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
        server.stop();
    });

    server.run();
    g_running = false;
    if (watchdog.joinable()) watchdog.join();
    std::fprintf(stdout, "{\"level\":\"INFO\",\"msg\":\"server stopped\"}\n");
    return 0;
}
