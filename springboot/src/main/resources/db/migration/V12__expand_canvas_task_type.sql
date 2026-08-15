-- V12: 扩展 canvas_tasks.type 列长度
--
-- 背景：V7__canvas_module.sql 创建 canvas_tasks.type 时给的 VARCHAR(16)，
-- 原值是 text/image/video/audio（最长 5），后来加了视频抽帧 (video-frame-caption, 19 字符)
-- 写入时被 MySQL 截断报错：Data too long for column 'type' at row 1
--
-- 改成 VARCHAR(32) 留足冗余，未来再加 task 类型不用再迁移。

ALTER TABLE canvas_tasks MODIFY COLUMN type VARCHAR(32) NOT NULL;