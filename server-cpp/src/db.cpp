#include "db.hpp"

#include <chrono>
#include <cstring>

#include "util.hpp"

namespace podcast {

namespace {
constexpr const char* kSchema = R"SQL(
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL DEFAULT '',
    role          TEXT NOT NULL DEFAULT 'user',
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    device_id  TEXT NOT NULL DEFAULT '',
    user_agent TEXT NOT NULL DEFAULT '',
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS podcasts (
    id              TEXT PRIMARY KEY,
    feed_url        TEXT NOT NULL UNIQUE,
    title           TEXT NOT NULL,
    author          TEXT NOT NULL DEFAULT '',
    description     TEXT NOT NULL DEFAULT '',
    image_url       TEXT NOT NULL DEFAULT '',
    link            TEXT NOT NULL DEFAULT '',
    language        TEXT NOT NULL DEFAULT '',
    category        TEXT NOT NULL DEFAULT '',
    explicit        INTEGER NOT NULL DEFAULT 0,
    is_catalog      INTEGER NOT NULL DEFAULT 1,
    created_by      TEXT NOT NULL DEFAULT '',
    etag            TEXT NOT NULL DEFAULT '',
    last_modified   TEXT NOT NULL DEFAULT '',
    last_fetched_at TEXT NOT NULL DEFAULT '',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    deleted         INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_podcasts_category ON podcasts(category, deleted);

CREATE TABLE IF NOT EXISTS episodes (
    id               TEXT PRIMARY KEY,
    podcast_id       TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    guid             TEXT NOT NULL,
    title            TEXT NOT NULL,
    description      TEXT NOT NULL DEFAULT '',
    audio_url        TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL DEFAULT 0,
    published_at     TEXT NOT NULL DEFAULT '',
    image_url        TEXT NOT NULL DEFAULT '',
    episode_number   INTEGER NOT NULL DEFAULT 0,
    season_number    INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    deleted          INTEGER NOT NULL DEFAULT 0,
    UNIQUE(podcast_id, guid)
);
CREATE INDEX IF NOT EXISTS idx_episodes_podcast ON episodes(podcast_id, published_at DESC);

CREATE TABLE IF NOT EXISTS subscriptions (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    podcast_id TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    notify     INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, podcast_id)
);

CREATE TABLE IF NOT EXISTS progress (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id  TEXT NOT NULL,
    position_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    completed   INTEGER NOT NULL DEFAULT 0,
    played_at   TEXT NOT NULL DEFAULT '',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, episode_id)
);
CREATE INDEX IF NOT EXISTS idx_progress_user ON progress(user_id, played_at DESC);

CREATE TABLE IF NOT EXISTS playlists (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS playlist_items (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    episode_id  TEXT NOT NULL,
    position    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_playlist_items ON playlist_items(playlist_id, position);

CREATE TABLE IF NOT EXISTS episode_actions (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id  TEXT NOT NULL,
    starred     INTEGER NOT NULL DEFAULT 0,
    queued      INTEGER NOT NULL DEFAULT 0,
    downloaded  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, episode_id)
);

CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         TEXT NOT NULL DEFAULT '',
    platform     TEXT NOT NULL DEFAULT 'android',
    user_agent   TEXT NOT NULL DEFAULT '',
    last_seen_at TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS stations (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL DEFAULT '',
    name        TEXT NOT NULL,
    stream_url  TEXT NOT NULL,
    homepage    TEXT NOT NULL DEFAULT '',
    favicon     TEXT NOT NULL DEFAULT '',
    genre       TEXT NOT NULL DEFAULT '',
    country     TEXT NOT NULL DEFAULT '',
    language    TEXT NOT NULL DEFAULT '',
    codec       TEXT NOT NULL DEFAULT '',
    bitrate     INTEGER NOT NULL DEFAULT 0,
    is_hls      INTEGER NOT NULL DEFAULT 0,
    source      TEXT NOT NULL DEFAULT 'manual',
    external_id TEXT NOT NULL DEFAULT '',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, stream_url)
);
CREATE INDEX IF NOT EXISTS idx_stations_user ON stations(user_id, deleted);

CREATE TABLE IF NOT EXISTS station_favorites (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    station_id TEXT NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, station_id)
);

