package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Claims 为访问令牌载荷。
type Claims struct {
	UserID string `json:"uid"`
	Email  string `json:"email"`
	Role   string `json:"role"`
	jwt.RegisteredClaims
}

// TokenIssuer 负责签发与校验访问令牌。
type TokenIssuer struct {
	secret     []byte
	accessTTL  time.Duration
	refreshTTL time.Duration
}

// NewTokenIssuer 构造签发器。
func NewTokenIssuer(secret string, accessTTL, refreshTTL time.Duration) *TokenIssuer {
	return &TokenIssuer{secret: []byte(secret), accessTTL: accessTTL, refreshTTL: refreshTTL}
}

// AccessTTL 返回访问令牌有效期。
func (t *TokenIssuer) AccessTTL() time.Duration { return t.accessTTL }

// RefreshTTL 返回刷新令牌有效期。
func (t *TokenIssuer) RefreshTTL() time.Duration { return t.refreshTTL }

// IssueAccess 签发 JWT 访问令牌。
func (t *TokenIssuer) IssueAccess(userID, email, role string) (string, time.Time, error) {
	now := time.Now()
	exp := now.Add(t.accessTTL)
	claims := Claims{
		UserID: userID,
		Email:  email,
		Role:   role,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "podcastapp",
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(exp),
			NotBefore: jwt.NewNumericDate(now.Add(-time.Minute)),
		},
	}
	raw, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(t.secret)
	return raw, exp, err
}

// ParseAccess 校验访问令牌。
func (t *TokenIssuer) ParseAccess(raw string) (*Claims, error) {
	claims := &Claims{}
	tok, err := jwt.ParseWithClaims(raw, claims, func(tk *jwt.Token) (any, error) {
		if tk.Method.Alg() != jwt.SigningMethodHS256.Alg() {
			return nil, errors.New("auth: unexpected signing method")
		}
		return t.secret, nil
	}, jwt.WithIssuer("podcastapp"), jwt.WithExpirationRequired())
	if err != nil {
		return nil, err
	}
	if !tok.Valid {
		return nil, errors.New("auth: invalid token")
	}
	return claims, nil
}

// NewRefreshToken 生成不透明刷新令牌与它的哈希。
func NewRefreshToken() (string, string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", "", err
	}
	raw := base64.RawURLEncoding.EncodeToString(buf)
	return raw, HashToken(raw), nil
}

// HashToken 对刷新令牌做 SHA-256，仅存哈希。
func HashToken(raw string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(raw)))
	return hex.EncodeToString(sum[:])
}
