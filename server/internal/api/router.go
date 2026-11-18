package api

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"strings"
	"time"

	"github.com/podcastapp/server/internal/auth"
	"github.com/podcastapp/server/internal/config"
	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/rss"
	"github.com/podcastapp/server/internal/store"
)

// Version 为服务端语义化版本，客户端可据此判断能力。
const Version = "1.1.0"

// API 汇总所有依赖。
type API struct {
	cfg       *config.Config
	store     *store.Store
	issuer    *auth.TokenIssuer
	fetcher   *rss.Fetcher
	itunes    *itunesClient
	startedAt time.Time
}

// New 构造 API 实例。
func New(cfg *config.Config, st *store.Store, issuer *auth.TokenIssuer) *API {
	return &API{
		cfg:       cfg,
		store:     st,
		issuer:    issuer,
		fetcher:   rss.NewFetcher(cfg.RSSUserAgent, cfg.RSSMaxBytes, cfg.RSSTimeout),
		itunes:    newItunesClient(cfg.ItunesSearchURL),
		startedAt: time.Now(),
	}
}

type route struct {
	pattern   string
	handler   http.HandlerFunc
	protected bool
}

// routes 汇总全部接口；同一 handler 可挂多个别名，便于旧客户端与调试脚本兼容。
func (a *API) routes() []route {
	return []route{
		// 公开
		{"GET /healthz", a.handleHealth, false},
		{"GET /api/v1/healthz", a.handleHealth, false},
		{"GET /api/v1/version", a.handleVersion, false},
		{"POST /api/v1/auth/register", a.handleRegister, false},
		{"POST /api/v1/register", a.handleRegister, false},
		{"POST /api/v1/auth/login", a.handleLogin, false},
		{"POST /api/v1/login", a.handleLogin, false},
		{"POST /api/v1/auth/refresh", a.handleRefresh, false},
		{"POST /api/v1/refresh", a.handleRefresh, false},

		// 账号
		{"POST /api/v1/auth/logout", a.handleLogout, true},
		{"POST /api/v1/logout", a.handleLogout, true},
		{"GET /api/v1/me", a.handleMe, true},
		{"PATCH /api/v1/me", a.handleUpdateProfile, true},
		{"PUT /api/v1/me", a.handleUpdateProfile, true},
		{"POST /api/v1/me/password", a.handleChangePassword, true},
		{"GET /api/v1/settings", a.handleGetSettings, true},
		{"PUT /api/v1/settings", a.handleSaveSettings, true},

		// 内容
		{"GET /api/v1/podcasts", a.handleListPodcasts, true},
		{"POST /api/v1/podcasts", a.handleAddPodcast, true},
		{"GET /api/v1/podcasts/{id}", a.handleGetPodcast, true},
		{"POST /api/v1/podcasts/{id}/refresh", a.handleRefreshPodcast, true},
		{"GET /api/v1/podcasts/{id}/episodes", a.handleListEpisodes, true},
		{"GET /api/v1/episodes/{id}", a.handleGetEpisode, true},
		{"GET /api/v1/discover/search", a.handleDiscover, true},
		{"GET /api/v1/discover/stations", a.handleDiscoverStations, true},

		// 网络电台
		{"GET /api/v1/stations", a.handleListStations, true},
		{"POST /api/v1/stations", a.handleAddStation, true},
		{"POST /api/v1/stations/import", a.handleImportStations, true},
		{"GET /api/v1/stations/probe", a.handleStationProbe, true},
		{"DELETE /api/v1/stations/{id}", a.handleDeleteStation, true},
		{"PUT /api/v1/stations/{id}/favorite", a.handleFavoriteStation, true},
		{"POST /api/v1/stations/{id}/favorite", a.handleFavoriteStation, true},
		{"DELETE /api/v1/stations/{id}/favorite", a.handleFavoriteStation, true},

		// 订阅
		{"GET /api/v1/subscriptions", a.handleListSubscriptions, true},
		{"PUT /api/v1/subscriptions/{podcastId}", a.handleSubscribe, true},
		{"POST /api/v1/subscriptions/{podcastId}", a.handleSubscribe, true},
		{"DELETE /api/v1/subscriptions/{podcastId}", a.handleUnsubscribe, true},

		// 进度
		{"GET /api/v1/progress", a.handleListProgress, true},
		{"GET /api/v1/progress/{episodeId}", a.handleGetProgress, true},
		{"PUT /api/v1/progress/{episodeId}", a.handleSaveProgress, true},
		{"POST /api/v1/progress/{episodeId}", a.handleSaveProgress, true},

		// 收藏 / 待播 / 下载
		{"GET /api/v1/actions", a.handleListActions, true},
		{"PUT /api/v1/actions/{episodeId}", a.handleSaveAction, true},
		{"POST /api/v1/actions/{episodeId}", a.handleSaveAction, true},

		// 播放列表
		{"GET /api/v1/playlists", a.handleListPlaylists, true},
		{"POST /api/v1/playlists", a.handleCreatePlaylist, true},
		{"GET /api/v1/playlists/{id}", a.handleGetPlaylist, true},
		{"DELETE /api/v1/playlists/{id}", a.handleDeletePlaylist, true},
		{"PUT /api/v1/playlists/{id}/items/{episodeId}", a.handleAddPlaylistItem, true},
		{"DELETE /api/v1/playlists/{id}/items/{episodeId}", a.handleRemovePlaylistItem, true},

		// 同步
		{"POST /api/v1/sync", a.handleSync, true},
	}
}

