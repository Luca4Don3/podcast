package config

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config 保存服务运行期配置，全部来自环境变量。
type Config struct {
	Addr            string
	Env             string
	DataDir         string
	DatabaseDSN     string
	PublicBaseURL   string
	JWTSecret       string
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	AllowedOrigins  []string
	InviteCode      string
	RSSUserAgent    string
	RSSMaxBytes     int64
	RSSTimeout      time.Duration
	ItunesSearchURL string
	RadioBrowserURL string
}

// Load 读取进程环境；缺失关键项时返回错误。
func Load() (*Config, error) {
	secureCookies := false
	_ = secureCookies

	c := &Config{
		Addr:            env("APP_ADDR", ":8080"),
		Env:             env("APP_ENV", "development"),
		DataDir:         env("APP_DATA_DIR", "./data"),
		DatabaseDSN:     os.Getenv("DATABASE_DSN"),
		PublicBaseURL:   strings.TrimRight(os.Getenv("PUBLIC_BASE_URL"), "/"),
		JWTSecret:       os.Getenv("JWT_SECRET"),
		AccessTokenTTL:  envDuration("ACCESS_TOKEN_TTL", 15*time.Minute),
		RefreshTokenTTL: envDuration("REFRESH_TOKEN_TTL", 30*24*time.Hour),
		AllowedOrigins:  splitList(env("CORS_ALLOWED_ORIGINS", "*")),
		InviteCode:      os.Getenv("SIGNUP_INVITE_CODE"),
		RSSUserAgent:    env("RSS_USER_AGENT", "PodcastApp/1.0"),
		RSSMaxBytes:     envInt64("RSS_MAX_BYTES", 20<<20),
		RSSTimeout:      envDuration("RSS_TIMEOUT", 20*time.Second),
		// 外部目录须由管理员显式启用；未设置或空白时禁用，不回退全球服务。
		ItunesSearchURL: strings.TrimSpace(os.Getenv("ITUNES_SEARCH_URL")),
		RadioBrowserURL: strings.TrimSpace(os.Getenv("RADIO_BROWSER_URL")),
	}

	if c.JWTSecret == "" {
		if c.Env == "production" {
			return nil, errors.New("config: JWT_SECRET is required in production")
		}
		c.JWTSecret = "dev-only-insecure-secret-change-me"
	}
	if len(c.JWTSecret) < 16 {
		return nil, fmt.Errorf("config: JWT_SECRET too short (%d bytes), need >= 16", len(c.JWTSecret))
	}
	if c.DatabaseDSN == "" {
		c.DatabaseDSN = "file:" + dataPath(c.DataDir, "podcast.db") + "?_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"
	}
	return c, nil
}

func dataPath(dir, name string) string {
	dir = strings.TrimRight(dir, "/")
	if dir == "" {
		return name
	}
	return dir + "/" + name
}

// LoadDotEnv 以“不存在才写入”的方式加载 .env，方便本地与容器共用同一份配置。
func LoadDotEnv(path string) error {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.Trim(strings.TrimSpace(value), "\"'")
		if key == "" {
			continue
		}
		if _, exists := os.LookupEnv(key); !exists {
			_ = os.Setenv(key, value)
		}
	}
	return scanner.Err()
}

func env(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && strings.TrimSpace(v) != "" {
		return v
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) time.Duration {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return fallback
	}
	return d
}

func envInt64(key string, fallback int64) int64 {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 {
		return fallback
	}
	return n
}

func splitList(v string) []string {
	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return []string{"*"}
	}
	return out
}
