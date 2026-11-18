package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Station 为网络电台（直播音频流）。
type Station struct {
	ID         string `json:"id"`
	UserID     string `json:"-"`
	Name       string `json:"name"`
	StreamURL  string `json:"streamUrl"`
	Homepage   string `json:"homepage"`
	Favicon    string `json:"favicon"`
	Genre      string `json:"genre"`
	Country    string `json:"country"`
	Language   string `json:"language"`
	Codec      string `json:"codec"`
	Bitrate    int    `json:"bitrate"`
	IsHLS      bool   `json:"isHls"`
	Source     string `json:"source"`
	ExternalID string `json:"externalId,omitempty"`
	CreatedAt  string `json:"createdAt"`
	UpdatedAt  string `json:"updatedAt"`
	Favorite   bool   `json:"favorite"`
}

const stationColumns = `id, user_id, name, stream_url, homepage, favicon, genre, country,
language, codec, bitrate, is_hls, source, external_id, created_at, updated_at`

// UpsertStation 写入电台；公共电台 userID 传空串。
func (s *Store) UpsertStation(ctx context.Context, st *Station) (string, error) {
	now := Now()
	if st.CreatedAt == "" {
		st.CreatedAt = now
	}
	if st.UpdatedAt == "" {
		st.UpdatedAt = now
	}
	if st.Source == "" {
		st.Source = "manual"
	}
	var id string
	err := s.DB.QueryRowContext(ctx, `INSERT INTO stations (id, user_id, name, stream_url, homepage, favicon,
genre, country, language, codec, bitrate, is_hls, source, external_id, created_at, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(user_id, stream_url) DO UPDATE SET
  name = excluded.name, homepage = excluded.homepage, favicon = excluded.favicon,
  genre = excluded.genre, country = excluded.country, language = excluded.language,
  codec = excluded.codec, bitrate = excluded.bitrate, is_hls = excluded.is_hls,
  source = excluded.source, external_id = excluded.external_id,
  updated_at = excluded.updated_at, deleted = 0
RETURNING id`,
		st.ID, st.UserID, st.Name, st.StreamURL, st.Homepage, st.Favicon,
		st.Genre, st.Country, st.Language, st.Codec, st.Bitrate, boolInt(st.IsHLS),
		st.Source, st.ExternalID, st.CreatedAt, st.UpdatedAt).Scan(&id)
	if err != nil {
		return "", err
	}
	return id, nil
}

// StationByID 查询电台。
func (s *Store) StationByID(ctx context.Context, id string) (*Station, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT `+stationColumns+` FROM stations WHERE id = ? AND deleted = 0`, id)
	return scanStation(row)
}

// ListUserStations 返回用户自建电台 + 已收藏公共电台（去重）。
func (s *Store) ListUserStations(ctx context.Context, userID string) ([]Station, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+stationColumns+` FROM stations
WHERE deleted = 0 AND (user_id = ? OR (user_id = '' AND id IN (SELECT station_id FROM station_favorites WHERE user_id = ? AND deleted = 0)))
ORDER BY (user_id = ?) DESC, updated_at DESC`, userID, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	favorites, err := s.favoriteSet(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := []Station{}
	for rows.Next() {
		st, err := scanStation(rows)
		if err != nil {
			return nil, err
		}
		st.Favorite = favorites[st.ID]
		out = append(out, *st)
	}
	return out, rows.Err()
}

func (s *Store) favoriteSet(ctx context.Context, userID string) (map[string]bool, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT station_id FROM station_favorites WHERE user_id = ? AND deleted = 0`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	set := map[string]bool{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		set[id] = true
	}
	return set, rows.Err()
}

// SetStationFavorite 仅允许收藏 / 取消收藏用户自己的或明确公共的电台。
func (s *Store) SetStationFavorite(ctx context.Context, userID, stationID string, favorite bool) error {
	now := Now()
	deleted := 1
	if favorite {
		deleted = 0
	}
	res, err := s.DB.ExecContext(ctx, `INSERT INTO station_favorites (id, user_id, station_id, created_at, updated_at, deleted)
SELECT lower(hex(randomblob(16))), ?, id, ?, ?, ? FROM stations
WHERE id = ? AND deleted = 0 AND (user_id = ? OR user_id = '')
ON CONFLICT(user_id, station_id) DO UPDATE SET deleted = excluded.deleted, updated_at = excluded.updated_at`,
		userID, now, now, deleted, stationID, userID)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteStation 软删除用户自建电台。
func (s *Store) DeleteStation(ctx context.Context, userID, stationID string) error {
	res, err := s.DB.ExecContext(ctx,
		`UPDATE stations SET deleted = 1, updated_at = ? WHERE id = ? AND user_id = ?`,
		Now(), stationID, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// SyncStations 返回同步用的电台快照。
func (s *Store) SyncStations(ctx context.Context, userID string) ([]Station, error) {
	return s.ListUserStations(ctx, userID)
}

func scanStation(sc scanner) (*Station, error) {
	var st Station
	var isHLS int
	err := sc.Scan(&st.ID, &st.UserID, &st.Name, &st.StreamURL, &st.Homepage, &st.Favicon,
		&st.Genre, &st.Country, &st.Language, &st.Codec, &st.Bitrate, &isHLS,
		&st.Source, &st.ExternalID, &st.CreatedAt, &st.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	st.IsHLS = isHLS == 1
	return &st, nil
}

// GuessHLS 根据 URL 判断是否为 HLS 直播流。
func GuessHLS(streamURL string) bool {
	lower := strings.ToLower(streamURL)
	return strings.Contains(lower, ".m3u8") || strings.Contains(lower, "/hls/")
}
