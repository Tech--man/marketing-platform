-- ============================================================
-- 迁移：审计日志加 source_id 幂等键（2026-09-30 第二轮复审 W1.2）
--   适用：已建好的存量 MySQL 卷（新库不需要执行，两份 init DDL 已含列与索引）
--   对装着 admin_audit_log 的库（单库档 marketing / 聚合档同库）执行。
--
--   成因：drain 是至少一次投递（insert 成功 → 进程死在 ACK 前 → reclaimStale 认领
--   重投 → 再 insert 一行），admin_audit_log 没有来源消息标识——同一动作两行审计
--   （时间、内容全同），"次数"语义（登录次数、改配置次数）被污染。source_id 用
--   Stream 消息 ID（XADD 全局唯一），uk_source 撞键时按"已落库"处理并照常 ACK。
--   NULL 不聚合唯一索引：存量行与 HTTP 直写路径不受影响。
--
--   SET NAMES 必需：本文件有中文 COMMENT。
--   可重复执行：列/索引已存在 → 跳过（information_schema 判定）。
-- ============================================================
SET NAMES utf8mb4;

SET @has_table := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log');

-- 1) source_id 列（ip 之后）
SET @s := (SELECT IF(@has_table > 0
      AND (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log'
             AND column_name = 'source_id') = 0,
    CONCAT('ALTER TABLE admin_audit_log ADD COLUMN source_id VARCHAR(64) NULL ',
        'COMMENT ''来源 Stream 消息 ID（W1.2 幂等）：drain 至少一次投递的去重键，',
        'NULL 为存量行/HTTP 直写'' AFTER ip'),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 唯一索引（MySQL 8 无 ADD INDEX IF NOT EXISTS，用 information_schema 判定）
SET @s := (SELECT IF(@has_table > 0
      AND (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log'
             AND index_name = 'uk_source') = 0,
    'ALTER TABLE admin_audit_log ADD UNIQUE KEY uk_source (source_id)',
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 自检：期望 source_col=1、uk_source=1（有 admin_audit_log 的库）
SELECT DATABASE() AS db,
       (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log'
           AND column_name = 'source_id') AS source_col,
       (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log'
           AND index_name = 'uk_source') AS uk_source
    WHERE @has_table > 0;
