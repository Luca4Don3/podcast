package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
)

type directoryRoundTripper func(*http.Request) (*http.Response, error)

func (f directoryRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// These tests do not run in parallel: they replace DefaultTransport and fail
// closed so even a regression cannot contact the public network.
func guardDirectoryRequests(t *testing.T, allowedHost string) *atomic.Int32 {
	t.Helper()
	var calls atomic.Int32
	previous := http.DefaultTransport
	local := &http.Transport{Proxy: nil}
	http.DefaultTransport = directoryRoundTripper(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if allowedHost == "" || r.URL.Host != allowedHost {
			t.Errorf("unexpected outbound request: %s", r.URL)
			return nil, fmt.Errorf("test blocked outbound request")
		}
		return local.RoundTrip(r)
	})
	t.Cleanup(func() {
		http.DefaultTransport = previous
		local.CloseIdleConnections()
	})
	return &calls
}

func TestDirectoriesDisabledWithoutNetwork(t *testing.T) {
	for _, tc := range []struct{ name, endpoint string }{
		{"default", ""}, {"whitespace", " \t\r\n "},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := guardDirectoryRequests(t, "")
			env := newTestEnv(t)
			env.api.cfg.RadioBrowserURL = tc.endpoint
			env.api.itunes = newItunesClient(tc.endpoint)
			access, _ := env.register(t, "disabled@example.com")
			for _, path := range []string{
				"/api/v1/discover/search", "/api/v1/discover/search?q=test",
				"/api/v1/discover/stations", "/api/v1/discover/stations?q=test",
			} {
				rec, body := env.do(t, http.MethodGet, path, access, nil)
				items, ok := body["items"].([]any)
				if rec.Code != http.StatusOK || !ok || len(items) != 0 || body["total"] != float64(0) || body["enabled"] != false || body["message"] == "" || body["message"] == nil {
					t.Fatalf("disabled %s: status=%d body=%s", path, rec.Code, rec.Body.String())
				}
				for _, token := range []string{"", "not-a-token"} {
					rec, _ = env.do(t, http.MethodGet, path, token, nil)
					if rec.Code != http.StatusUnauthorized {
						t.Fatalf("disabled directory bypassed auth: %s status=%d", path, rec.Code)
					}
				}
			}
			// Exercise the actual client too, not just the handler's early return.
			items, err := env.api.itunes.Search(context.Background(), "test", 25, "CN")
			if err != nil || items == nil || len(items) != 0 || env.api.itunes.Enabled() {
				t.Fatalf("disabled client: items=%v error=%v", items, err)
			}
			if calls.Load() != 0 {
				t.Fatalf("disabled directories made %d requests", calls.Load())
			}
		})
	}
}

func TestConfiguredDirectoriesUseLocalStub(t *testing.T) {
	var podcastCalls, stationCalls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/podcasts":
			podcastCalls.Add(1)
			if r.URL.Query().Get("term") != "test" || r.URL.Query().Get("country") != "CN" || r.URL.Query().Get("limit") != "2" {
				t.Errorf("unexpected podcast query: %s", r.URL.RawQuery)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"results": []map[string]any{
				{"collectionName": "Local podcast", "feedUrl": "https://example.invalid/feed.xml"},
			}})
		case "/stations":
			stationCalls.Add(1)
			if r.URL.Query().Get("name") != "test" || r.URL.Query().Get("countrycode") != "CN" || r.URL.Query().Get("limit") != "2" {
				t.Errorf("unexpected station query: %s", r.URL.RawQuery)
			}
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{"stationuuid": "local", "name": "Local radio", "url_resolved": "https://example.invalid/live.mp3"},
			})
		default:
			t.Errorf("unexpected path: %s", r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	parsed, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	calls := guardDirectoryRequests(t, parsed.Host)
	env := newTestEnv(t)
	env.api.cfg.RadioBrowserURL = " \t" + srv.URL + "/stations \n"
	env.api.itunes = newItunesClient(" \t" + srv.URL + "/podcasts \n")
	access, _ := env.register(t, "configured@example.com")
	for _, path := range []string{"/api/v1/discover/search", "/api/v1/discover/stations"} {
		rec, _ := env.do(t, http.MethodGet, path+"?q=test&country=CN&limit=2", "", nil)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("configured directory bypassed auth: %d", rec.Code)
		}
		rec, body := env.do(t, http.MethodGet, path+"?q=test&country=CN&limit=2", access, nil)
		items, ok := body["items"].([]any)
		if rec.Code != http.StatusOK || !ok || len(items) != 1 || body["total"] != float64(1) || body["enabled"] != true {
			t.Fatalf("configured directory: status=%d body=%s", rec.Code, rec.Body.String())
		}
	}
	if calls.Load() != 2 || podcastCalls.Load() != 1 || stationCalls.Load() != 1 {
		t.Fatalf("wrong request counts: total=%d podcast=%d station=%d", calls.Load(), podcastCalls.Load(), stationCalls.Load())
	}
}

func TestManualImportsWorkWithDirectoriesDisabled(t *testing.T) {
	env := newTestEnv(t)
	parsed, err := url.Parse(env.feedURL)
	if err != nil {
		t.Fatal(err)
	}
	calls := guardDirectoryRequests(t, parsed.Host)
	access, _ := env.register(t, "imports@example.com")
	rec, body := env.do(t, http.MethodPost, "/api/v1/podcasts", access, map[string]any{"feedUrl": env.feedURL})
	if rec.Code != http.StatusOK {
		t.Fatalf("manual RSS import: status=%d body=%s", rec.Code, rec.Body.String())
	}
	podcastID := body["podcast"].(map[string]any)["id"].(string)
	rec, body = env.do(t, http.MethodGet, "/api/v1/podcasts/"+podcastID+"/episodes", access, nil)
	if rec.Code != http.StatusOK || body["total"] != float64(2) {
		t.Fatalf("imported RSS episodes: status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/subscriptions", access, nil)
	if rec.Code != http.StatusOK || len(body["items"].([]any)) != 1 {
		t.Fatalf("manual RSS subscription: status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodPost, "/api/v1/stations", access, map[string]any{
		"name": "Manual", "streamUrl": "https://example.invalid/manual.mp3",
	})
	if rec.Code != http.StatusOK || body["station"].(map[string]any)["source"] != "manual" {
		t.Fatalf("manual station: status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodPost, "/api/v1/stations/import", access, map[string]any{
		"stations": []map[string]any{{"name": "Bulk", "streamUrl": "https://example.invalid/bulk.m3u8"}},
	})
	if rec.Code != http.StatusOK || body["imported"] != float64(1) || len(body["items"].([]any)) != 2 {
		t.Fatalf("bulk stations: status=%d body=%s", rec.Code, rec.Body.String())
	}
	for _, item := range body["items"].([]any) {
		if item.(map[string]any)["source"] != "manual" {
			t.Fatalf("import must remain manual: %v", item)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("only the manually imported RSS may be fetched, got %d requests", calls.Load())
	}
}
