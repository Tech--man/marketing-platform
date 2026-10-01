-- ============================================================
-- 迁移：秒杀订单「取消后重抢」与唯一索引对齐（2026-09-29 架构审查 H7）
--   适用：本变更之前已建好的 MySQL 卷（含常驻数据卷 mkt-data_mysql-data）
--   新库不需要执行（init 与 init-lite 两份 DDL 已含列与索引）
--
--   对"每个装着 seckill_order 的业务库"各执行一遍：
--     单库档      → mysql -umarketing -pmarketing123 marketing           < 本文件
--     四库隔离档  → mysql -umarketing -pmarketing123 marketing_seckill   < 本文件
--   （其他库没有 seckill_order 表，本文件会整体跳过，不会误建。）
--
--   做三件事：
--     1) seckill_order 加 active 列（存量行取默认值 1 = 有效）；
--     2) 唯一索引 uk_activity_user 从 (activity_no, user_id) 换成
--        (activity_no, user_id, active) —— 只约束"一人一张有效单"；
--     3) 存量 CANCELLED 行回填 active=0 —— ADD COLUMN 的默认值 1 会把它们也标成
--        "有效单"，这批用户在旧索引下本就被永久锁死（无法重抢），不回填等于把
--        病灶原样留给存量数据（本仓常驻卷实测 6907 行 CANCELLED）。
--   成因：超时取消链路会回补库存并删防重标记（允许重抢），但旧唯一索引把
--   CANCELLED 行也算进占用——重抢的 insert 必撞旧行，消费端把已取消单号当
--   SUCCESS 回放，用户拿到永远付不了款的单号，且本次名额无主。
--
--   SET NAMES 必需：本文件有中文 COMMENT（成因见 2026-09-22-fix-seed-encoding.sql）。
--   可重复执行：列已存在 → DO 0；索引已是新形状 → 跳过。
-- ============================================================
SET NAMES utf8mb4;

-- 前置：本库有 seckill_order 吗
SET @has_order := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'seckill_order');

-- 1) active 列（MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 information_schema + 预处理兜幂等；
--    拼接一律用 CONCAT——默认 sql_mode 下 || 是逻辑或，不是字符串拼接）
SET @s := (SELECT IF(@has_order > 0 AND COUNT(*) = 0,
    CONCAT('ALTER TABLE seckill_order ADD COLUMN active TINYINT NOT NULL DEFAULT 1 ',
        'COMMENT ''是否有效单：取消置 0。H7：唯一索引带 active，只约束有效单——取消后允许重抢'''),
    'DO 0') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'seckill_order' AND column_name = 'active');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 唯一索引换形状：只在新列就位、且旧索引仍是两列形态时执行
--    （重跑时新索引已是三列，COUNT=0，自动跳过）
SET @old_index_two_cols := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'seckill_order'
      AND index_name = 'uk_activity_user' AND column_name = 'active');
SET @s := (SELECT IF(@has_order > 0
      AND (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'seckill_order'
             AND index_name = 'uk_activity_user') > 0
      AND @old_index_two_cols = 0,
    CONCAT('ALTER TABLE seckill_order ',
        'DROP INDEX uk_activity_user, ',
        'ADD UNIQUE KEY uk_activity_user (activity_no, user_id, active) ',
        'COMMENT ''防重复购买兜底（一人一张有效单；取消置 active=0 释放占用）'''),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 2.5) 存量回填：已取消的订单释放占用（active=0）。幂等：已置 0 的行不再命中
-- ------------------------------------------------------------
SET @s := (SELECT IF(@has_order > 0,
    'UPDATE seckill_order SET active = 0 WHERE status = ''CANCELLED'' AND active = 1',
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 自检：期望 active_column=1、index_cols=3、cancelled_rows=历史取消行数（有 seckill_order 的库）。
--    整条 SELECT 必须走 PREPARE 守卫（与上面三段同款）：外层 WHERE @has_order > 0 过滤不掉
--    子查询里 seckill_order 的解析期表引用——空 marketing 库（data compose 的
--    MYSQL_DATABASE 默认建的那个）上静态 SELECT 直接 ERROR 1146 中断 ALL_DBS 迁移链
--    （2026-10-01 DDL 门禁实测）。
SET @s := (SELECT IF(@has_order > 0,
    CONCAT('SELECT DATABASE() AS db, ',
        '(SELECT COUNT(*) FROM information_schema.columns ',
        ' WHERE table_schema = DATABASE() AND table_name = ''seckill_order'' ',
        '   AND column_name = ''active'') AS active_column, ',
        '(SELECT COUNT(*) FROM information_schema.statistics ',
        ' WHERE table_schema = DATABASE() AND table_name = ''seckill_order'' ',
        '   AND index_name = ''uk_activity_user'' AND column_name = ''active'') AS index_cols, ',
        '(SELECT COUNT(*) FROM seckill_order WHERE active = 0) AS cancelled_rows'),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
