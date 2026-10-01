-- ============================================================
-- 迁移：seckill_order.active 改 NULLable、取消单置 NULL（2026-09-30 第二轮复审 P0）
--   适用：已按 2026-09-29-seckill-active.sql 迁移过的存量 MySQL 卷
--   （新库不需要执行：init 与 init-lite 两份 DDL 已是新形状）
--
--   对"每个装着 seckill_order 的业务库"各执行一遍：
--     单库档      → mysql -umarketing -pmarketing123 marketing           < 本文件
--     四库隔离档  → mysql -umarketing -pmarketing123 marketing_seckill   < 本文件
--   （其他库没有 seckill_order 表，本文件会整体跳过，不会误建。）
--
--   成因：active 为 NOT NULL 时，唯一索引 (activity_no, user_id, active) 只容许
--   每个用户一张取消单——第二次「超时取消→重抢→再超时取消」的 UPDATE 必撞
--   uk_activity_user（DuplicateKeyException），且超时取消 Job 循环无逐单隔离，
--   毒单按 (status, create_time) 序靠前，把全站超时取消卡死（名额不回补、
--   bought 标记不删、已售不回减）。取消单的"不占用"语义用 NULL 表达：
--   MySQL/H2 唯一索引都不聚合 NULL，同一用户可有任意多张取消单。
--
--   做两件事：
--     1) active 列去掉 NOT NULL（列注释同步换新）；
--     2) 存量 active=0 的取消单回填为 NULL（幂等：回填后不再命中）。
--
--   SET NAMES 必需：本文件有中文 COMMENT。
--   可重复执行：列已 NULLable → 跳过；无 active=0 的行 → UPDATE 影响 0 行。
-- ============================================================
SET NAMES utf8mb4;

-- 前置：本库有 seckill_order 吗
SET @has_order := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'seckill_order');

-- 1) active 去 NOT NULL（MySQL 8 无 ALTER COLUMN IF EXISTS，用 information_schema 判 IS_NULLABLE 兜幂等）
SET @s := (SELECT IF(@has_order > 0
      AND (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'seckill_order'
             AND column_name = 'active' AND is_nullable = 'NO') > 0,
    CONCAT('ALTER TABLE seckill_order MODIFY COLUMN active TINYINT NULL DEFAULT 1 ',
        'COMMENT ''是否有效单：有效=1，取消置 NULL。H7/P0：唯一索引带 active 且 NULL 不聚合——',
        '同一用户可有任意多张取消单，二次取消不再撞索引'''),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 存量取消单（active=0）统一回填为 NULL，消除「一用户一取消单」的上限
SET @s := (SELECT IF(@has_order > 0,
    'UPDATE seckill_order SET active = NULL WHERE active = 0',
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 自检：期望 nullable=YES、cancelled_null=历史取消行数、residual_zero=0（有 seckill_order 的库）。
--    整条 SELECT 走 PREPARE 守卫：外层 WHERE 过滤不掉子查询里 seckill_order 的解析期
--    表引用——空 marketing 库上静态 SELECT 直接 ERROR 1146（同 2026-09-29-seckill-active.sql
--    的自检，2026-10-01 DDL 门禁实测）。
SET @s := (SELECT IF(@has_order > 0,
    CONCAT('SELECT DATABASE() AS db, ',
        '(SELECT is_nullable FROM information_schema.columns ',
        ' WHERE table_schema = DATABASE() AND table_name = ''seckill_order'' ',
        '   AND column_name = ''active'') AS nullable, ',
        '(SELECT COUNT(*) FROM seckill_order WHERE active IS NULL) AS cancelled_null, ',
        '(SELECT COUNT(*) FROM seckill_order WHERE active = 0) AS residual_zero'),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
