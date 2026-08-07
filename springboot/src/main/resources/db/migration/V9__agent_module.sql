-- ============================================================================
-- V9: Agent 对话模块（MVP - 仅对话核心：CRUD + 记忆）
--
-- 设计原则：
--   - 对话之间没有联系：每个 session 独立，互不关联
--   - 对话记忆：每个 session 保存多条消息，按 created_at 升序
--   - title 支持修改（rename）
--   - 用户隔离：user_id 必填，所有查询都带
--   - 不做支付/订单/套餐/兑换卡（独立 PR）
--
-- 注意：V8 是 media_assets（用户素材库），跟本模块无关。
--
-- 表：
--   1. agent_sessions  对话列表
--   2. agent_messages  对话消息（user / assistant / system 3 种角色）
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_sessions (
    id VARCHAR(32) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '新对话',
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    credits_used INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_updated (user_id, updated_at DESC),
    INDEX idx_user_pinned (user_id, pinned DESC, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Agent 对话会话表';

CREATE TABLE IF NOT EXISTS agent_messages (
    id VARCHAR(32) PRIMARY KEY,
    session_id VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    attachments TEXT,
    tool_calls TEXT,
    error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_session_created (session_id, created_at ASC),
    INDEX idx_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Agent 对话消息表';
