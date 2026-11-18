package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/store"
)

type stationRequest struct {
	Name       string `json:"name"`
	StreamURL  string `json:"streamUrl"`
	Homepage   string `json:"homepage"`
	Favicon    string `json:"favicon"`
	Genre      string `json:"genre"`
	Country    string `json:"country"`
	Language   string `json:"language"`
	Codec      string `json:"codec"`
	Bitrate    int    `json:"bitrate"`
	ExternalID string `json:"externalId"`
}

// handleListStations 返回用户电台（自建 + 收藏）。
func (a *API) handleListStations(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListUserStations(r.Context(), httpx.UserIDFrom(r.Context()))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items, "total": len(items)})
}

// handleAddStation 添加自定义电台（任意 http/https 音频流）。
func (a *API) handleAddStation(w http.ResponseWriter, r *http.Request) {
	var req stationRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	streamURL := strings.TrimSpace(req.StreamURL)
	if !isHTTPURL(streamURL) {
		httpx.Fail(w, http.StatusBadRequest, "invalid_stream_url", "请提供 http(s) 音频流地址")
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = guessStationName(streamURL)
	}

	station := &store.Station{
		ID:         newID(),
		UserID:     httpx.UserIDFrom(r.Context()),
		Name:       name,
		StreamURL:  streamURL,
		Homepage:   strings.TrimSpace(req.Homepage),
		Favicon:    strings.TrimSpace(req.Favicon),
		Genre:      strings.TrimSpace(req.Genre),
		Country:    strings.TrimSpace(req.Country),
		Language:   strings.TrimSpace(req.Language),
		Codec:      strings.ToUpper(strings.TrimSpace(req.Codec)),
		Bitrate:    req.Bitrate,
		IsHLS:      store.GuessHLS(streamURL),
		Source:     "manual",
		ExternalID: strings.TrimSpace(req.ExternalID),
	}
	id, err := a.store.UpsertStation(r.Context(), station)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	saved, err := a.store.StationByID(r.Context(), id)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"station": saved})
}

// handleDeleteStation 删除自建电台。
func (a *API) handleDeleteStation(w http.ResponseWriter, r *http.Request) {
	if err := a.store.DeleteStation(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("id")); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

type favoriteRequest struct {
	Favorite bool `json:"favorite"`
}

// handleFavoriteStation 收藏 / 取消收藏。
func (a *API) handleFavoriteStation(w http.ResponseWriter, r *http.Request) {
	var req favoriteRequest
	favorite := r.Method != http.MethodDelete
	_ = httpx.Decode(r, &req)
	if r.Method == http.MethodPut {
		favorite = req.Favorite
	}
	userID := httpx.UserIDFrom(r.Context())
	stationID := r.PathValue("id")
	if err := a.store.SetStationFavorite(r.Context(), userID, stationID, favorite); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"favorite": favorite})
}

// handleStationProbe 探测音频流是否可用，返回内容类型、是否为 HLS、是否能只取头部。
func (a *API) handleStationProbe(w http.ResponseWriter, r *http.Request) {
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if !isHTTPURL(raw) {
		httpx.Fail(w, http.StatusBadRequest, "invalid_stream_url", "请提供 http(s) 音频流地址")
		return
	}
	info := a.probeStream(r, raw)
	httpx.WriteJSON(w, http.StatusOK, info)
}

type streamInfo struct {
	Reachable   bool   `json:"reachable"`
	StatusCode  int    `json:"statusCode"`
	ContentType string `json:"contentType"`
	IsHLS       bool   `json:"isHls"`
	FinalURL    string `json:"finalUrl"`
	Error       string `json:"error,omitempty"`
	LatencyMs   int64  `json:"latencyMs"`
}

func (a *API) probeStream(r *http.Request, raw string) streamInfo {
	start := time.Now()
	info := streamInfo{FinalURL: raw, IsHLS: store.GuessHLS(raw)}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, raw, nil)
	if err != nil {
		info.Error = "地址无效"
		return info
	}
	// 只取头部若干字节，避免把直播流整个读进来
	req.Header.Set("Range", "bytes=0-2047")
	req.Header.Set("User-Agent", a.cfg.RSSUserAgent)

	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		info.Error = "无法连接：" + err.Error()
		info.LatencyMs = time.Since(start).Milliseconds()
		return info
	}
	defer resp.Body.Close()

	info.StatusCode = resp.StatusCode
	info.ContentType = resp.Header.Get("Content-Type")
	info.FinalURL = resp.Request.URL.String()
	info.Reachable = resp.StatusCode < 400
	info.LatencyMs = time.Since(start).Milliseconds()

	ct := strings.ToLower(info.ContentType)
	if strings.Contains(ct, "mpegurl") || strings.Contains(strings.ToLower(info.FinalURL), ".m3u8") {
		info.IsHLS = true
	}
	return info
}

type radioBrowserItem struct {
	StationUUID string `json:"stationuuid"`
	Name        string `json:"name"`
	URLResolved string `json:"url_resolved"`
	Homepage    string `json:"homepage"`
	Favicon     string `json:"favicon"`
	Tags        string `json:"tags"`
	Country     string `json:"country"`
	CountryCode string `json:"countrycode"`
	Language    string `json:"language"`
	Codec       string `json:"codec"`
	Bitrate     int    `json:"bitrate"`
	HLS         int    `json:"hls"`
	Votes       int    `json:"votes"`
}

