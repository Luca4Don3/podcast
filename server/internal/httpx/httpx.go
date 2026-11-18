package httpx

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/podcastapp/server/internal/store"
)

// APIError 统一错误响应体。
type APIError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// WriteJSON 输出 JSON 响应。
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("write json", "err", err)
	}
}

// Fail 输出错误响应。
func Fail(w http.ResponseWriter, status int, code, message string) {
	WriteJSON(w, status, APIError{Code: code, Message: message})
}

// FailStore 把存储层错误映射成 HTTP 响应。
func FailStore(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		Fail(w, http.StatusNotFound, "not_found", "资源不存在")
	case errors.Is(err, store.ErrConflict):
		Fail(w, http.StatusConflict, "conflict", "资源冲突")
	default:
		slog.Error("internal error", "err", err)
		Fail(w, http.StatusInternalServerError, "internal", "服务器内部错误")
	}
}

// Decode 解析请求体，限制 2MB。
func Decode(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 2<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// QueryInt 读取整数查询参数。
func QueryInt(r *http.Request, key string, def int) int {
	v := strings.TrimSpace(r.URL.Query().Get(key))
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

// QueryBool 读取布尔查询参数。
func QueryBool(r *http.Request, key string) (bool, bool) {
	v := strings.TrimSpace(r.URL.Query().Get(key))
	if v == "" {
		return false, false
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return false, false
	}
	return b, true
}
