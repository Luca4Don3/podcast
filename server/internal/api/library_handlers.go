package api

import (
	"net/http"
	"strings"

	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/store"
)

type subscribeRequest struct {
	Subscribed bool `json:"subscribed"`
	Notify     bool `json:"notify"`
}

// handleListSubscriptions 返回订阅列表。
func (a *API) handleListSubscriptions(w http.ResponseWriter, r *http.Request) {
	subs, err := a.store.ListSubscriptions(r.Context(), httpx.UserIDFrom(r.Context()))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": subs, "total": len(subs)})
}

// handleSubscribe 订阅播客（幂等）。
func (a *API) handleSubscribe(w http.ResponseWriter, r *http.Request) {
	var req subscribeRequest
	_ = httpx.Decode(r, &req)
	notify := true
	if !req.Notify {
		notify = r.ContentLength <= 0
	}
	podcastID := r.PathValue("podcastId")
	if _, err := a.store.PodcastByID(r.Context(), podcastID); err != nil {
		httpx.FailStore(w, err)
		return
	}
	if err := a.store.UpsertSubscription(r.Context(), httpx.UserIDFrom(r.Context()), podcastID, true, notify); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"subscribed": true})
}

// handleUnsubscribe 取消订阅。
func (a *API) handleUnsubscribe(w http.ResponseWriter, r *http.Request) {
	if err := a.store.UpsertSubscription(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("podcastId"), false, false); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"subscribed": false})
}

type progressRequest struct {
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	Completed  bool   `json:"completed"`
	PlayedAt   string `json:"playedAt"`
}

// handleSaveProgress 写入播放进度。
func (a *API) handleSaveProgress(w http.ResponseWriter, r *http.Request) {
	var req progressRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	if req.PositionMs < 0 || req.DurationMs < 0 {
		httpx.Fail(w, http.StatusBadRequest, "invalid_progress", "进度不能为负数")
		return
	}
	p := &store.Progress{
		UserID:     httpx.UserIDFrom(r.Context()),
		EpisodeID:  r.PathValue("episodeId"),
		PositionMs: req.PositionMs,
		DurationMs: req.DurationMs,
		Completed:  req.Completed,
		PlayedAt:   store.NormalizeTime(req.PlayedAt),
	}
	if err := a.store.UpsertProgress(r.Context(), p); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

// handleGetProgress 读取单集进度。
func (a *API) handleGetProgress(w http.ResponseWriter, r *http.Request) {
	p, err := a.store.ProgressByEpisode(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("episodeId"))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, p)
}

// handleListProgress 返回最近播放。
func (a *API) handleListProgress(w http.ResponseWriter, r *http.Request) {
	limit := clamp(httpx.QueryInt(r, "limit", 30), 1, 100)
	offset := max(httpx.QueryInt(r, "offset", 0), 0)
	items, total, err := a.store.ListProgress(r.Context(), httpx.UserIDFrom(r.Context()), limit, offset)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	ids := make([]string, 0, len(items))
	for _, p := range items {
		ids = append(ids, p.EpisodeID)
	}
	episodes, err := a.store.EpisodesByIDs(r.Context(), ids)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"items":    items,
		"episodes": episodes,
		"total":    total,
		"limit":    limit,
		"offset":   offset,
	})
}

type actionRequest struct {
	Starred    *bool `json:"starred"`
	Queued     *bool `json:"queued"`
	Downloaded *bool `json:"downloaded"`
}

// handleSaveAction 写入收藏/待播/下载状态。
func (a *API) handleSaveAction(w http.ResponseWriter, r *http.Request) {
	var req actionRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	userID := httpx.UserIDFrom(r.Context())
	episodeID := r.PathValue("episodeId")
	action := &store.EpisodeAction{EpisodeID: episodeID}
	existing, _ := a.store.ListEpisodeActions(r.Context(), userID, "all")
	for _, e := range existing {
		if e.EpisodeID == episodeID {
			action.Starred, action.Queued, action.Downloaded = e.Starred, e.Queued, e.Downloaded
			break
		}
	}
	if req.Starred != nil {
		action.Starred = *req.Starred
	}
	if req.Queued != nil {
		action.Queued = *req.Queued
	}
	if req.Downloaded != nil {
		action.Downloaded = *req.Downloaded
	}
	if err := a.store.UpsertEpisodeAction(r.Context(), userID, action); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, action)
}

