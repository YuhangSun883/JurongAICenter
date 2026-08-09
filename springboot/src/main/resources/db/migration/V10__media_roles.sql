-- ============================================================================
-- V10：媒体角色库（media_roles）
--
-- 设计原则：
--   - 角色库是一组"精选人脸/形象"，给画布/Agent 作主体参考
--   - 每个角色归属一个分类（逼真人脸/都市蓝领/儿童/...）
--   - 软删除（@TableLogic）
--   - is_locked=1 表示系统角色（不可删/改），is_locked=0 表示用户自建
--
-- 注意：V8 已经建过 media_libraries + media_assets，本迁移不重复建。
-- ============================================================================

CREATE TABLE IF NOT EXISTS media_roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '角色名称',
    category VARCHAR(50) NOT NULL COMMENT '分类（face/urban-blue/...）',
    image_url VARCHAR(500) NOT NULL COMMENT '角色预览图 URL（MinIO',
    is_locked TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=系统锁定不可删, 0=用户自建',
    description VARCHAR(500) COMMENT '角色描述（性格/用途）',
    tags VARCHAR(255) COMMENT '逗号分隔标签',
    sort_order INT NOT NULL DEFAULT 0,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_category_sort (category, sort_order ASC, id ASC),
    INDEX idx_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='媒体角色库（精选人脸/形象）';

-- 种子数据：按 category 各塞 3-5 个示例角色
-- （生产环境可由运营/管理员通过后台管理）
INSERT INTO media_roles (name, category, image_url, description, tags, sort_order) VALUES
  ('都市白领-晓晓', 'urban-blue', 'https://picsum.photos/seed/role-urban-1/300/400', '30岁都市白领女性，温柔知性', '白领,女性,30+', 0),
  ('蓝领工人-大刚', 'urban-blue', 'https://picsum.photos/seed/role-urban-2/300/400', '40岁蓝领工人，刚毅朴实', '蓝领,男性,40+', 1),
  ('银发奶奶-王奶奶', 'urban-silver', 'https://picsum.photos/seed/role-silver-1/300/400', '65岁银发奶奶，慈祥亲切', '银发,奶奶,65+', 0),
  ('小孩-小明', 'kids', 'https://picsum.photos/seed/role-kid-1/300/400', '6岁小男孩，活泼可爱', '男孩,儿童,6', 0),
  ('妈妈-丽丽', 'mom', 'https://picsum.photos/seed/role-mom-1/300/400', '32岁精致妈妈，优雅时尚', '妈妈,女性,30+', 0),
  ('小镇青年-阿强', 'town-young', 'https://picsum.photos/seed/role-town-1/300/400', '24岁小镇青年，阳光质朴', '小镇,青年,男', 0),
  ('二次元-樱', 'fantasy', 'https://picsum.photos/seed/role-fantasy-1/300/400', '动漫少女，活力满满', '二次元,少女', 0),
  ('国风-青衣', 'chinese', 'https://picsum.photos/seed/role-chinese-1/300/400', '古风女性，优雅端庄', '国风,古装', 0),
  ('模特-安娜', 'fashion', 'https://picsum.photos/seed/role-fashion-1/300/400', '25岁时尚模特，高挑冷艳', '模特,女性,25+', 0),
  ('猫咪-小白', 'animal', 'https://picsum.photos/seed/role-animal-1/300/400', '可爱白色猫咪', '猫咪,动物', 0),
  ('逼真美女-丽人', 'face', 'https://picsum.photos/seed/role-face-1/300/400', '28岁逼真人脸，自然生动', '人脸,女性,28', 0),
  ('小镇中老年-老李', 'town-mid', 'https://picsum.photos/seed/role-townmid-1/300/400', '55岁小镇中老年，男性', '小镇,中老年,男', 0);