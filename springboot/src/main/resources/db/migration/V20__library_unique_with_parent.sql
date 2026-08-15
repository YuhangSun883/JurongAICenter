-- ============================================================================
-- V20: 改 media_libraries 唯一索引为 (user_id, parent_id, name)
--
-- 背景：
--   2026-08-15 V19 引入父子库，兄弟节点不能重名，但子库与父库 / 不同父库的
--   子库之间允许同名。V8 时代设的是 (user_id, name) 全局唯一，不再适用。
--
-- 注意：
--   1. MySQL 中 NULL 在唯一索引中视为互不相等，所以多行 (user_id, parent_id=NULL, name=X) 不冲突
--   2. 现有 (user_id, name) 唯一索引在数据无重复时可直接 DROP
--   3. 添加新索引时若已存在冲突数据会失败，本次不会有（手工测试数据会清理）
-- ============================================================================

-- 1. 删旧唯一索引
ALTER TABLE media_libraries DROP INDEX uk_user_name;

-- 2. 加新唯一索引：同用户同父库下不能重名
--    MySQL 行为：NULL != NULL，所以多个根级 (parent_id=NULL) 也不冲突（与 V8 行为差异：现在根级重名也会失败）
ALTER TABLE media_libraries
  ADD UNIQUE KEY uk_user_parent_name (user_id, parent_id, name);
