-- =====================================================================
-- 双策略切割排样升级：为切割单补充排样口径列。
-- 仅适用于已存在 cutting_order 表的既有库；演示环境的 schema.sql
-- 已包含这些列（初始化时 DROP 重建，无需执行本脚本）。
-- MySQL 8.0.29+ 支持 ADD COLUMN IF NOT EXISTS；低版本请逐条执行并忽略
-- “Duplicate column” 报错。
-- =====================================================================

ALTER TABLE cutting_order
    ADD COLUMN strategy VARCHAR(16) NULL COMMENT '采用策略 USAGE/REMNANT' AFTER operator;
ALTER TABLE cutting_order
    ADD COLUMN kerf_mm INT DEFAULT 0 COMMENT '刀缝宽度mm' AFTER strategy;
ALTER TABLE cutting_order
    ADD COLUMN allow_rotation TINYINT(1) DEFAULT 1 COMMENT '是否允许旋转90度' AFTER kerf_mm;

-- 历史单据按旧版口径回填：单策略、刀缝 0、允许旋转
UPDATE cutting_order SET strategy = 'USAGE' WHERE strategy IS NULL;
UPDATE cutting_order SET kerf_mm = 0 WHERE kerf_mm IS NULL;
UPDATE cutting_order SET allow_rotation = 1 WHERE allow_rotation IS NULL;