// handleListActions 返回收藏/待播/下载列表（含单集详情）。
func (a *API) handleListActions(w http.ResponseWriter, r *http.Request) {
	filter := r.URL.Query().Get("type")
	actions, err := a.store.ListEpisodeActions(r.Context(), httpx.UserIDFrom(r.Context()), filter)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	ids := make([]string, 0, len(actions))
	for _, a := range actions {
		ids = append(ids, a.EpisodeID)
	}
	episodes, err := a.store.EpisodesByIDs(r.Context(), ids)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": actions, "episodes": episodes})
}

type createPlaylistRequest struct {
	Title       string `json:"title"`
	Description string `json:"description"`
}

// handleCreatePlaylist 新建播放列表。
func (a *API) handleCreatePlaylist(w http.ResponseWriter, r *http.Request) {
	var req createPlaylistRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	title := strings.TrimSpace(req.Title)
	if title == "" {
		httpx.Fail(w, http.StatusBadRequest, "missing_title", "请填写名称")
		return
	}
	p := &store.Playlist{ID: newID(), UserID: httpx.UserIDFrom(r.Context()), Title: title, Description: req.Description}
	if err := a.store.CreatePlaylist(r.Context(), p); err != nil {
		httpx.FailStore(w, err)
		return
	}
	items, _ := a.store.ListPlaylists(r.Context(), p.UserID)
	httpx.WriteJSON(w, http.StatusCreated, map[string]any{"items": items})
}

// handleListPlaylists 返回播放列表。
func (a *API) handleListPlaylists(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListPlaylists(r.Context(), httpx.UserIDFrom(r.Context()))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items})
}

// handleGetPlaylist 返回播放列表及其单集。
func (a *API) handleGetPlaylist(w http.ResponseWriter, r *http.Request) {
	userID := httpx.UserIDFrom(r.Context())
	playlists, err := a.store.ListPlaylists(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	id := r.PathValue("id")
	var found *store.Playlist
	for i := range playlists {
		if playlists[i].ID == id {
			found = &playlists[i]
			break
		}
	}
	if found == nil {
		httpx.Fail(w, http.StatusNotFound, "not_found", "播放列表不存在")
		return
	}
	episodes, err := a.store.PlaylistEpisodes(r.Context(), userID, id)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"playlist": found, "episodes": episodes})
}

// handleDeletePlaylist 删除播放列表。
func (a *API) handleDeletePlaylist(w http.ResponseWriter, r *http.Request) {
	if err := a.store.DeletePlaylist(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("id")); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleAddPlaylistItem 向播放列表加入单集。
func (a *API) handleAddPlaylistItem(w http.ResponseWriter, r *http.Request) {
	if err := a.store.AddPlaylistItem(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("id"), r.PathValue("episodeId")); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleRemovePlaylistItem 从播放列表移除单集。
func (a *API) handleRemovePlaylistItem(w http.ResponseWriter, r *http.Request) {
	if err := a.store.RemovePlaylistItem(r.Context(), httpx.UserIDFrom(r.Context()), r.PathValue("id"), r.PathValue("episodeId")); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

type settingsRequest struct {
	PlaybackSpeed  *float64 `json:"playbackSpeed"`
	SkipForward    *int     `json:"skipForward"`
	SkipBack       *int     `json:"skipBack"`
	AutoDownload   *bool    `json:"autoDownload"`
	DownloadPolicy *string  `json:"downloadPolicy"`
	Theme          *string  `json:"theme"`
	NotificationOn *bool    `json:"notificationOn"`
}

// handleGetSettings 读取用户偏好。
func (a *API) handleGetSettings(w http.ResponseWriter, r *http.Request) {
	st, err := a.store.Settings(r.Context(), httpx.UserIDFrom(r.Context()))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, st)
}

// handleSaveSettings 保存用户偏好。
func (a *API) handleSaveSettings(w http.ResponseWriter, r *http.Request) {
	var req settingsRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	userID := httpx.UserIDFrom(r.Context())
	st, err := a.store.Settings(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	if req.PlaybackSpeed != nil {
		st.PlaybackSpeed = *req.PlaybackSpeed
	}
	if req.SkipForward != nil {
		st.SkipForward = *req.SkipForward
	}
	if req.SkipBack != nil {
		st.SkipBack = *req.SkipBack
	}
	if req.AutoDownload != nil {
		st.AutoDownload = *req.AutoDownload
	}
	if req.DownloadPolicy != nil {
		st.DownloadPolicy = *req.DownloadPolicy
	}
	if req.Theme != nil {
		st.Theme = *req.Theme
	}
	if req.NotificationOn != nil {
		st.NotificationOn = *req.NotificationOn
	}
	if err := a.store.SaveSettings(r.Context(), userID, st); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, st)
}
