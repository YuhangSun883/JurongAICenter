-- ============================================================================
-- V8: 资产库模块（Media Library + Media Asset）
--
--   1. media_libraries  用户资产库表
--      - 每个用户注册时自动建 2 个系统默认库：
--          * "我的资产" (type='system-uploaded')  装用户上传的素材
--          * "AI 生成结果" (type='system-ai')     装 AI 生成的素材
--      - 用户可建自定义库 (type='custom')，可重命名/删除
--      - 系统库 (type 以 'system-' 开头) 不可重命名/删除
--
--   2. media_assets     素材资产表
--      - 每个素材归属于一个 library
--      - 删除 custom 库时，库内素材一并删除（连 MinIO 对象）
--
-- 设计原则：
--   - 软删除（MyBatis Plus @TableLogic），deleted=0/1
--   - 唯一约束：同一用户下库名不重
-- ============================================================================

CREATE TABLE IF NOT EXISTS media_libraries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL COMMENT 'system-uploaded / system-ai / custom',
    icon_key VARCHAR(32) DEFAULT 'folder' COMMENT 'folder/star/heart/sparkles',
    description VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_name (user_id, name),
    KEY idx_user_type (user_id, type),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户资产库';

CREATE TABLE IF NOT EXISTS media_assets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    library_id BIGINT NOT NULL COMMENT '所属资产库',
    type VARCHAR(16) NOT NULL COMMENT 'image/video/audio',
    source VARCHAR(16) NOT NULL COMMENT 'uploaded/ai-generated',
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100),
    size_bytes BIGINT,
    width INT,
    height INT,
    duration_sec DECIMAL(10,2),
    object_key VARCHAR(500) NOT NULL,
    source_tool VARCHAR(32) COMMENT 'video/image/canvas/agent/upload',
    source_task_id VARCHAR(64) COMMENT '关联的任务 ID',
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_created (user_id, created_at),
    KEY idx_user_type (user_id, type),
    KEY idx_user_source (user_id, source),
    KEY idx_user_library (user_id, library_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户素材资产表';
