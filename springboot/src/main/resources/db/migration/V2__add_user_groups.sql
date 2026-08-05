-- ============================================================================
-- Jurong AICenter Backend - V2 迁移：客户分组
-- 新增 2 张表：user_groups（分组定义）+ user_group_members（用户-分组多对多）
-- 不修改现有 users 表，向后兼容
-- ============================================================================

-- 1. 分组定义表
CREATE TABLE IF NOT EXISTS user_groups (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '分组名称（唯一）',
    description VARCHAR(500) COMMENT '分组描述',
    color VARCHAR(20) DEFAULT '#909399' COMMENT '前端标签颜色（hex）',
    is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认分组：新用户注册时自动加入',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_name (name),
    KEY idx_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户分组表';

-- 2. 用户-分组中间表（多对多）
CREATE TABLE IF NOT EXISTS user_group_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_group (user_id, group_id),
    KEY idx_group (group_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户分组关联表';

-- 3. 初始化一个默认分组（所有新用户自动加入）
INSERT INTO user_groups (name, description, color, is_default)
VALUES ('Default', '默认分组，所有新用户自动加入', '#909399', 1)
ON DUPLICATE KEY UPDATE name = name;
