-- 为 user_prompts 表添加 title 列（提示词标题）
-- 幂等：如果 title 列已存在则跳过

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
