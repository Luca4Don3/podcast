package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Subscription 为用户订阅关系。
type Subscription struct {
	ID        string `json:"id"`
	UserID    string `json:"userId"`
	PodcastID string `json:"podcastId"`
	Notify    bool   `json:"notify"`
	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`
}

// Progress 为播放进度。
type Progress struct {
	ID         string `json:"id"`
	UserID     string `json:"userId"`
	EpisodeID  string `json:"episodeId"`
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	Completed  bool   `json:"completed"`
	PlayedAt   string `json:"playedAt"`
	UpdatedAt  string `json:"updatedAt"`
}

// Playlist 为用户自建播放列表。
type Playlist struct {
	ID          string `json:"id"`
	UserID      string `json:"userId"`
	Title       string `json:"title"`
	Description string `json:"description"`
	ItemCount   int    `json:"itemCount"`
	CreatedAt   string `json:"createdAt"`
	UpdatedAt   string `json:"updatedAt"`
}

// EpisodeAction 记录收藏、待播、已下载状态。
type EpisodeAction struct {
	EpisodeID  string `json:"episodeId"`
	Starred    bool   `json:"starred"`
	Queued     bool   `json:"queued"`
	Downloaded bool   `json:"downloaded"`
	UpdatedAt  string `json:"updatedAt"`
}

// Settings 为用户播放偏好。
type Settings struct {
	PlaybackSpeed  float64 `json:"playbackSpeed"`
	SkipForward    int     `json:"skipForward"`
	SkipBack       int     `json:"skipBack"`
	AutoDownload   bool    `json:"autoDownload"`
	DownloadPolicy string  `json:"downloadPolicy"`
	Theme          string  `json:"theme"`
	NotificationOn bool    `json:"notificationOn"`
}

// UpsertSubscription 订阅或取消订阅（软删除，便于跨设备同步）。
func (s *Store) UpsertSubscription(ctx context.Context, userID, podcastID string, subscribed, notify bool) error {
	now := Now()
	deleted := 0
	if !subscribed {
		deleted = 1
	}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO subscriptions (id, user_id, podcast_id, notify, created_at, updated_at, deleted)
VALUES (lower(hex(randomblob(16))), ?, ?, ?, ?, ?, ?)
ON CONFLICT(user_id, podcast_id) DO UPDATE SET notify = excluded.notify, updated_at = excluded.updated_at, deleted = excluded.deleted`,
		userID, podcastID, boolInt(notify), now, now, deleted)
	return err
}

// ListSubscriptions 返回用户订阅的播客（按订阅时间倒序）。
func (s *Store) ListSubscriptions(ctx context.Context, userID string) ([]PodcastWithMeta, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT p.id, p.feed_url, p.title, p.author, p.description, p.image_url, p.link,
       p.language, p.category, p.explicit, p.etag, p.last_modified, p.last_fetched_at, p.created_at, p.updated_at,
       sub.created_at, sub.notify
FROM subscriptions sub JOIN podcasts p ON p.id = sub.podcast_id
WHERE sub.user_id = ? AND sub.deleted = 0 AND p.deleted = 0
ORDER BY sub.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []PodcastWithMeta{}
	for rows.Next() {
		var p Podcast
		var explicit int
		var subCreated string
		var notify int
		if err := rows.Scan(&p.ID, &p.FeedURL, &p.Title, &p.Author, &p.Description, &p.ImageURL, &p.Link, &p.Language,
			&p.Category, &explicit, &p.ETag, &p.LastModified, &p.LastFetchedAt, &p.CreatedAt, &p.UpdatedAt,
			&subCreated, &notify); err != nil {
			return nil, err
		}
		p.Explicit = explicit == 1
		out = append(out, PodcastWithMeta{Podcast: p, Subscribed: true, SubscribedAt: subCreated})
	}
	return out, rows.Err()
}

