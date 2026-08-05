-- ============================================================================
-- JurongAICenter Backend - V4 迁移：工作流模板
-- 插入 3 个开箱即用模板（产品图 / 动漫风 / 图生视频）
-- is_template=1，所有用户可见可用
-- 注意：数据库 schema 已由 A/B 的 V4 迁移更新（add user id to admin users）
--       本迁移仅新增模板数据
-- ============================================================================

INSERT INTO workflows (user_id, name, description, graph_json, is_template, is_public, created_at, updated_at)
VALUES
(1, '产品图生成', '文生图模板 - 输入产品描述，生成 1024x1024 产品图',
 '{"1":{"class_type":"JurongTextToImage","inputs":{"prompt":"{{prompt}}","size":"1024x1024"}},"2":{"class_type":"SaveImage","inputs":{"images":["1",0],"filename_prefix":"jurong_product"}}}',
 1, 1, NOW(), NOW()),

(1, '动漫风格图', '文生图模板 - 输入描述，生成动漫风格插画',
 '{"1":{"class_type":"JurongTextToImage","inputs":{"prompt":"{{prompt}}, anime style, vibrant colors, detailed illustration","size":"1024x1024"}},"2":{"class_type":"SaveImage","inputs":{"images":["1",0],"filename_prefix":"jurong_anime"}}}',
 1, 1, NOW(), NOW()),

(1, '图生视频', '图生视频模板 - 上传图片 + 输入描述，生成视频',
 '{"1":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},"2":{"class_type":"JurongImageToVideo","inputs":{"image":["1",0],"prompt":"{{prompt}}"}}}',
 1, 1, NOW(), NOW());
