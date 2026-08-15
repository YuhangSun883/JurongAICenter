-- ============================================================================
-- Jurong AICenter Backend - V5 迁移：admin 模块
-- 1. users.disabled     标记用户被禁用（B 域，Phase 9 提前落地）
-- 2. user_groups.deleted 已被 BaseMapper 接管，这里只确认软删字段存在（实际 V2 已加，无需 ALTER）
-- 3. admin_audit_logs   管理员操作审计记录（防误操作、可追溯）
-- 4. users 索引：role + deleted 组合索引（搜索场景）
-- ============================================================================

-- 1. users 表新增 disabled 字段（默认 0 = 启用）
ALTER TABLE users
    ADD COLUMN disabled TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '是否禁用：1=禁用（不可登录/不可生成），0=启用'
    AFTER role;

-- 2. admin_audit_logs 审计表（管理员每次改"用户/分组/成员"都写一条）
CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id BIGINT NOT NULL COMMENT '执行操作的管理员 user_id',
    admin_email VARCHAR(255) NOT NULL COMMENT '冗余存邮箱，便于审计展示（即使管理员改名也能查）',
    action VARCHAR(64) NOT NULL COMMENT '操作类型，参见 AdminAuditAction.java 枚举',
    target_type VARCHAR(32) NOT NULL COMMENT '目标类型：USER / GROUP / GROUP_MEMBER',
    target_id BIGINT COMMENT '目标主键（user_id 或 group_id）',
    detail JSON COMMENT '改动详情，例如 {fromRole:"USER", toRole:"ADMIN"} 或 {groupName:"VIP"}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_admin_created (admin_id, created_at),
    KEY idx_target (target_type, target_id),
    KEY idx_action_created (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员操作审计日志';

-- 3. users(role, deleted, disabled) 组合索引：admin 列表/筛选场景
ALTER TABLE users
    ADD INDEX idx_role_disabled (role, deleted, disabled);

-- 4. users.display_name 索引：admin 搜索场景（B 管理员按 displayName 搜）
ALTER TABLE users
    ADD INDEX idx_display_name (display_name);
