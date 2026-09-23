-- ============================================================
-- 迁移 ⑤：在线配置下发的落库面
--   适用：本变更之前已建好的 MySQL 卷（含常驻数据卷 mkt-data_mysql-data）
--   新库不需要执行（init 与 init-lite 两份 DDL 已含表与列）
--
--   对"每个业务库"各执行一遍即可，本文件自己会判断该库里有什么：
--     单库档      → mysql -umarketing -pmarketing123 marketing           < 本文件
--     四库隔离档  → 对 marketing_activity / _coupon / _discount / _seckill / _admin 各执行一遍
--   每段都带"这张表在本库存在吗"的前置判断：不该出现 admin_config 的库（比如
--   marketing_activity）就真的不建它——留一张空表在那里，等于给"admin 连错库"
--   准备了一个"写进去没人读、读出来是空的"的静默陷阱。
--
--   SET NAMES 必需：本文件有中文 COMMENT，客户端默认字符集不是 utf8mb4 时会把中文
--   按 latin1 写进去（2026-09-22 的种子就是这个坑，见 fix-seed-encoding.sql 的成因说明）。
--
--   可重复执行：列已存在 → DO 0；表已存在 → IF NOT EXISTS；种子已改过 → WHERE 挡掉。
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) activity 灰度两列（仅在本库有 activity 表时）
--    MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 information_schema + 预处理兜幂等
-- ------------------------------------------------------------
SET @has_activity := (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'activity');

SET @s := (SELECT IF(@has_activity > 0 AND COUNT(*) = 0,
    'ALTER TABLE activity ADD COLUMN gray_percent INT NULL COMMENT ''灰度放量百分比 0-100；NULL=未配灰度=全量放行''',
    'DO 0') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'activity' AND column_name = 'gray_percent');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(@has_activity > 0 AND COUNT(*) = 0,
    'ALTER TABLE activity ADD COLUMN gray_whitelist VARCHAR(255) NULL COMMENT ''灰度白名单 userId CSV；NULL 或空=无白名单''',
    'DO 0') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'activity' AND column_name = 'gray_whitelist');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 既有卷的 ACT2026001 补种子：链路 0 断言"percent=100 命中"，而 yml 那份 gray 配置要被删掉
SET @s := (SELECT IF(@has_activity > 0,
    'UPDATE activity SET gray_percent = 100 WHERE activity_no = ''ACT2026001'' AND gray_percent IS NULL',
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 3) 真值表（仅在本库装着 admin_* 表时创建）
-- ------------------------------------------------------------
SET @s := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'admin_user') > 0
    AND (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'admin_config') = 0,
    'CREATE TABLE IF NOT EXISTS admin_config (
        id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        cfg_key     VARCHAR(64)  NOT NULL COMMENT ''参数键，与 ConfigDefinition.key 一致'',
        form        VARCHAR(16)  NOT NULL DEFAULT ''GLOBAL'' COMMENT ''GLOBAL/LITE/FULL/DEV'',
        cfg_value   VARCHAR(255) NOT NULL COMMENT ''按声明的 type 解析；越界或类型不符时读方逐条忽略'',
        version     BIGINT       NOT NULL DEFAULT 0 COMMENT ''写入时 mkt:cfg:seq 的值，仅用于展示第几版生效'',
        updated_by  VARCHAR(64)  NOT NULL DEFAULT '''' COMMENT ''最后一次写的后台账号'',
        remark      VARCHAR(255) NOT NULL DEFAULT '''',
        create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
        update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id),
        UNIQUE KEY uk_key_form (cfg_key, form)
    ) ENGINE = InnoDB COMMENT ''在线配置真值（删行即恢复出厂）''',
    'DO 0'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 4) 自检：期望 gray_columns=2（有 activity 的库）、admin_config_exists 与库角色一致、
--    ACT2026001 的灰度是 100（有 activity 的库）
--    判读口径见 docs/superpowers/plans/2026-09-23-online-config-delivery.md 的 Task 3：
--    单库档 marketing → 2 / 1 / 100；隔离档 marketing_activity → 2 / 0 / 100；
--    隔离档 marketing_admin → 0 / 1 / NULL（这里既没有 activity 也没有灰度种子）
-- ------------------------------------------------------------
SELECT DATABASE() AS db,
       (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'activity'
           AND column_name IN ('gray_percent', 'gray_whitelist')) AS gray_columns,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_name = 'admin_config') AS admin_config_exists;
SET @s := (SELECT IF(@has_activity > 0,
    'SELECT activity_no, gray_percent FROM activity WHERE activity_no = ''ACT2026001''',
    'SELECT ''本库无 activity 表，跳过灰度种子自检'' AS note'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
