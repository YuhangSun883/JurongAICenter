-- ============================================================================
-- Jurong AICenter Backend - 初始数据库迁移
-- 5 张核心表: users / workflows / jobs / billing_logs + templates_v 视图
-- ============================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    credits INT NOT NULL DEFAULT 0 COMMENT '当前可用积分',
    monthly_quota INT NOT NULL DEFAULT 50 COMMENT '月度配额',
    quota_used INT NOT NULL DEFAULT 0 COMMENT '当月已用',
    quota_period_start DATE COMMENT '配额周期起点',
    plan VARCHAR(32) NOT NULL DEFAULT 'FREE' COMMENT 'FREE / PRO / PRO+ / ENTERPRISE',
    plan_expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_email (email),
    KEY idx_role (role),
    KEY idx_plan (plan)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 2. 工作流表
CREATE TABLE IF NOT EXISTS workflows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(255),
    description TEXT,
    graph_json JSON NOT NULL COMMENT 'ComfyUI workflow JSON',
    thumbnail_url VARCHAR(500),
    is_template TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否官方模板',
    is_public TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否发布到模板市场',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_user (user_id),
    KEY idx_template_public (is_template, is_public),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流表';

-- 3. 任务表
CREATE TABLE IF NOT EXISTS jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    workflow_id BIGINT,
    template_id VARCHAR(64) COMMENT '从哪个模板生成的',
    comfyui_prompt_id VARCHAR(100) COMMENT 'ComfyUI /prompt 返回的 ID',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / COMPLETED / FAILED / CANCELLED',
    inputs_snapshot JSON COMMENT '提交时的输入参数',
    graph_snapshot JSON COMMENT '提交时的 graph JSON',
    result_urls JSON COMMENT 'MinIO 产物 URL',
    error_message TEXT,
    credits_cost INT NOT NULL DEFAULT 0,
    duration_ms INT COMMENT '实际耗时',
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_user_status (user_id, status),
    KEY idx_prompt_id (comfyui_prompt_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- 4. 计费流水表（Phase 8 启用）
CREATE TABLE IF NOT EXISTS billing_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    job_id BIGINT,
    type VARCHAR(20) NOT NULL COMMENT 'CONSUME / RECHARGE / REFUND / GRANT / EXPIRE',
    credits_delta INT NOT NULL COMMENT '正负',
    balance_after INT NOT NULL,
    description VARCHAR(255),
    payment_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_created (user_id, created_at),
    KEY idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计费流水表';

-- 5. 模板视图（官方 + 用户公开的工作流）
CREATE OR REPLACE VIEW templates_v AS
SELECT id, user_id, name, description, graph_json, thumbnail_url, created_at, updated_at
FROM workflows
WHERE is_template = 1 OR is_public = 1;

-- 插入一个默认管理员账号（密码: admin123，bcrypt 哈希需要真实生成）
-- INSERT INTO users (email, password_hash, display_name, role, plan)
-- VALUES ('admin@jurong.local', '$2a$10$...bcrypt_hash_here...', 'Admin', 'ADMIN', 'ENTERPRISE');
-- 注：实际初始化通过 Java 代码做，这里只是示意。