CREATE TABLE IF NOT EXISTS user_settings (
    user_id          TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    playback_speed   REAL NOT NULL DEFAULT 1.0,
    skip_forward     INTEGER NOT NULL DEFAULT 30,
    skip_back        INTEGER NOT NULL DEFAULT 15,
    auto_download    INTEGER NOT NULL DEFAULT 0,
    download_policy  TEXT NOT NULL DEFAULT 'wifi',
    theme            TEXT NOT NULL DEFAULT 'system',
    notification_on  INTEGER NOT NULL DEFAULT 1,
    updated_at       TEXT NOT NULL,
    deleted          INTEGER NOT NULL DEFAULT 0
);
)SQL";
}  // namespace

Database::~Database() {
    if (db_) {
        sqlite3_close(db_);
        db_ = nullptr;
    }
}

bool Database::open(const std::string& path, std::string* error) {
    const int rc = sqlite3_open_v2(path.c_str(), &db_,
                                   SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE |
                                       SQLITE_OPEN_FULLMUTEX,
                                   nullptr);
    if (rc != SQLITE_OK) {
        if (error) *error = sqlite3_errmsg(db_);
        return false;
    }
    sqlite3_busy_timeout(db_, 10000);
    sqlite3_exec(db_, "PRAGMA journal_mode=WAL", nullptr, nullptr, nullptr);
    sqlite3_exec(db_, "PRAGMA foreign_keys=ON", nullptr, nullptr, nullptr);
    sqlite3_exec(db_, "PRAGMA synchronous=NORMAL", nullptr, nullptr, nullptr);
    if (!exec(kSchema, error)) return false;
    return true;
}

bool Database::exec(std::string_view sql, std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    char* err = nullptr;
    const int rc = sqlite3_exec(db_, std::string(sql).c_str(), nullptr, nullptr, &err);
    if (rc != SQLITE_OK) {
        if (error) *error = err ? err : sqlite3_errmsg(db_);
        if (err) sqlite3_free(err);
        return false;
    }
    return true;
}

std::vector<Row> Database::query(std::string_view sql,
                                 const std::vector<std::string>& params, bool* ok) {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<Row> rows;
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, std::string(sql).c_str(), -1, &stmt, nullptr) != SQLITE_OK) {
        if (ok) *ok = false;
        return rows;
    }
    for (std::size_t i = 0; i < params.size(); ++i) {
        sqlite3_bind_text(stmt, static_cast<int>(i + 1), params[i].c_str(),
                          static_cast<int>(params[i].size()), SQLITE_TRANSIENT);
    }
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        Row row;
        const int cols = sqlite3_column_count(stmt);
        row.reserve(static_cast<std::size_t>(cols));
        for (int c = 0; c < cols; ++c) {
            const auto* text = sqlite3_column_text(stmt, c);
            row.emplace_back(text ? reinterpret_cast<const char*>(text) : "");
        }
        rows.push_back(std::move(row));
    }
    sqlite3_finalize(stmt);
    if (ok) *ok = true;
    return rows;
}

std::optional<std::string> Database::queryScalar(std::string_view sql,
                                                 const std::vector<std::string>& params) {
    bool ok = false;
    const auto rows = query(sql, params, &ok);
    if (!ok || rows.empty() || rows[0].empty()) return std::nullopt;
    return rows[0][0];
}

int Database::run(std::string_view sql, const std::vector<std::string>& params,
                  std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, std::string(sql).c_str(), -1, &stmt, nullptr) != SQLITE_OK) {
        if (error) *error = sqlite3_errmsg(db_);
        return -1;
    }
    for (std::size_t i = 0; i < params.size(); ++i) {
        sqlite3_bind_text(stmt, static_cast<int>(i + 1), params[i].c_str(),
                          static_cast<int>(params[i].size()), SQLITE_TRANSIENT);
    }
    const int rc = sqlite3_step(stmt);
    const int changed = sqlite3_changes(db_);
    if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
        if (error) *error = sqlite3_errmsg(db_);
        sqlite3_finalize(stmt);
        return -1;
    }
    sqlite3_finalize(stmt);
    return changed;
}

bool Database::ping() {
    std::lock_guard<std::mutex> lock(mu_);
    if (!db_) return false;
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT 1", -1, &stmt, nullptr) != SQLITE_OK) return false;
    const bool ok = sqlite3_step(stmt) == SQLITE_ROW;
    sqlite3_finalize(stmt);
    return ok;
}

std::string nowIso() { return util::nowIso(); }

std::string normalizeTime(std::string_view input) { return util::normalizeTime(input); }

}  // namespace podcast
