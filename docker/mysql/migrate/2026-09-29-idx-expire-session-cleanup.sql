-- ============================================================
-- 迁移：第四批索引补齐（2026-09-29 架构审查）
--   1) user_coupon 加 idx_status_expire (status, expire_at)
--      过期扫描 CouponExpireJob 每分钟 WHERE status='UNUSED' AND expire_at<now，
--      无此前导索引 = 全表扫（表只增不删，随发券量线性恶化）。
--   2) consumer_session 加 idx_refresh_expire (refresh_expire_at)
--      会话清理任务按 refresh 寿命扫过期行。
--
--   适用：本变更之前已建好的 MySQL 卷；新库不需要执行（两份 init DDL 已含）。
--   对装着对应表的库执行（单库档 marketing 一遍即可；隔离档对
--   marketing / marketing_seckill 各执行——本文件自带表存在性守卫，乱跑无害）。
--   SET NAMES 必需（中文 COMMENT）；可重复执行（information_schema 守卫）。
-- ============================================================
SET NAMES utf8mb4;

-- 1) user_coupon.idx_status_expire
SET @s := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'user_coupon') > 0
    AND (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'user_coupon'
          AND index_name = 'idx_status_expire') = 0,
    CONCAT('ALTER TABLE user_coupon ',
        'ADD KEY idx_status_expire (status, expire_at) ',
        'COMMENT ''过期扫描前导索引（CouponExpireJob 每分钟），2026-09-29 补'''),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) consumer_session.idx_refresh_expire
SET @s := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'consumer_session') > 0
    AND (SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'consumer_session'
          AND index_name = 'idx_refresh_expire') = 0,
    CONCAT('ALTER TABLE consumer_session ',
        'ADD KEY idx_refresh_expire (refresh_expire_at) ',
        'COMMENT ''清理任务按 refresh 寿命扫过期行，2026-09-29 补'''),
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 自检：期望 coupon_idx=1（有 user_coupon 的库）、session_idx=1（有 consumer_session 的库）
SELECT DATABASE() AS db,
       (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'user_coupon'
           AND index_name = 'idx_status_expire') AS coupon_idx,
       (SELECT COUNT(*) FROM information_schema.statistics
         WHERE table_schema = DATABASE() AND table_name = 'consumer_session'
           AND index_name = 'idx_refresh_expire') AS session_idx;
