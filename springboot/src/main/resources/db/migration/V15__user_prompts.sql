-- ============================================================================
-- 用户提示词表
-- 保存用户常用提示词，支持按使用次数排序
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_prompts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL COMMENT '用户邮箱（关联 users 表 email 字段）',
    title VARCHAR(255) NOT NULL DEFAULT '' COMMENT '提示词标题',
    prompt TEXT NOT NULL COMMENT '提示词内容',
    use_count INT NOT NULL DEFAULT 0 COMMENT '使用次数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email_prompt (email, prompt(255)),
    KEY idx_email (email),
    KEY idx_use_count (use_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户提示词表';

-- 如果是 V15__user_prompts.sql 之前跑过（无 title 列），补加 title
-- （V15__user_prompts.sql 和 V15__user_prompts_add_title.sql 合并后，幂等执行）
DROP PROCEDURE IF EXISTS _add_title_if_missing;
CREATE PROCEDURE _add_title_if_missing()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_prompts'
          AND COLUMN_NAME = 'title'
    ) THEN
        ALTER TABLE user_prompts
            ADD COLUMN title VARCHAR(255) NOT NULL DEFAULT '' COMMENT '提示词标题' AFTER id;
    END IF;
END;
CALL _add_title_if_missing();
DROP PROCEDURE _add_title_if_missing;
