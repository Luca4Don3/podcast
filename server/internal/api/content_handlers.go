package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/store"
)

type addPodcastRequest struct {
	FeedURL string `json:"feedUrl"`
}

type episodeListResponse struct {
	Items  []store.Episode `json:"items"`
	Total  int             `json:"total"`
	Limit  int             `json:"limit"`
	Offset int             `json:"offset"`
}

// handleListPodcasts 返回目录/订阅中的播客。
func (a *API) handleListPodcasts(w http.ResponseWriter, r *http.Request) {
	scope := r.URL.Query().Get("scope")
	limit := clamp(httpx.QueryInt(r, "limit", 50), 1, 200)
	offset := max(httpx.QueryInt(r, "offset", 0), 0)

	if scope == "subscribed" {
		subs, err := a.store.ListSubscriptions(r.Context(), httpx.UserIDFrom(r.Context()))
		if err != nil {
			httpx.FailStore(w, err)
			return
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": subs, "total": len(subs)})
		return
	}

	items, total, err := a.store.ListPodcasts(r.Context(), r.URL.Query().Get("q"), r.URL.Query().Get("category"), limit, offset)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items, "total": total, "limit": limit, "offset": offset})
}

// handleAddPodcast 抓取订阅源并入库，默认同时订阅。
func (a *API) handleAddPodcast(w http.ResponseWriter, r *http.Request) {
	var req addPodcastRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	feedURL := strings.TrimSpace(req.FeedURL)
	if !isHTTPURL(feedURL) {
		httpx.Fail(w, http.StatusBadRequest, "invalid_feed_url", "请提供 http(s) 订阅源地址")
		return
	}

	podcast, err := a.ingestFeed(r.Context(), feedURL, httpx.UserIDFrom(r.Context()))
	if err != nil {
		switch {
		case errors.Is(err, errFeedFetch):
			httpx.Fail(w, http.StatusBadGateway, "feed_unreachable", "订阅源抓取失败："+err.Error())
		default:
			httpx.Fail(w, http.StatusBadRequest, "invalid_feed", "订阅源解析失败")
		}
		return
	}
	if err := a.store.UpsertSubscription(r.Context(), httpx.UserIDFrom(r.Context()), podcast.ID, true, true); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"podcast": podcast})
}

// handleGetPodcast 返回播客详情（含订阅状态）。
func (a *API) handleGetPodcast(w http.ResponseWriter, r *http.Request) {
	podcast, err := a.store.PodcastByID(r.Context(), r.PathValue("id"))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	subs, _ := a.store.ListSubscriptions(r.Context(), httpx.UserIDFrom(r.Context()))
	subscribed := false
	for _, s := range subs {
		if s.ID == podcast.ID {
			subscribed = true
			break
		}
	}
	latest, _ := a.store.LatestEpisode(r.Context(), podcast.ID)
	_ = latest
	httpx.WriteJSON(w, http.StatusOK, store.PodcastWithMeta{Podcast: *podcast, Subscribed: subscribed})
}

// handleRefreshPodcast 手动刷新单集列表。
func (a *API) handleRefreshPodcast(w http.ResponseWriter, r *http.Request) {
	podcast, err := a.store.PodcastByID(r.Context(), r.PathValue("id"))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	updated, err := a.ingestFeed(r.Context(), podcast.FeedURL, "")
	if err != nil {
		httpx.Fail(w, http.StatusBadGateway, "feed_unreachable", "刷新失败")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"podcast": updated})
}

// handleListEpisodes 分页返回某播客的单集。
func (a *API) handleListEpisodes(w http.ResponseWriter, r *http.Request) {
	limit := clamp(httpx.QueryInt(r, "limit", 30), 1, 100)
	offset := max(httpx.QueryInt(r, "offset", 0), 0)
	items, total, err := a.store.ListEpisodes(r.Context(), r.PathValue("id"), limit, offset)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, episodeListResponse{Items: items, Total: total, Limit: limit, Offset: offset})
}

