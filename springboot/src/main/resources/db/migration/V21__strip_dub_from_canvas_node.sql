-- ============================================================================
-- 一次性数据清理：去掉 canvas_node.content 里残留的口播段
--
-- 背景：
--   VideoFrameCaptionService.assembleScript() 在 2026-08-10 之前的版本里,
--   会在每行 ShotXX 后面追加 ` 口播:"..."`(来自 ASR 抽取的口播原文)。
--   后续产品决定不再向前端展示口播段,后端生成代码已删除。
--   但数据库里旧节点 content 还残留,需要一次性 UPDATE 清掉。
--
-- 匹配模式:` 口播:"..."`(一个空格 + 口播: + 双引号包裹的内容 + 结束双引号)
-- 用 REGEXP_REPLACE(MySQL 8.0+ 支持),全局替换所有匹配项。
--
-- 期望影响行数:少量(只有 2026-08-10 之前跑过抽帧/脚本拆解的画布会命中)。
-- ============================================================================

UPDATE canvas_nodes
SET content = REGEXP_REPLACE(content, ' 口播:"[^"]*"', '')
WHERE content REGEXP ' 口播:"';

-- 验证一下:理想结果返回 0 行(应该已经没有"口播:"残留了)
-- 如果 > 0,说明有边界情况没匹配上,需要人工排查
SELECT COUNT(*) AS remaining_dub_rows
FROM canvas_nodes
WHERE content REGEXP ' 口播:"';