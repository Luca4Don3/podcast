package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/podcastapp/server/internal/httpx"
	"github.com/podcastapp/server/internal/store"
)

type registerRequest struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"displayName"`
	InviteCode  string `json:"inviteCode"`
	DeviceID    string `json:"deviceId"`
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	DeviceID string `json:"deviceId"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
	DeviceID     string `json:"deviceId"`
}

type logoutRequest struct {
	RefreshToken string `json:"refreshToken"`
	AllDevices   bool   `json:"allDevices"`
}

type tokenPair struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ExpiresIn    int64  `json:"expiresIn"`
	TokenType    string `json:"tokenType"`
}

type authResponse struct {
	User  userResponse `json:"user"`
	Token tokenPair    `json:"token"`
}

type userResponse struct {
	ID          string `json:"id"`
	Email       string `json:"email"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
	CreatedAt   string `json:"createdAt"`
}

func toUserResponse(u *store.User) userResponse {
	return userResponse{ID: u.ID, Email: u.Email, DisplayName: u.DisplayName, Role: u.Role, CreatedAt: u.CreatedAt}
}

// handleRegister 注册账号并直接登录。
func (a *API) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	req.Email = strings.ToLower(strings.TrimSpace(req.Email))
	if !validEmail(req.Email) {
		httpx.Fail(w, http.StatusBadRequest, "invalid_email", "邮箱格式不正确")
		return
	}
	if len(req.Password) < 8 {
		httpx.Fail(w, http.StatusBadRequest, "weak_password", "密码至少 8 位")
		return
	}
	if a.cfg.InviteCode != "" && req.InviteCode != a.cfg.InviteCode {
		httpx.Fail(w, http.StatusForbidden, "invalid_invite", "邀请码不正确")
		return
	}

	hash, err := hashPassword(req.Password)
	if err != nil {
		httpx.Fail(w, http.StatusInternalServerError, "internal", "密码处理失败")
		return
	}
	now := store.Now()
	user := &store.User{
		ID:           newID(),
		Email:        req.Email,
		PasswordHash: hash,
		DisplayName:  defaultDisplayName(req.DisplayName, req.Email),
		Role:         "user",
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	// 首个注册用户成为管理员，便于自建服务初始化。
	if count, err := a.store.CountUsers(r.Context()); err == nil && count == 0 {
		user.Role = "admin"
	}
	if err := a.store.CreateUser(r.Context(), user); err != nil {
		if errors.Is(err, store.ErrConflict) {
			httpx.Fail(w, http.StatusConflict, "email_taken", "该邮箱已注册")
			return
		}
		httpx.FailStore(w, err)
		return
	}

	token, err := a.issueTokens(r, user, req.DeviceID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, authResponse{User: toUserResponse(user), Token: token})
}

// handleLogin 校验账号密码并签发令牌。
func (a *API) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	user, err := a.store.UserByEmail(r.Context(), req.Email)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			httpx.Fail(w, http.StatusUnauthorized, "invalid_credentials", "邮箱或密码错误")
			return
		}
		httpx.FailStore(w, err)
		return
	}

	ok, needsRehash, err := verifyPassword(req.Password, user.PasswordHash)
	if err != nil || !ok {
		httpx.Fail(w, http.StatusUnauthorized, "invalid_credentials", "邮箱或密码错误")
		return
	}
	if needsRehash {
		if hash, herr := hashPassword(req.Password); herr == nil {
			_ = a.store.UpdateUserPassword(r.Context(), user.ID, hash)
		}
	}

	token, err := a.issueTokens(r, user, req.DeviceID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, authResponse{User: toUserResponse(user), Token: token})
}

// handleRefresh 用刷新令牌换新的令牌对（旋转刷新令牌）。
func (a *API) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	hash := hashToken(req.RefreshToken)
	stored, err := a.store.RefreshTokenByHash(r.Context(), hash)
	if err != nil {
		httpx.Fail(w, http.StatusUnauthorized, "invalid_refresh", "刷新令牌无效")
		return
	}
	if stored.RevokedAt.Valid || stored.ExpiresAt < store.Now() {
		httpx.Fail(w, http.StatusUnauthorized, "invalid_refresh", "刷新令牌已失效")
		return
	}
	user, err := a.store.UserByID(r.Context(), stored.UserID)
	if err != nil {
		httpx.Fail(w, http.StatusUnauthorized, "invalid_refresh", "账号不存在")
		return
	}
	if err := a.store.RevokeRefreshToken(r.Context(), stored.ID); err != nil {
		httpx.FailStore(w, err)
		return
	}
	deviceID := req.DeviceID
	if deviceID == "" {
		deviceID = stored.DeviceID
	}
	token, err := a.issueTokens(r, user, deviceID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, authResponse{User: toUserResponse(user), Token: token})
}

// handleLogout 撤销刷新令牌。
func (a *API) handleLogout(w http.ResponseWriter, r *http.Request) {
	var req logoutRequest
	if err := httpx.Decode(r, &req); err != nil && !errors.Is(err, http.ErrBodyReadAfterClose) && err.Error() != "EOF" {
		// 允许空请求体
		_ = err
	}
	userID := httpx.UserIDFrom(r.Context())
	if req.AllDevices || req.RefreshToken == "" {
		if err := a.store.RevokeUserTokens(r.Context(), userID, ""); err != nil {
			httpx.FailStore(w, err)
			return
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
		return
	}
	if stored, err := a.store.RefreshTokenByHash(r.Context(), hashToken(req.RefreshToken)); err == nil && stored.UserID == userID {
		_ = a.store.RevokeRefreshToken(r.Context(), stored.ID)
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleMe 返回当前用户资料。
func (a *API) handleMe(w http.ResponseWriter, r *http.Request) {
	user, err := a.store.UserByID(r.Context(), httpx.UserIDFrom(r.Context()))
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"user": toUserResponse(user)})
}

type updateProfileRequest struct {
	DisplayName string `json:"displayName"`
}

// handleUpdateProfile 更新昵称。
func (a *API) handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	var req updateProfileRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	name := strings.TrimSpace(req.DisplayName)
	if len([]rune(name)) > 40 {
		httpx.Fail(w, http.StatusBadRequest, "invalid_name", "昵称过长")
		return
	}
	userID := httpx.UserIDFrom(r.Context())
	if err := a.store.UpdateUserProfile(r.Context(), userID, name); err != nil {
		httpx.FailStore(w, err)
		return
	}
	user, err := a.store.UserByID(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"user": toUserResponse(user)})
}

type changePasswordRequest struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

// handleChangePassword 修改密码并踢掉其它设备。
func (a *API) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	var req changePasswordRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, http.StatusBadRequest, "invalid_body", "请求体格式错误")
		return
	}
	if len(req.NewPassword) < 8 {
		httpx.Fail(w, http.StatusBadRequest, "weak_password", "新密码至少 8 位")
		return
	}
	userID := httpx.UserIDFrom(r.Context())
	user, err := a.store.UserByID(r.Context(), userID)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	ok, _, err := verifyPassword(req.CurrentPassword, user.PasswordHash)
	if err != nil || !ok {
		httpx.Fail(w, http.StatusForbidden, "invalid_credentials", "当前密码错误")
		return
	}
	hash, err := hashPassword(req.NewPassword)
	if err != nil {
		httpx.FailStore(w, err)
		return
	}
	if err := a.store.UpdateUserPassword(r.Context(), userID, hash); err != nil {
		httpx.FailStore(w, err)
		return
	}
	if err := a.store.RevokeUserTokens(r.Context(), userID, ""); err != nil {
		httpx.FailStore(w, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// issueTokens 签发访问令牌并持久化刷新令牌。
func (a *API) issueTokens(r *http.Request, user *store.User, deviceID string) (tokenPair, error) {
	access, exp, err := a.issuer.IssueAccess(user.ID, user.Email, user.Role)
	if err != nil {
		return tokenPair{}, err
	}
	raw, hash, err := newRefreshToken()
	if err != nil {
		return tokenPair{}, err
	}
	now := time.Now().UTC()
	err = a.store.CreateRefreshToken(r.Context(), &store.RefreshToken{
		ID:        newID(),
		UserID:    user.ID,
		TokenHash: hash,
		DeviceID:  deviceID,
		UserAgent: truncate(r.UserAgent(), 200),
		ExpiresAt: now.Add(a.issuer.RefreshTTL()).Format(time.RFC3339Nano),
		CreatedAt: now.Format(time.RFC3339Nano),
	})
	if err != nil {
		return tokenPair{}, err
	}
	if deviceID != "" {
		_ = a.store.UpsertDevice(r.Context(), deviceID, user.ID, "", "android", r.UserAgent())
	}
	return tokenPair{
		AccessToken:  access,
		RefreshToken: raw,
		ExpiresIn:    int64(time.Until(exp).Seconds()),
		TokenType:    "Bearer",
	}, nil
}
