package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Podcast 为播客节目。
type Podcast struct {
	ID            string `json:"id"`
	FeedURL       string `json:"feedUrl"`
	Title         string `json:"title"`
	Author        string `json:"author"`
	Description   string `json:"description"`
	ImageURL      string `json:"imageUrl"`
	Link          string `json:"link"`
	Language      string `json:"language"`
	Category      string `json:"category"`
	Explicit      bool   `json:"explicit"`
	ETag          string `json:"-"`
	LastModified  string `json:"-"`
	LastFetchedAt string `json:"lastFetchedAt"`
	CreatedAt     string `json:"createdAt"`
	UpdatedAt     string `json:"updatedAt"`
	EpisodeCount  int    `json:"episodeCount,omitempty"`
}

// Episode 为播客单集。
type Episode struct {
	ID              string `json:"id"`
	PodcastID       string `json:"podcastId"`
	GUID            string `json:"guid"`
	Title           string `json:"title"`
	Description     string `json:"description"`
	AudioURL        string `json:"audioUrl"`
	DurationSeconds int    `json:"durationSeconds"`
	PublishedAt     string `json:"publishedAt"`
	ImageURL        string `json:"imageUrl"`
	EpisodeNumber   int    `json:"episodeNumber"`
	SeasonNumber    int    `json:"seasonNumber"`
	UpdatedAt       string `json:"-"`
}

// PodcastWithMeta 为带订阅信息的播客。
type PodcastWithMeta struct {
	Podcast
	Subscribed   bool   `json:"subscribed"`
	SubscribedAt string `json:"subscribedAt,omitempty"`
}

const podcastColumns = `id, feed_url, title, author, description, image_url, link, language, category, explicit,
etag, last_modified, last_fetched_at, created_at, updated_at`

// UpsertPodcast 按 feed_url 写入或更新播客，返回记录 ID。
func (s *Store) UpsertPodcast(ctx context.Context, p *Podcast) error {
	now := Now()
	if p.CreatedAt == "" {
		p.CreatedAt = now
	}
	if p.UpdatedAt == "" {
		p.UpdatedAt = now
	}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO podcasts (id, feed_url, title, author, description, image_url, link, language, category, explicit,
etag, last_modified, last_fetched_at, created_at, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(feed_url) DO UPDATE SET
  title = excluded.title,
  author = excluded.author,
  description = excluded.description,
  image_url = excluded.image_url,
  link = excluded.link,
  language = excluded.language,
  category = excluded.category,
  explicit = excluded.explicit,
  etag = excluded.etag,
  last_modified = excluded.last_modified,
  last_fetched_at = excluded.last_fetched_at,
  updated_at = excluded.updated_at,
  deleted = 0`,
		p.ID, p.FeedURL, p.Title, p.Author, p.Description, p.ImageURL, p.Link, p.Language, p.Category, boolInt(p.Explicit),
		p.ETag, p.LastModified, p.LastFetchedAt, p.CreatedAt, p.UpdatedAt)
	return err
}

// PodcastByID 查询播客。
func (s *Store) PodcastByID(ctx context.Context, id string) (*Podcast, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT `+podcastColumns+` FROM podcasts WHERE id = ? AND deleted = 0`, id)
	return scanPodcast(row)
}

