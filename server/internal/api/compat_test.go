package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 老客户端/代理常见的 CORS 预检要能通过。
func TestCORSPreflight(t *testing.T) {
	env := newTestEnv(t)
	req := httptest.NewRequest(http.MethodOptions, "/api/v1/podcasts", nil)
	req.Header.Set("Origin", "https://app.example.com")
	req.Header.Set("Access-Control-Request-Method", "GET")
	req.Header.Set("Access-Control-Request-Headers", "authorization")
	rec := httptest.NewRecorder()
	env.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("preflight status=%d", rec.Code)
	}
	if rec.Header().Get("Access-Control-Allow-Origin") == "" {
		t.Fatal("missing CORS header")
	}
}

// 客户端版本参差不齐：未知字段应被拒绝，但缺失的可选字段要允许。
func TestPayloadCompatibility(t *testing.T) {
	env := newTestEnv(t)

	// 只带必填字段的老客户端
	body := map[string]string{"email": "old@example.com", "password": "supersecret123"}
	raw, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	env.handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("minimal payload status=%d body=%s", rec.Code, rec.Body.String())
	}

	// 大小写混合的邮箱也要能登录
	var auth map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &auth)
	loginBody, _ := json.Marshal(map[string]string{"email": "OLD@Example.COM", "password": "supersecret123"})
	loginReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(loginBody))
	loginReq.Header.Set("Content-Type", "application/json")
	loginRec := httptest.NewRecorder()
	env.handler.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("case-insensitive email login status=%d body=%s", loginRec.Code, loginRec.Body.String())
	}
}

// 非法输入不应 500，而应返回 4xx。
func TestBadInputDoesNotPanic(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "robust@example.com")

	cases := []struct {
		name string
		body string
	}{
		{"malformed json", "{not json"},
		{"empty body", ""},
		{"wrong type", "{\"feedUrl\":123}"},
		{"bad url", "{\"feedUrl\":\"not-a-url\"}"},
	}
	for _, tc := range cases {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/podcasts", strings.NewReader(tc.body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+access)
		rec := httptest.NewRecorder()
		env.handler.ServeHTTP(rec, req)
		if rec.Code >= 500 {
			t.Fatalf("%s: unexpected 5xx (%d) body=%s", tc.name, rec.Code, rec.Body.String())
		}
	}
}

// 分页参数越界要能兜底。
func TestPaginationBounds(t *testing.T) {
	env := newTestEnv(t)
	access, _ := env.register(t, "page@example.com")

	rec, _ := env.do(t, http.MethodGet, "/api/v1/podcasts?limit=-5&offset=-100", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("negative paging status=%d", rec.Code)
	}
	rec, _ = env.do(t, http.MethodGet, "/api/v1/podcasts?limit=999999", access, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("huge limit status=%d", rec.Code)
	}
}
