-- ============================================================================
-- V7：画布模块（Canvas）
--   1. canvas_nodes   画布节点表（text / image / video / audio 4 种类型）
--   2. canvas_tasks   画布异步生成任务表（每个 generate 调用产生一条）
--
-- 设计原则：
--   - canvas_nodes 保存节点元数据 + 产物（result_url / content）
--   - canvas_tasks 保存每次 generate 的入参/出参/状态，**异步任务**专用
--   - status 字段统一小写（idle/running/success/failed），对齐前端
-- ============================================================================

CREATE TABLE IF NOT EXISTS canvas_nodes (
    id VARCHAR(32) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    
    -- text / image / video / audio
    type VARCHAR(16) NOT NULL,
    
    title VARCHAR(255),
    content TEXT,                       -- 文本节点：原文；图片/视频节点：可存放上游输入
    asset_id VARCHAR(32),               -- 关联 media_items.id（可空）
    result_url VARCHAR(1024),           -- 图片/视频节点产物的公网 URL
    
    -- 输入参数（图片模型 settings、视频时长等），存 JSON 字符串
    settings TEXT,
    
    -- 节点在画布上的位置（前端记录/还原用）
    position_x INT DEFAULT 0,
    position_y INT DEFAULT 0,
    
    -- 上下游节点 ID 列表（前端连线关系），存 JSON 字符串
    upstream_ids TEXT,
    downstream_ids TEXT,
    
    -- idle / running / success / failed
    status VARCHAR(16) DEFAULT 'idle',
    fail_reason VARCHAR(500),
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_updated (user_id, updated_at DESC),
    INDEX idx_user_type (user_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='画布节点表';

CREATE TABLE IF NOT EXISTS canvas_tasks (
    id VARCHAR(32) PRIMARY KEY,         -- 任务 ID（UUID）
    node_id VARCHAR(32) NOT NULL,       -- 关联 canvas_nodes.id
    user_id BIGINT NOT NULL,
    
    -- text / image / video / audio
    type VARCHAR(16) NOT NULL,
    
    -- pending / running / success / failed
    status VARCHAR(16) DEFAULT 'pending',
    
    -- 入参（前端传过来的）
    prompt TEXT NOT NULL,               -- 用户原始输入
    upstream_content TEXT,              -- 上游节点输出（文本）
    settings TEXT,                      -- 模型参数（JSON 字符串）
    asset_ids TEXT,                     -- 引用素材 id（JSON 字符串）
    
    -- 出参（AI 生成结果）
    text_result TEXT,                   -- 文本节点：润色/生成的文本
    result_url VARCHAR(1024),           -- 图片/视频节点：产物的公网 URL
    
    -- 计费与统计
    credits_estimated INT DEFAULT 0,
    duration_ms INT,
    
    -- 失败原因（**不直接暴露给前端**，仅后端日志用）
    error_message VARCHAR(1000),
    
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_node (node_id),
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='画布异步生成任务';