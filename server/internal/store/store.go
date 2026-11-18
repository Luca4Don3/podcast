package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

var (
	// ErrNotFound 表示目标记录不存在。
	ErrNotFound = errors.New("store: not found")
	// ErrConflict 表示唯一约束冲突。
	ErrConflict = errors.New("store: conflict")
)

// Store 封装数据库连接与迁移。
type Store struct {
	DB *sql.DB
}

// Open 打开数据库并执行幂等迁移。
func Open(ctx context.Context, dsn string) (*Store, error) {
	db, err := sql.Open("sqlite", normalizeDSN(dsn))
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(time.Hour)

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("store: ping: %w", err)
	}

	s := &Store{DB: db}
	if err := s.migrate(ctx); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Close 释放连接。
func (s *Store) Close() error { return s.DB.Close() }

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.DB.ExecContext(ctx, schemaSQL); err != nil {
		return fmt.Errorf("store: migrate: %w", err)
	}
	// 老版本数据库缺列时自动补齐，避免升级即报错
	for _, stmt := range compatibilityMigrations {
		if _, err := s.DB.ExecContext(ctx, stmt); err != nil {
			// 列已存在等情况忽略，不让启动失败
			if !strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
				return fmt.Errorf("store: migrate compat: %w", err)
			}
		}
	}
	return nil
}

// compatibilityMigrations 为历史版本到当前版本的增量列。
var compatibilityMigrations = []string{
	"ALTER TABLE podcasts ADD COLUMN is_catalog INTEGER NOT NULL DEFAULT 1",
	"ALTER TABLE subscriptions ADD COLUMN notify INTEGER NOT NULL DEFAULT 1",
	"ALTER TABLE user_settings ADD COLUMN notification_on INTEGER NOT NULL DEFAULT 1",
	"ALTER TABLE episodes ADD COLUMN season_number INTEGER NOT NULL DEFAULT 0",
	"ALTER TABLE stations ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'",
	"ALTER TABLE stations ADD COLUMN external_id TEXT NOT NULL DEFAULT ''",
}

// normalizeDSN 兼容多种写法：
//   - 相对路径 / 绝对路径
//   - file:xxx.db
//   - 已带 _pragma 的 DSN 原样使用
func normalizeDSN(dsn string) string {
	dsn = strings.TrimSpace(dsn)
	if dsn == "" {
		return "file:podcast.db"
	}
	if strings.Contains(dsn, "_pragma=") {
		return dsn
	}
	base := dsn
	if !strings.HasPrefix(base, "file:") {
		base = "file:" + base
	}
	sep := "?"
	if strings.Contains(base, "?") {
		sep = "&"
	}
	return base + sep +
		"_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"
}

// Now 返回统一精度的 UTC 时间字符串，便于客户端按字符串比较。
func Now() string { return time.Now().UTC().Format(time.RFC3339Nano) }

// NormalizeTime 归一化客户端传入的时间戳，兼容多种历史格式。
func NormalizeTime(v string) string {
	if strings.TrimSpace(v) == "" {
		return Now()
	}
	layouts := []string{
		time.RFC3339Nano,
		time.RFC3339,
		"2006-01-02T15:04:05.000Z0700",
		"2006-01-02 15:04:05",
		"2006-01-02T15:04:05",
		"2006-01-02",
	}
	for _, layout := range layouts {
		if t, err := time.Parse(layout, v); err == nil {
			return t.UTC().Format(time.RFC3339Nano)
		}
	}
	return Now()
}