// handleGetEpisode 返回单集详情。
func (a *API) handleGetEpisode(w http.ResponseWriter, r *http.Request) {
	episode, err := a.store.EpisodeByID(r.Context(), r.PathValue("id"))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	podcast, err := a.store.PodcastByID(r.Context(), episode.PodcastID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"episode": episode, "podcast": podcast})
}

// ingestFeed 抓取并落库订阅源，返回播客记录。
func (a *API) ingestFeed(ctx context.Context, feedURL, userID string) (*store.Podcast, error) {
	existing, err := a.store.PodcastByFeedURL(ctx, feedURL)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	etag, lastMod := "", ""
	if existing != nil {
		etag, lastMod = existing.ETag, existing.LastModified
	}

	feed, notModified, err := a.fetcher.Fetch(feedURL, etag, lastMod)
	if err != nil {
		if existing != nil {
			// 抓取失败时回退到已有缓存，避免离线场景下不可用
			return existing, nil
		}
		return nil, errFeedFetch
	}
	if notModified && existing != nil {
		return existing, nil
	}

	now := store.Now()
	podcast := &store.Podcast{
		ID:            newID(),
		FeedURL:       feedURL,
		Title:         feed.Title,
		Author:        feed.Author,
		Description:   feed.Description,
		ImageURL:      feed.ImageURL,
		Link:          feed.Link,
		Language:      feed.Language,
		Category:      feed.Category,
		Explicit:      feed.Explicit,
		ETag:          feed.ETag,
		LastModified:  feed.LastMod,
		LastFetchedAt: now,
		CreatedAt:     now,
		UpdatedAt:     now,
	}
	if existing != nil {
		podcast.ID = existing.ID
		podcast.CreatedAt = existing.CreatedAt
	}
	if err := a.store.UpsertPodcast(ctx, podcast); err != nil {
		return nil, err
	}
	saved, err := a.store.PodcastByFeedURL(ctx, feedURL)
	if err != nil {
		return nil, err
	}

	for _, ep := range feed.Episodes {
		item := &store.Episode{
			ID:              newID(),
			PodcastID:       saved.ID,
			GUID:            ep.GUID,
			Title:           ep.Title,
			Description:     ep.Description,
			AudioURL:        ep.AudioURL,
			DurationSeconds: ep.DurationSec,
			PublishedAt:     ep.PublishedAt.UTC().Format(time.RFC3339Nano),
			ImageURL:        firstNonEmpty(ep.ImageURL, saved.ImageURL),
			EpisodeNumber:   ep.EpisodeNumber,
			SeasonNumber:    ep.SeasonNumber,
		}
		if _, err := a.store.UpsertEpisode(ctx, item); err != nil {
			return nil, err
		}
	}
	_ = userID
	return saved, nil
}

// handleDiscover 仅搜索管理员显式配置的兼容目录；默认关闭外部搜索。
func (a *API) handleDiscover(w http.ResponseWriter, r *http.Request) {
	if !a.itunes.Enabled() {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{
			"items": []DiscoverItem{}, "total": 0, "enabled": false,
			"message": "播客目录未启用，请手动导入有权使用的 RSS 地址",
		})
		return
	}
	term := strings.TrimSpace(r.URL.Query().Get("q"))
	if term == "" {
		httpx.Fail(w, http.StatusBadRequest, "missing_query", "请提供搜索关键词")
		return
	}
	items, err := a.itunes.Search(r.Context(), term, clamp(httpx.QueryInt(r, "limit", 25), 1, 50), r.URL.Query().Get("country"))
	if err != nil {
		httpx.Fail(w, http.StatusBadGateway, "discover_unavailable", "外部目录不可用，请直接添加 RSS 地址")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items, "total": len(items), "enabled": true})
}

func isHTTPURL(v string) bool {
	return strings.HasPrefix(v, "http://") || strings.HasPrefix(v, "https://")
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if s := strings.TrimSpace(v); s != "" {
			return s
		}
	}
	return ""
}
