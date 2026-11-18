package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStationCRUD(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "radio@example.com")

	// 手动添加
	rec, body := env.do(t, http.MethodPost, "/api/v1/stations", access, map[string]any{
		"name": "测试电台", "streamUrl": "https://example.com/live.mp3", "genre": "News", "bitrate": 128,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("add station status=%d body=%s", rec.Code, rec.Body.String())
	}
	station := body["station"].(map[string]any)
	stationID := station["id"].(string)
	if station["isHls"] != false {
		t.Fatalf("mp3 should not be HLS: %v", station["isHls"])
	}

	// HLS 自动识别
	rec, body = env.do(t, http.MethodPost, "/api/v1/stations", access, map[string]any{
		"name": "HLS 电台", "streamUrl": "https://example.com/live/playlist.m3u8",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("add hls status=%d", rec.Code)
	}
	if body["station"].(map[string]any)["isHls"] != true {
		t.Fatalf("m3u8 should be HLS")
	}

	// 列表
	rec, body = env.do(t, http.MethodGet, "/api/v1/stations", access, nil)
	if rec.Code != http.StatusOK || body["total"].(float64) != 2 {
		t.Fatalf("list status=%d body=%s", rec.Code, rec.Body.String())
	}

	// 收藏
	rec, body = env.do(t, http.MethodPut, "/api/v1/stations/"+stationID+"/favorite", access, map[string]any{"favorite": true})
	if rec.Code != http.StatusOK || body["favorite"] != true {
		t.Fatalf("favorite status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/stations", access, nil)
	found := false
	for _, item := range body["items"].([]any) {
		m := item.(map[string]any)
		if m["id"] == stationID && m["favorite"] == true {
			found = true
		}
	}
	if !found {
		t.Fatalf("favorite flag not persisted: %s", rec.Body.String())
	}

	// 删除
	rec, _ = env.do(t, http.MethodDelete, "/api/v1/stations/"+stationID, access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete status=%d", rec.Code)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/stations", access, nil)
	if body["total"].(float64) != 1 {
		t.Fatalf("expected 1 station after delete, got %v", body["total"])
	}
}

func TestStationValidation(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "radio2@example.com")

	// 非 http(s) 地址
	rec, _ := env.do(t, http.MethodPost, "/api/v1/stations", access, map[string]any{
		"streamUrl": "ftp://example.com/a.mp3",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("ftp stream status=%d", rec.Code)
	}

	// 空地址
	rec, _ = env.do(t, http.MethodPost, "/api/v1/stations", access, map[string]any{"name": "x"})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("empty stream status=%d", rec.Code)
	}

	// 未鉴权
	rec, _ = env.do(t, http.MethodGet, "/api/v1/stations", "", nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauth status=%d", rec.Code)
	}

	// 收藏不存在的电台
	rec, _ = env.do(t, http.MethodPut, "/api/v1/stations/nope/favorite", access, map[string]any{"favorite": true})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("favorite missing status=%d", rec.Code)
	}
}

func TestStationProbeUnreachable(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "radio3@example.com")

	// 本地 stub 返回不可用，避免依赖外网 DNS 或代理。
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()
	rec, body := env.do(t, http.MethodGet, "/api/v1/stations/probe?url="+srv.URL+"/x.mp3", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("probe should not 5xx, got %d", rec.Code)
	}
	if body["reachable"] != false {
		t.Fatalf("expected unreachable: %s", rec.Body.String())
	}

	// 非法地址
	rec, _ = env.do(t, http.MethodGet, "/api/v1/stations/probe?url=notaurl", access, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid probe url status=%d", rec.Code)
	}
}

func TestStationImport(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "radio4@example.com")

	rec, body := env.do(t, http.MethodPost, "/api/v1/stations/import", access, map[string]any{
		"stations": []map[string]any{
			{"name": "A", "streamUrl": "https://a.example.com/1.mp3"},
			{"name": "B", "streamUrl": "https://b.example.com/2.m3u8"},
			{"name": "坏地址", "streamUrl": "notaurl"},
		},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("import status=%d body=%s", rec.Code, rec.Body.String())
	}
	if body["imported"].(float64) != 2 {
		t.Fatalf("expected 2 imported, got %v", body["imported"])
	}

	// 超量导入
	tooMany := make([]map[string]any, 101)
	for i := range tooMany {
		tooMany[i] = map[string]any{"streamUrl": "https://x.example.com/" + string(rune('a'+i%26)) + ".mp3"}
	}
	rec, _ = env.do(t, http.MethodPost, "/api/v1/stations/import", access, map[string]any{"stations": tooMany})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("oversized import status=%d", rec.Code)
	}
}

// 目录不可用时应优雅降级而不是 500
func TestDiscoverStationsDegrades(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "radio5@example.com")
	unavailable := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer unavailable.Close()
	env.api.cfg.RadioBrowserURL = unavailable.URL

	rec, _ := env.do(t, http.MethodGet, "/api/v1/discover/stations?q=Jazz", access, nil)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 for dead directory, got %d", rec.Code)
	}

	// 显式配置的本地兼容目录应返回 200，无需外部服务。
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"stationuuid":"u1","name":"Jazz FM","url_resolved":"https://j.example.com/live.mp3","country":"US","codec":"MP3","bitrate":128,"hls":0,"tags":"jazz,smooth"}]`))
	}))
	defer srv.Close()
	env.api.cfg.RadioBrowserURL = srv.URL
	rec, body := env.do(t, http.MethodGet, "/api/v1/discover/stations?q=Jazz", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("directory status=%d body=%s", rec.Code, rec.Body.String())
	}
	items := body["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["name"] != "Jazz FM" {
		t.Fatalf("unexpected directory result: %s", rec.Body.String())
	}
}
