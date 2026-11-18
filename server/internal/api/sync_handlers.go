package api

import (
	"net/http"

	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/store"
)

// syncChanges 为客户端同步所需的云端快照。
type syncChanges struct {
	Subscriptions []store.PodcastWithMeta `json:"subscriptions"`
	Progress      []store.Progress        `json:"progress"`
	Actions       []store.EpisodeAction   `json:"actions"`
	Playlists     []store.Playlist        `json:"playlists"`
	Settings      *store.Settings         `json:"settings,omitempty"`
	ServerTime    string                  `json:"serverTime"`
}

type syncPushItem struct {
	EpisodeID  string `json:"episodeId"`
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	Completed  bool   `json:"completed"`
	PlayedAt   string `json:"playedAt"`
	Starred    *bool  `json:"starred"`
	Queued     *bool  `json:"queued"`
	Downloaded *bool  `json:"downloaded"`
}

type syncPushRequest struct {
	Items []syncPushItem `json:"items"`
}

// handleSync 单次请求完成「上传本地变更 + 拉取云端快照」，适合移动端弱网重试。
func (a *API) handleSync(w http.ResponseWriter, r *http.Request) {
	userID := httpx.UserIDFrom(r.Context())

	var push syncPushRequest
	if r.ContentLength > 0 {
		if err := httpx.Decode(r, &push); err != nil {
			httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
			return
		}
	}

	for _, item := range push.Items {
		if item.EpisodeID == "" {
			continue
		}
		if err := a.store.UpsertProgress(r.Context(), &store.Progress{
			UserID:     userID,
			EpisodeID:  item.EpisodeID,
			PositionMs: item.PositionMs,
			DurationMs: item.DurationMs,
			Completed:  item.Completed,
			PlayedAt:   store.NormalizeTime(item.PlayedAt),
		}); err != nil {
			httpx.FailStore(w, err)
			return
		}
		if item.Starred != nil || item.Queued != nil || item.Downloaded != nil {
			action := &store.EpisodeAction{EpisodeID: item.EpisodeID}
			existing, _ := a.store.ListEpisodeActions(r.Context(), userID, "all")
			for _, e := range existing {
				if e.EpisodeID == item.EpisodeID {
					action.Starred, action.Queued, action.Downloaded = e.Starred, e.Queued, e.Downloaded
					break
				}
			}
			if item.Starred != nil {
				action.Starred = *item.Starred
			}
			if item.Queued != nil {
				action.Queued = *item.Queued
			}
			if item.Downloaded != nil {
				action.Downloaded = *item.Downloaded
			}
			if err := a.store.UpsertEpisodeAction(r.Context(), userID, action); err != nil {
				httpx.FailStore(w, err)
				return
			}
		}
	}

	subs, err := a.store.ListSubscriptions(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	progress, _, err := a.store.ListProgress(r.Context(), userID, 500, 0)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	actions, err := a.store.ListEpisodeActions(r.Context(), userID, "all")
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	playlists, err := a.store.ListPlaylists(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	settings, err := a.store.Settings(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}

	httpx.WriteJSON(w, http.StatusOK, syncChanges{
		Subscriptions: subs,
		Progress:      progress,
		Actions:       actions,
		Playlists:     playlists,
		Settings:      settings,
		ServerTime:    store.Now(),
	})
}