// PodcastByFeedURL 按订阅源地址查询。
func (s *Store) PodcastByFeedURL(ctx context.Context, feedURL string) (*Podcast, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT `+podcastColumns+` FROM podcasts WHERE feed_url = ? AND deleted = 0`, feedURL)
	return scanPodcast(row)
}

// ListPodcasts 按关键词/分类分页查询播客。
func (s *Store) ListPodcasts(ctx context.Context, query, category string, limit, offset int) ([]Podcast, int, error) {
	where := []string{"deleted = 0"}
	args := []any{}
	if q := strings.TrimSpace(query); q != "" {
		where = append(where, "(title LIKE ? OR author LIKE ? OR description LIKE ?)")
		like := "%" + q + "%"
		args = append(args, like, like, like)
	}
	if c := strings.TrimSpace(category); c != "" && c != "all" {
		where = append(where, "category = ?")
		args = append(args, c)
	}
	whereSQL := " WHERE " + strings.Join(where, " AND ")

	var total int
	if err := s.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM podcasts"+whereSQL, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, "SELECT "+podcastColumns+" FROM podcasts"+whereSQL+" ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?",
		append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var out []Podcast
	for rows.Next() {
		p, err := scanPodcast(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *p)
	}
	return out, total, rows.Err()
}

// UpsertEpisode 按 (podcast_id, guid) 写入单集，返回数据库里的 ID。
func (s *Store) UpsertEpisode(ctx context.Context, e *Episode) (string, error) {
	now := Now()
	var id string
	err := s.DB.QueryRowContext(ctx, `INSERT INTO episodes (id, podcast_id, guid, title, description, audio_url, duration_seconds,
published_at, image_url, episode_number, season_number, created_at, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(podcast_id, guid) DO UPDATE SET
  title = excluded.title,
  description = excluded.description,
  audio_url = excluded.audio_url,
  duration_seconds = excluded.duration_seconds,
  published_at = excluded.published_at,
  image_url = excluded.image_url,
  episode_number = excluded.episode_number,
  season_number = excluded.season_number,
  updated_at = excluded.updated_at,
  deleted = 0
RETURNING id`,
		e.ID, e.PodcastID, e.GUID, e.Title, e.Description, e.AudioURL, e.DurationSeconds,
		e.PublishedAt, e.ImageURL, e.EpisodeNumber, e.SeasonNumber, now, now).Scan(&id)
	if err != nil {
		return "", err
	}
	return id, nil
}

const episodeColumns = `id, podcast_id, guid, title, description, audio_url, duration_seconds,
published_at, image_url, episode_number, season_number, updated_at`

// EpisodeByID 查询单集。
func (s *Store) EpisodeByID(ctx context.Context, id string) (*Episode, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT `+episodeColumns+` FROM episodes WHERE id = ? AND deleted = 0`, id)
	return scanEpisode(row)
}

// EpisodesByIDs 批量查询单集（保持传入顺序）。
func (s *Store) EpisodesByIDs(ctx context.Context, ids []string) ([]Episode, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT `+episodeColumns+` FROM episodes WHERE id IN (`+placeholders+`) AND deleted = 0`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	byID := map[string]Episode{}
	for rows.Next() {
		e, err := scanEpisode(rows)
		if err != nil {
			return nil, err
		}
		byID[e.ID] = *e
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Episode, 0, len(ids))
	for _, id := range ids {
		if e, ok := byID[id]; ok {
			out = append(out, e)
		}
	}
	return out, nil
}

// ListEpisodes 按播客分页查询单集。
func (s *Store) ListEpisodes(ctx context.Context, podcastID string, limit, offset int) ([]Episode, int, error) {
	var total int
	if err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM episodes WHERE podcast_id = ? AND deleted = 0`, podcastID).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT `+episodeColumns+` FROM episodes WHERE podcast_id = ? AND deleted = 0
ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?`, podcastID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var out []Episode
	for rows.Next() {
		e, err := scanEpisode(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *e)
	}
	return out, total, rows.Err()
}

// LatestEpisode 返回播客最新单集的发布时间。
func (s *Store) LatestEpisode(ctx context.Context, podcastID string) (Episode, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT `+episodeColumns+` FROM episodes WHERE podcast_id = ? AND deleted = 0
ORDER BY published_at DESC LIMIT 1`, podcastID)
	e, err := scanEpisode(row)
	if err != nil {
		return Episode{}, err
	}
	return *e, nil
}

func scanPodcast(sc scanner) (*Podcast, error) {
	var p Podcast
	var explicit int
	err := sc.Scan(&p.ID, &p.FeedURL, &p.Title, &p.Author, &p.Description, &p.ImageURL, &p.Link, &p.Language,
		&p.Category, &explicit, &p.ETag, &p.LastModified, &p.LastFetchedAt, &p.CreatedAt, &p.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	p.Explicit = explicit == 1
	return &p, nil
}

func scanEpisode(sc scanner) (*Episode, error) {
	var e Episode
	err := sc.Scan(&e.ID, &e.PodcastID, &e.GUID, &e.Title, &e.Description, &e.AudioURL, &e.DurationSeconds,
		&e.PublishedAt, &e.ImageURL, &e.EpisodeNumber, &e.SeasonNumber, &e.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &e, nil
}

type scanner interface {
	Scan(dest ...any) error
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
