package store

import (
	"context"
	"database/sql"
	"errors"
)

// RefreshToken 为持久化的刷新令牌（只存哈希）。
type RefreshToken struct {
	ID        string
	UserID    string
	TokenHash string
	DeviceID  string
	UserAgent string
	ExpiresAt string
	CreatedAt string
	RevokedAt sql.NullString
}

// CreateRefreshToken 保存刷新令牌。
func (s *Store) CreateRefreshToken(ctx context.Context, t *RefreshToken) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, user_agent, expires_at, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		t.ID, t.UserID, t.TokenHash, t.DeviceID, t.UserAgent, t.ExpiresAt, t.CreatedAt)
	return err
}

// RefreshTokenByHash 按哈希查找刷新令牌。
func (s *Store) RefreshTokenByHash(ctx context.Context, hash string) (*RefreshToken, error) {
	var t RefreshToken
	err := s.DB.QueryRowContext(ctx, `SELECT id, user_id, token_hash, device_id, user_agent, expires_at, created_at, revoked_at
FROM refresh_tokens WHERE token_hash = ?`, hash).
		Scan(&t.ID, &t.UserID, &t.TokenHash, &t.DeviceID, &t.UserAgent, &t.ExpiresAt, &t.CreatedAt, &t.RevokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// RevokeRefreshToken 撤销单个刷新令牌。
func (s *Store) RevokeRefreshToken(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, Now(), id)
	return err
}

// RevokeUserTokens 撤销某用户全部（或某设备）刷新令牌。
func (s *Store) RevokeUserTokens(ctx context.Context, userID, deviceID string) error {
	if deviceID == "" {
		_, err := s.DB.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, Now(), userID)
		return err
	}
	_, err := s.DB.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL`, Now(), userID, deviceID)
	return err
}

// PurgeExpiredTokens 清理过期令牌，供定时任务调用。
func (s *Store) PurgeExpiredTokens(ctx context.Context) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE expires_at < ?`, Now())
	return err
}
