-- ============================================================================
-- 用户提示词表
-- 保存用户常用提示词，支持按使用次数排序
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_prompts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL COMMENT '用户邮箱（关联 users 表 email 字段）',
    prompt TEXT NOT NULL COMMENT '提示词内容',
    use_count INT NOT NULL DEFAULT 0 COMMENT '使用次数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email_prompt (email, prompt(255)),
    KEY idx_email (email),
    KEY idx_use_count (use_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户提示词表';