// Handler 组装路由。
func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()
	authMiddleware := httpx.AuthMiddleware(a.issuer)

	for _, rt := range a.routes() {
		var h http.Handler = rt.handler
		if rt.protected {
			h = authMiddleware(h)
		}
		mux.Handle(rt.pattern, h)
	}

	return httpx.Chain(mux,
		httpx.Recover,
		httpx.RequestLog,
		httpx.CORS(a.cfg.AllowedOrigins),
	)
}

func (a *API) handleHealth(w http.ResponseWriter, r *http.Request) {
	dbOK := a.store.DB.PingContext(r.Context()) == nil
	status := "ok"
	code := http.StatusOK
	if !dbOK {
		status = "degraded"
		code = http.StatusServiceUnavailable
	}
	httpx.WriteJSON(w, code, map[string]any{
		"status":   status,
		"database": dbOK,
		"uptime":   time.Since(a.startedAt).Seconds(),
		"version":  Version,
		"api":      "v1",
	})
}

type versionInfo struct {
	Version   string   `json:"version"`
	API       string   `json:"api"`
	MinClient string   `json:"minClient"`
	Features  []string `json:"features"`
}

func (a *API) handleVersion(w http.ResponseWriter, r *http.Request) {
	httpx.WriteJSON(w, http.StatusOK, versionInfo{
		Version:   Version,
		API:       "v1",
		MinClient: "1.0.0",
		Features: []string{
			"auth", "podcasts", "episodes", "subscriptions",
			"progress", "actions", "playlists", "settings", "sync", "discover",
		},
	})
}

func newID() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	return hex.EncodeToString(buf)
}

func validEmail(v string) bool {
	if len(v) < 3 || len(v) > 254 || !strings.Contains(v, "@") {
		return false
	}
	parts := strings.Split(v, "@")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return false
	}
	return strings.Contains(parts[1], ".")
}

func defaultDisplayName(name, email string) string {
	if n := strings.TrimSpace(name); n != "" {
		return n
	}
	if idx := strings.Index(email, "@"); idx > 0 {
		return email[:idx]
	}
	return "听友"
}

func truncate(v string, n int) string {
	if len(v) <= n {
		return v
	}
	return v[:n]
}
