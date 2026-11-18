package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/podcastapp/server/internal/auth"
	"github.com/podcastapp/server/internal/config"
	"github.com/podcastapp/server/internal/store"
)

const sampleFeed = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>测试播客</title>
    <description>一个用于测试的播客</description>
    <language>zh-CN</language>
    <itunes:author>测试主播</itunes:author>
    <itunes:image href="https://example.com/cover.jpg"/>
    <itunes:category text="Technology"/>
    <item>
      <title>第一集：开场</title>
      <guid>ep-1</guid>
      <pubDate>Mon, 14 Oct 2024 08:00:00 GMT</pubDate>
      <itunes:duration>01:02:03</itunes:duration>
      <description>第一集的简介</description>
      <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
    </item>
    <item>
      <title>第二集：继续</title>
      <guid>ep-2</guid>
      <pubDate>Mon, 21 Oct 2024 08:00:00 GMT</pubDate>
      <itunes:duration>1800</itunes:duration>
      <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="12345"/>
    </item>
  </channel>
</rss>`

type testEnv struct {
	api     *API
	handler http.Handler
	feedURL string
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{
		Env:             "test",
		DataDir:         dir,
		DatabaseDSN:     "file:" + filepath.Join(dir, "test.db") + "?_pragma=busy_timeout(10000)&_pragma=foreign_keys(1)",
		JWTSecret:       "test-secret-value-1234567890",
		AccessTokenTTL:  time.Minute,
		RefreshTokenTTL: time.Hour,
		AllowedOrigins:  []string{"*"},
		RSSUserAgent:    "PodcastAppTest/1.0",
		RSSMaxBytes:     1 << 20,
		RSSTimeout:      5 * time.Second,
	}
	st, err := store.Open(context.Background(), cfg.DatabaseDSN)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	feedSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write([]byte(sampleFeed))
	}))
	t.Cleanup(feedSrv.Close)

	issuer := auth.NewTokenIssuer(cfg.JWTSecret, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)
	a := New(cfg, st, issuer)
	return &testEnv{api: a, handler: a.Handler(), feedURL: feedSrv.URL + "/feed.xml"}
}

func (e *testEnv) do(t *testing.T, method, path, token string, body any) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("encode body: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.handler.ServeHTTP(rec, req)
	out := map[string]any{}
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec, out
}

func (e *testEnv) register(t *testing.T, email string) (string, string) {
	t.Helper()
	rec, body := e.do(t, http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": email, "password": "supersecret123", "displayName": "测试用户",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register status=%d body=%s", rec.Code, rec.Body.String())
	}
	token := body["token"].(map[string]any)
	return token["accessToken"].(string), token["refreshToken"].(string)
}

func TestHealth(t *testing.T) {
	env := newTestEnv(t)
	rec, _ := env.do(t, http.MethodGet, "/healthz", "", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("health status=%d", rec.Code)
	}
}

func TestRegisterLoginRefresh(t *testing.T) {
	env := newTestEnv(t)
	access, refresh := env.register(t, "Alice@Example.com")

	rec, _ := env.do(t, http.MethodGet, "/api/v1/me", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("me status=%d body=%s", rec.Code, rec.Body.String())
	}

	// 重复注册
	rec, _ = env.do(t, http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": "alice@example.com", "password": "supersecret123",
	})
	if rec.Code != http.StatusConflict {
		t.Fatalf("duplicate register status=%d", rec.Code)
	}

	// 登录
	rec, body := env.do(t, http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": "alice@example.com", "password": "supersecret123", "deviceId": "device-1",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("login status=%d body=%s", rec.Code, rec.Body.String())
	}
	if body["user"].(map[string]any)["role"] != "admin" {
		t.Fatalf("first user should be admin: %v", body["user"])
	}

	// 错误密码
	rec, _ = env.do(t, http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": "alice@example.com", "password": "wrong-password",
	})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("bad password status=%d", rec.Code)
	}

	// 刷新并旋转
	rec, body = env.do(t, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"refreshToken": refresh})
	if rec.Code != http.StatusOK {
		t.Fatalf("refresh status=%d body=%s", rec.Code, rec.Body.String())
	}
	newRefresh := body["token"].(map[string]any)["refreshToken"].(string)
	if newRefresh == refresh {
		t.Fatal("refresh token should rotate")
	}
	rec, _ = env.do(t, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"refreshToken": refresh})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("reused refresh token status=%d", rec.Code)
	}
}

func TestSubscriptionAndProgress(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "bob@example.com")

	rec, body := env.do(t, http.MethodPost, "/api/v1/podcasts", access, map[string]any{"feedUrl": env.feedURL})
	if rec.Code != http.StatusOK {
		t.Fatalf("add podcast status=%d body=%s", rec.Code, rec.Body.String())
	}
	podcast := body["podcast"].(map[string]any)
	podcastID := podcast["id"].(string)
	if podcast["title"] != "测试播客" {
		t.Fatalf("unexpected title %v", podcast["title"])
	}

	rec, body = env.do(t, http.MethodGet, "/api/v1/podcasts/"+podcastID+"/episodes", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("episodes status=%d", rec.Code)
	}
	items := body["items"].([]any)
	if len(items) != 2 {
		t.Fatalf("expected 2 episodes, got %d", len(items))
	}
	var episodeID string
	var durations []float64
	for _, it := range items {
		m := it.(map[string]any)
		durations = append(durations, m["durationSeconds"].(float64))
		if m["title"] == "第一集：开场" {
			episodeID = m["id"].(string)
			if m["durationSeconds"].(float64) != 3723 {
				t.Fatalf("hh:mm:ss duration parse failed: %v", m["durationSeconds"])
			}
		}
	}
	if episodeID == "" {
		t.Fatalf("episode not found: %v", durations)
	}

	rec, _ = env.do(t, http.MethodGet, "/api/v1/subscriptions", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("subscriptions status=%d", rec.Code)
	}

	rec, _ = env.do(t, http.MethodPut, "/api/v1/progress/"+episodeID, access, map[string]any{
		"positionMs": 61000, "durationMs": 3723000, "completed": false,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("save progress status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/progress/"+episodeID, access, nil)
	if rec.Code != http.StatusOK || body["positionMs"].(float64) != 61000 {
		t.Fatalf("get progress status=%d body=%s", rec.Code, rec.Body.String())
	}

	rec, _ = env.do(t, http.MethodPut, "/api/v1/actions/"+episodeID, access, map[string]any{"starred": true})
	if rec.Code != http.StatusOK {
		t.Fatalf("star status=%d", rec.Code)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/actions?type=starred", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list actions status=%d", rec.Code)
	}
	if len(body["items"].([]any)) != 1 {
		t.Fatalf("expected 1 starred episode, got %v", body["items"])
	}

	rec, _ = env.do(t, http.MethodDelete, "/api/v1/subscriptions/"+podcastID, access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("unsubscribe status=%d", rec.Code)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/subscriptions", access, nil)
	if len(body["items"].([]any)) != 0 {
		t.Fatalf("expected empty subscriptions, got %v", body["items"])
	}
}

func TestPlaylistsAndSync(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "carol@example.com")

	_, body := env.do(t, http.MethodPost, "/api/v1/podcasts", access, map[string]any{"feedUrl": env.feedURL})
	podcastID := body["podcast"].(map[string]any)["id"].(string)
	_, body = env.do(t, http.MethodGet, "/api/v1/podcasts/"+podcastID+"/episodes", access, nil)
	episodeID := body["items"].([]any)[0].(map[string]any)["id"].(string)

	rec, body := env.do(t, http.MethodPost, "/api/v1/playlists", access, map[string]any{"title": "通勤"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create playlist status=%d body=%s", rec.Code, rec.Body.String())
	}
	list := body["items"].([]any)
	playlistID := list[0].(map[string]any)["id"].(string)

	rec, _ = env.do(t, http.MethodPut, "/api/v1/playlists/"+playlistID+"/items/"+episodeID, access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("add item status=%d", rec.Code)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/playlists/"+playlistID, access, nil)
	if rec.Code != http.StatusOK || len(body["episodes"].([]any)) != 1 {
		t.Fatalf("playlist detail status=%d body=%s", rec.Code, rec.Body.String())
	}

	rec, body = env.do(t, http.MethodPost, "/api/v1/sync", access, map[string]any{
		"items": []map[string]any{{"episodeId": episodeID, "positionMs": 12000, "durationMs": 3723000, "starred": true}},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("sync status=%d body=%s", rec.Code, rec.Body.String())
	}
	if len(body["progress"].([]any)) != 1 || len(body["actions"].([]any)) != 1 {
		t.Fatalf("unexpected sync payload: %s", rec.Body.String())
	}
}

func TestUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	rec, _ := env.do(t, http.MethodGet, "/api/v1/me", "not-a-token", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rec.Code)
	}
	rec, _ = env.do(t, http.MethodGet, "/api/v1/podcasts", "", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rec.Code)
	}
}
