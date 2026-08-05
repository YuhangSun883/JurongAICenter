-- V6: 登出支持 — 记录已撤销的 refresh token jti
-- 目的：登出后，refresh token 无法再换新 access token（access token 自然过期后强制重新登录）

CREATE TABLE IF NOT EXISTS revoked_tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- refresh token 的 jti（JWT 唯一标识）
    jti VARCHAR(64) NOT NULL,
    -- 关联用户（审计用）
    user_id BIGINT NOT NULL,
    -- 撤销时间
    revoked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 到期时间（refresh token 本身的过期时间），用作定时清理
    expires_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_jti (jti),
    KEY idx_user_id (user_id),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='已撤销的 refresh token 列表（登出 / 改密 / 管理员强制下线）';