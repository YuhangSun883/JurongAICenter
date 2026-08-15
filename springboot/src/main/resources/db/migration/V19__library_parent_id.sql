-- ============================================================================
-- V19: 资产库父子层级（无限嵌套）
--
-- 背景：
--   2026-08-15 资产库升级第二步。库支持父子嵌套（无限深度）：
--     - 根库：parent_id = NULL
--     - 子库：parent_id 指向父库 id
--     - 自引用 FK，RESTRICT 防误删
--
-- 业务规则：
--   1. 系统库（system-uploaded / system-ai）不能做父库/子库
--   2. 普通库（biz_type=normal）下子库类型不限
--   3. 虚拟人/真人库下子库类型必须与父库一致
--   4. 父库改不了（防 loop / 误改）
--   5. 删父库 → 级联删所有后代库 + 素材 + MinIO
--   6. 父库素材视图：各是各的，不递归聚合
--
-- 不动现有数据：所有现有库 parent_id = NULL（视为根库），无需迁移
-- ============================================================================

ALTER TABLE media_libraries
  ADD COLUMN parent_id BIGINT NULL COMMENT '父库 id，NULL=根库' AFTER user_id,
  ADD INDEX idx_media_libraries_parent (parent_id, user_id);

-- 自引用外键，RESTRICT 防误删
ALTER TABLE media_libraries
  ADD CONSTRAINT fk_media_libraries_parent
    FOREIGN KEY (parent_id) REFERENCES media_libraries(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;
