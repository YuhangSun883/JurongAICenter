-- ============================================================================
-- V18: 资产库业务类型 + 真人授权
--
-- 背景：
--   2026-08-15 资产库升级，参考示例网站 da-ai.cc/assets 实现按库类型分类：
--     - normal        普通库
--     - virtual_human 虚拟人库
--     - real_person   真人库（需要授权说明 + 授权有效期）
--
-- 注意：
--   1. 已有库默认 biz_type='normal'，保持兼容。
--   2. 授权字段只对 real_person 类型有意义，其他类型可为空。
--   3. 授权状态（valid/expired/none）由后端根据 auth_expire_at 与当前日期计算。
--   4. 不要复用 V8 的 `type` 字段（已用于 system-uploaded/system-ai/custom），
--      新字段命名 biz_type 避免冲突。
-- ============================================================================

ALTER TABLE media_libraries
  ADD COLUMN biz_type      VARCHAR(32)  DEFAULT 'normal'  COMMENT '库业务类型：normal/virtual_human/real_person',
  ADD COLUMN auth_purpose   TEXT         DEFAULT NULL      COMMENT '授权用途说明（仅real_person）',
  ADD COLUMN auth_expire_at DATE         DEFAULT NULL      COMMENT '授权有效期（仅real_person）';

-- 索引：按 biz_type 筛选常用
ALTER TABLE media_libraries
  ADD INDEX idx_media_libraries_biz_type (user_id, biz_type);
