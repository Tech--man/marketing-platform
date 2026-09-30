-- ============================================================
-- 迁移：幂等执行记录加 claim_token 列（2026-09-30 第二轮复审 W1.1）
--   适用：已建好的存量 MySQL 卷（新库不需要执行，两份 init DDL 已含列）
--   对"每个装着 idempotent_record 的业务库"各执行一遍（migrate.sh ALL_DBS=1 会按
--   schema_name LIKE 'marketing%' 遍历，没有这张表的库本文件整体跳过）。
--
--   成因：markSuccess/markFailed 的 WHERE 只判 status='PROCESSING'，不区分是哪一次
--   执行。动作耗时超过租约（默认 120s，GC/下游挂起）被接管后，原持有者的回写仍能
--   命中接管者的 PROCESSING：markFailed 会把行打成 FAILED 放开第三次执行（与接管者
--   并发），markSuccess 则让两个调用方拿到不同结果。claim_token 是执行代次：
--   claim（INSERT/租约 CAS/FAILED 抢占）时写入随机 UUID，mark 时必须比对——
--   旧持有者的回写一律落空（updated=0 只 warn）。
--
--   存量行 claim_token 为 NULL：只存在于终态（SUCCESS/FAILED）与在途 PROCESSING 行。
--   终态行不会再被 mark，NULL 无影响；在途 PROCESSING 行会在下一次租约接管时
--   由接管的 UPDATE 写入新 token（claim 的三条路径都 SET claim_token）。
--
--   SET NAMES 必需：本文件有中文 COMMENT。
--   可重复执行：列已存在 → 跳过（information_schema 判定）。
-- ============================================================
SET NAMES utf8mb4;

-- 前置：本库有 idempotent_record 吗
SET @has_table := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'idempotent_record');

-- 1) 加 claim_token 列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，用 information_schema 兜幂等）
SET @s := (SELECT IF(@has_table > 0
      AND (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'idempotent_record'
             AND column_name = 'claim_token') = 0,
    CONCAT('ALTER TABLE idempotent_record ADD COLUMN claim_token VARCHAR(36) NULL ',
        'COMMENT ''执行代次（W1.1 fencing）：claim 时写入，mark 成功/失败都要比对——',
        '慢持有者被租约接管后的回写不会毒化接管者'' AFTER error_msg'),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 自检：期望 claim_token_column=1（有 idempotent_record 的库）
SELECT DATABASE() AS db,
       (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'idempotent_record'
           AND column_name = 'claim_token') AS claim_token_column
    WHERE @has_table > 0;
