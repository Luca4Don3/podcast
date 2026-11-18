package api

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"

	"github.com/podcastapp/server/internal/auth"
)

// errFeedFetch 表示外部订阅源抓取失败。
var errFeedFetch = errors.New("feed fetch failed")

func newRefreshToken() (string, string, error) { return auth.NewRefreshToken() }

func hashToken(raw string) string { return auth.HashToken(raw) }

func hashPassword(pw string) (string, error) { return auth.HashPassword(pw) }

func verifyPassword(pw, encoded string) (bool, bool, error) { return auth.VerifyPassword(pw, encoded) }

func randomHex(n int) string {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return ""
	}
	return hex.EncodeToString(buf)
}

func base64ID(n int) string {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return ""
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}