// handleDiscoverStations 仅搜索管理员显式配置的兼容目录；默认关闭外部搜索。
func (a *API) handleDiscoverStations(w http.ResponseWriter, r *http.Request) {
	endpoint := strings.TrimSpace(a.cfg.RadioBrowserURL)
	if endpoint == "" {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{
			"items": []any{}, "total": 0, "enabled": false,
			"message": "电台目录未启用，请手动导入有权使用的电台地址",
		})
		return
	}
	term := strings.TrimSpace(r.URL.Query().Get("q"))
	country := strings.TrimSpace(r.URL.Query().Get("country"))
	tag := strings.TrimSpace(r.URL.Query().Get("tag"))

	limit := clamp(httpx.QueryInt(r, "limit", 30), 1, 100)
	params := url.Values{}
	params.Set("limit", fmt.Sprintf("%d", limit))
	params.Set("hidebroken", "true")
	params.Set("order", "clickcount")
	params.Set("reverse", "true")
	if term != "" {
		params.Set("name", term)
	}
	if country != "" {
		params.Set("countrycode", strings.ToUpper(country))
	}
	if tag != "" {
		params.Set("tag", tag)
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, endpoint+"?"+params.Encode(), nil)
	if err != nil {
		httpx.Fail(w, http.StatusInternalServerError, "internal", "目录请求构造失败")
		return
	}
	req.Header.Set("User-Agent", a.cfg.RSSUserAgent+" (+radio-directory)")
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		httpx.Fail(w, http.StatusBadGateway, "directory_unavailable", "电台目录不可用，可手动添加流地址")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		httpx.Fail(w, http.StatusBadGateway, "directory_unavailable", "电台目录返回异常")
		return
	}

	var items []radioBrowserItem
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		httpx.Fail(w, http.StatusBadGateway, "directory_unavailable", "目录响应解析失败")
		return
	}

	out := make([]map[string]any, 0, len(items))
	for _, it := range items {
		streamURL := strings.TrimSpace(it.URLResolved)
		if streamURL == "" || !isHTTPURL(streamURL) {
			continue
		}
		out = append(out, map[string]any{
			"name":       strings.TrimSpace(it.Name),
			"streamUrl":  streamURL,
			"homepage":   it.Homepage,
			"favicon":    it.Favicon,
			"genre":      shortenTags(it.Tags),
			"country":    it.Country,
			"language":   it.Language,
			"codec":      strings.ToUpper(it.Codec),
			"bitrate":    it.Bitrate,
			"isHls":      it.HLS == 1 || store.GuessHLS(streamURL),
			"externalId": it.StationUUID,
			"votes":      it.Votes,
		})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": out, "total": len(out), "enabled": true})
}

// handleImportStations 批量导入用户提供的电台，仅归属当前用户。
func (a *API) handleImportStations(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Favorites []bool `json:"favorite"`
	}
	_ = req
	var payload struct {
		Stations []stationRequest `json:"stations"`
	}
	if err := httpx.Decode(r, &payload); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	if len(payload.Stations) == 0 || len(payload.Stations) > 100 {
		httpx.Fail(w, http.StatusBadRequest, "invalid_batch", "一次最多导入 100 个电台")
		return
	}
	userID := httpx.UserIDFrom(r.Context())
	imported := 0
	for _, req := range payload.Stations {
		streamURL := strings.TrimSpace(req.StreamURL)
		if !isHTTPURL(streamURL) {
			continue
		}
		name := strings.TrimSpace(req.Name)
		if name == "" {
			name = guessStationName(streamURL)
		}
		station := &store.Station{
			ID:         newID(),
			UserID:     userID,
			Name:       name,
			StreamURL:  streamURL,
			Homepage:   strings.TrimSpace(req.Homepage),
			Favicon:    strings.TrimSpace(req.Favicon),
			Genre:      strings.TrimSpace(req.Genre),
			Country:    strings.TrimSpace(req.Country),
			Language:   strings.TrimSpace(req.Language),
			Codec:      strings.ToUpper(strings.TrimSpace(req.Codec)),
			Bitrate:    req.Bitrate,
			IsHLS:      store.GuessHLS(streamURL),
			Source:     "manual",
			ExternalID: strings.TrimSpace(req.ExternalID),
		}
		if _, err := a.store.UpsertStation(r.Context(), station); err != nil {
			if errors.Is(err, store.ErrConflict) {
				continue
			}
			httpx.FailStore(w, err)
			return
		}
		imported++
	}
	items, _ := a.store.ListUserStations(r.Context(), userID)
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"imported": imported, "items": items})
}

func guessStationName(streamURL string) string {
	u, err := url.Parse(streamURL)
	if err != nil || u.Host == "" {
		return "网络电台"
	}
	return u.Host
}

func shortenTags(tags string) string {
	parts := strings.Split(tags, ",")
	if len(parts) > 3 {
		parts = parts[:3]
	}
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return strings.Join(parts, ", ")
}