// UpsertProgress 写入播放进度。
func (s *Store) UpsertProgress(ctx context.Context, p *Progress) error {
	now := Now()
	if p.PlayedAt == "" {
		p.PlayedAt = now
	}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO progress (id, user_id, episode_id, position_ms, duration_ms, completed, played_at, created_at, updated_at, deleted)
VALUES (lower(hex(randomblob(16))), ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(user_id, episode_id) DO UPDATE SET
  position_ms = excluded.position_ms,
  duration_ms = excluded.duration_ms,
  completed = excluded.completed,
  played_at = excluded.played_at,
  updated_at = excluded.updated_at,
  deleted = 0`,
		p.UserID, p.EpisodeID, p.PositionMs, p.DurationMs, boolInt(p.Completed), p.PlayedAt, now, now)
	return err
}

// ProgressByEpisode 查询单集进度。
func (s *Store) ProgressByEpisode(ctx context.Context, userID, episodeID string) (*Progress, error) {
	var p Progress
	var completed int
	err := s.DB.QueryRowContext(ctx, `SELECT id, user_id, episode_id, position_ms, duration_ms, completed, played_at, updated_at
FROM progress WHERE user_id = ? AND episode_id = ? AND deleted = 0`, userID, episodeID).
		Scan(&p.ID, &p.UserID, &p.EpisodeID, &p.PositionMs, &p.DurationMs, &completed, &p.PlayedAt, &p.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	p.Completed = completed == 1
	return &p, nil
}

// ListProgress 返回最近播放列表。
func (s *Store) ListProgress(ctx context.Context, userID string, limit, offset int) ([]Progress, int, error) {
	var total int
	if err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM progress WHERE user_id = ? AND deleted = 0`, userID).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT id, user_id, episode_id, position_ms, duration_ms, completed, played_at, updated_at
FROM progress WHERE user_id = ? AND deleted = 0 ORDER BY played_at DESC LIMIT ? OFFSET ?`, userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	out := []Progress{}
	for rows.Next() {
		var p Progress
		var completed int
		if err := rows.Scan(&p.ID, &p.UserID, &p.EpisodeID, &p.PositionMs, &p.DurationMs, &completed, &p.PlayedAt, &p.UpdatedAt); err != nil {
			return nil, 0, err
		}
		p.Completed = completed == 1
		out = append(out, p)
	}
	return out, total, rows.Err()
}

// UpsertEpisodeAction 写入收藏/待播/下载标记。
func (s *Store) UpsertEpisodeAction(ctx context.Context, userID string, a *EpisodeAction) error {
	now := Now()
	_, err := s.DB.ExecContext(ctx, `INSERT INTO episode_actions (id, user_id, episode_id, starred, queued, downloaded, created_at, updated_at, deleted)
VALUES (lower(hex(randomblob(16))), ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(user_id, episode_id) DO UPDATE SET
  starred = excluded.starred,
  queued = excluded.queued,
  downloaded = excluded.downloaded,
  updated_at = excluded.updated_at,
  deleted = 0`,
		userID, a.EpisodeID, boolInt(a.Starred), boolInt(a.Queued), boolInt(a.Downloaded), now, now)
	return err
}

// ListEpisodeActions 按标记类型返回单集 ID。
func (s *Store) ListEpisodeActions(ctx context.Context, userID string, filter string) ([]EpisodeAction, error) {
	where := "user_id = ? AND deleted = 0"
	switch filter {
	case "starred":
		where += " AND starred = 1"
	case "queued":
		where += " AND queued = 1"
	case "downloaded":
		where += " AND downloaded = 1"
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT episode_id, starred, queued, downloaded, updated_at FROM episode_actions WHERE `+where+` ORDER BY updated_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []EpisodeAction{}
	for rows.Next() {
		var a EpisodeAction
		var starred, queued, downloaded int
		if err := rows.Scan(&a.EpisodeID, &starred, &queued, &downloaded, &a.UpdatedAt); err != nil {
			return nil, err
		}
		a.Starred, a.Queued, a.Downloaded = starred == 1, queued == 1, downloaded == 1
		out = append(out, a)
	}
	return out, rows.Err()
}

// CreatePlaylist 新建播放列表。
func (s *Store) CreatePlaylist(ctx context.Context, p *Playlist) error {
	now := Now()
	_, err := s.DB.ExecContext(ctx, `INSERT INTO playlists (id, user_id, title, description, created_at, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, 0)`, p.ID, p.UserID, p.Title, p.Description, now, now)
	return err
}

// ListPlaylists 返回用户播放列表。
func (s *Store) ListPlaylists(ctx context.Context, userID string) ([]Playlist, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT pl.id, pl.user_id, pl.title, pl.description, pl.created_at, pl.updated_at,
  (SELECT COUNT(*) FROM playlist_items it WHERE it.playlist_id = pl.id AND it.deleted = 0)
FROM playlists pl WHERE pl.user_id = ? AND pl.deleted = 0 ORDER BY pl.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Playlist{}
	for rows.Next() {
		var p Playlist
		if err := rows.Scan(&p.ID, &p.UserID, &p.Title, &p.Description, &p.CreatedAt, &p.UpdatedAt, &p.ItemCount); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// AddPlaylistItem 向播放列表追加单集。
func (s *Store) AddPlaylistItem(ctx context.Context, userID, playlistID, episodeID string) error {
	var exists int
	if err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM playlists WHERE id = ? AND user_id = ? AND deleted = 0`,
		playlistID, userID).Scan(&exists); err != nil {
		return err
	}
	if exists == 0 {
		return ErrNotFound
	}
	var maxPos sql.NullInt64
	if err := s.DB.QueryRowContext(ctx, `SELECT MAX(position) FROM playlist_items WHERE playlist_id = ? AND deleted = 0`,
		playlistID).Scan(&maxPos); err != nil {
		return err
	}
	now := Now()
	_, err := s.DB.ExecContext(ctx, `INSERT INTO playlist_items (id, user_id, playlist_id, episode_id, position, created_at, updated_at, deleted)
VALUES (lower(hex(randomblob(16))), ?, ?, ?, ?, ?, ?, 0)`,
		userID, playlistID, episodeID, maxPos.Int64+1, now, now)
	return err
}

