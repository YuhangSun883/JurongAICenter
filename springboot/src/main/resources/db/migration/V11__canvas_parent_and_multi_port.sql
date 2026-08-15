-- ============================================================================
-- V11：画布父表 + 节点多入口/出口端口
--   1. canvas           画布本身（容器），一组节点的归属
--   2. canvas_nodes.canvas_id  把节点挂到画布下
--
-- 设计：
--   - 一张画布 = 一个"项目"，用户可建多张（"我的创作" 列表）
--   - canvas_nodes.canvas_id NULL 表示孤儿节点，BackfillRunner 在启动时
--     自动给每个 user 建一个"默认画布"并把孤儿节点挪进去
--   - 上游/下游节点 ID 列表升级成多端口格式：
--       旧：[ "nodeId1", "nodeId2" ]
--       新：[ {"port": "video", "nodeId": "nodeId1"},
--            {"port": "text",  "nodeId": "nodeId2"} ]
--     数据格式转换在 Java (CanvasBackfillRunner) 完成，更安全
-- ============================================================================

CREATE TABLE IF NOT EXISTS canvas (
    id VARCHAR(32) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    thumbnail VARCHAR(1024),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_updated (user_id, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='画布表（容器，每个用户可建多张）';

ALTER TABLE canvas_nodes
    ADD COLUMN canvas_id VARCHAR(32) AFTER user_id,
    ADD INDEX idx_canvas (canvas_id),
    ADD INDEX idx_canvas_position (canvas_id, position_x, position_y);

-- 业务约束（应用层校验，DB 层不强加 FK 避免删库顺序耦合）