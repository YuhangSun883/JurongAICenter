-- V13: 给 canvas_tasks 加 created_node_ids 列
--
-- 背景：抽帧 / 脚本拆解完成后，前端只想要这次新创建的节点追加到本地 state，
-- 不要 reloadCanvasFromBackend 拉整张画布（否则会把之前所有节点都堆左上角）。
-- 这个列存"这次任务新建的 CanvasNode.id 列表"，前端轮询成功后只拉这些节点。
--
-- TEXT 而非 JSON：方便兼容 MySQL 5.x + 直接 SELECT SUBSTRING 调试；解析在 Java 端做。
-- 幂等：列已存在则跳过（数据库可能已被更高版本迁移过）

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'canvas_tasks' AND COLUMN_NAME = 'created_node_ids');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE canvas_tasks ADD COLUMN created_node_ids TEXT NULL COMMENT ''本次任务新建的 CanvasNode ID 列表(JSON 数组)''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;