// RemovePlaylistItem 从播放列表移除单集。
func (s *Store) RemovePlaylistItem(ctx context.Context, userID, playlistID, episodeID string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE playlist_items SET deleted = 1, updated_at = ?
WHERE playlist_id = ? AND episode_id = ? AND user_id = ?`, Now(), playlistID, episodeID, userID)
	return err
}

// PlaylistEpisodes 返回播放列表内的单集，按 position 排序。
func (s *Store) PlaylistEpisodes(ctx context.Context, userID, playlistID string) ([]Episode, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT e.id, e.podcast_id, e.guid, e.title, e.description, e.audio_url, e.duration_seconds,
  e.published_at, e.image_url, e.episode_number, e.season_number, e.updated_at
FROM playlist_items it JOIN episodes e ON e.id = it.episode_id
WHERE it.playlist_id = ? AND it.user_id = ? AND it.deleted = 0 AND e.deleted = 0
ORDER BY it.position ASC`, playlistID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Episode{}
	for rows.Next() {
		e, err := scanEpisode(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *e)
	}
	return out, rows.Err()
}

// DeletePlaylist 软删除播放列表。
func (s *Store) DeletePlaylist(ctx context.Context, userID, playlistID string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE playlists SET deleted = 1, updated_at = ? WHERE id = ? AND user_id = ?`,
		Now(), playlistID, userID)
	return err
}

// Settings 读取用户偏好，不存在时返回默认值。
func (s *Store) Settings(ctx context.Context, userID string) (*Settings, error) {
	st := &Settings{PlaybackSpeed: 1.0, SkipForward: 30, SkipBack: 15, DownloadPolicy: "wifi", Theme: "system", NotificationOn: true}
	var autoDownload, notificationOn int
	err := s.DB.QueryRowContext(ctx, `SELECT playback_speed, skip_forward, skip_back, auto_download, download_policy, theme, notification_on
FROM user_settings WHERE user_id = ? AND deleted = 0`, userID).
		Scan(&st.PlaybackSpeed, &st.SkipForward, &st.SkipBack, &autoDownload, &st.DownloadPolicy, &st.Theme, &notificationOn)
	if errors.Is(err, sql.ErrNoRows) {
		return st, nil
	}
	if err != nil {
		return nil, err
	}
	st.AutoDownload = autoDownload == 1
	st.NotificationOn = notificationOn == 1
	return st, nil
}

// SaveSettings 写入用户偏好。
func (s *Store) SaveSettings(ctx context.Context, userID string, st *Settings) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO user_settings (user_id, playback_speed, skip_forward, skip_back, auto_download,
  download_policy, theme, notification_on, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(user_id) DO UPDATE SET
  playback_speed = excluded.playback_speed,
  skip_forward = excluded.skip_forward,
  skip_back = excluded.skip_back,
  auto_download = excluded.auto_download,
  download_policy = excluded.download_policy,
  theme = excluded.theme,
  notification_on = excluded.notification_on,
  updated_at = excluded.updated_at,
  deleted = 0`,
		userID, st.PlaybackSpeed, st.SkipForward, st.SkipBack, boolInt(st.AutoDownload),
		st.DownloadPolicy, st.Theme, boolInt(st.NotificationOn), Now())
	return err
}

// UpsertDevice 记录设备最近活跃时间。
func (s *Store) UpsertDevice(ctx context.Context, deviceID, userID, name, platform, userAgent string) error {
	now := Now()
	normalized := strings.TrimSpace(deviceID)
	if normalized == "" {
		return nil
	}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO devices (id, user_id, name, platform, user_agent, last_seen_at, created_at, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(id) DO UPDATE SET last_seen_at = excluded.last_seen_at, user_agent = excluded.user_agent, updated_at = excluded.updated_at, deleted = 0`,
		normalized, userID, name, platform, userAgent, now, now, now)
	return err
}
