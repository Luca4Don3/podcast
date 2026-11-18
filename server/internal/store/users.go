package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// User 为账号实体。
type User struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	PasswordHash string `json:"-"`
	DisplayName  string `json:"displayName"`
	Role         string `json:"role"`
	CreatedAt    string `json:"createdAt"`
	UpdatedAt    string `json:"updatedAt"`
}

// CreateUser 插入账号，邮箱重复时返回 ErrConflict。
func (s *Store) CreateUser(ctx context.Context, u *User) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		u.ID, normalizeEmail(u.Email), u.PasswordHash, u.DisplayName, u.Role, u.CreatedAt, u.UpdatedAt)
	if err != nil {
		if isUniqueViolation(err) {
			return ErrConflict
		}
		return err
	}
	return nil
}

// UserByEmail 按邮箱查询账号。
func (s *Store) UserByEmail(ctx context.Context, email string) (*User, error) {
	return scanUser(s.DB.QueryRowContext(ctx, `SELECT id, email, password_hash, display_name, role, created_at, updated_at
FROM users WHERE email = ?`, normalizeEmail(email)))
}

// UserByID 按 ID 查询账号。
func (s *Store) UserByID(ctx context.Context, id string) (*User, error) {
	return scanUser(s.DB.QueryRowContext(ctx, `SELECT id, email, password_hash, display_name, role, created_at, updated_at
FROM users WHERE id = ?`, id))
}

// UpdateUserPassword 更新密码哈希。
func (s *Store) UpdateUserPassword(ctx context.Context, id, hash string) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?`, hash, Now(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdateUserProfile 更新昵称。
func (s *Store) UpdateUserProfile(ctx context.Context, id, displayName string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?`, displayName, Now(), id)
	return err
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Email, &u.PasswordHash, &u.DisplayName, &u.Role, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// CountUsers 返回注册账号数量，用于首个用户提权。
func (s *Store) CountUsers(ctx context.Context) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM users`).Scan(&n)
	return n, err
}

func normalizeEmail(email string) string { return strings.ToLower(strings.TrimSpace(email)) }

func isUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "unique constraint") || strings.Contains(msg, "constraint failed: users.email")
}
