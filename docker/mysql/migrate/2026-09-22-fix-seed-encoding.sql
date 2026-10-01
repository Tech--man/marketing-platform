-- ============================================================
-- 修复：init 文件里的中文种子被按 latin1 客户端连接写坏（双重编码）
-- 日期：2026-09-22（2026-10-01 幂等化重写，见文末"重写说明"）
-- 背景：docker-entrypoint-initdb.d 用容器内 mysql 客户端执行，其 character_set_client
--       默认是 latin1（实测：服务端 utf8mb4 时客户端仍是 latin1），于是种子中文
--       先被按 latin1 解释、再存成 utf8mb4，落库即 "2026 秋季大促" -> "2026 ç§‹å­£å¤§ä¿ƒ"。
--       init 文件已补 SET NAMES utf8mb4，新卷不再复现；本脚本修的是**已经建好的卷**。
-- 幂等：每句都带 `AND <col> <> '<正确字面量>'`，已经正确的行不会被再次改写，
--       重复执行是 no-op；不加这个条件的话，正确数据会被同一段 CONVERT 二次损坏。
-- 手法：CONVERT(CAST(CONVERT(col USING latin1) AS BINARY) USING utf8mb4)
--       是"把被误读成 latin1 的 utf8 字节还原"的标准做法，对 JSON 串里的中文同样有效。
-- 前置：只对 5 个业务号（种子）操作，不碰应用写入的行。
-- ============================================================
-- 重写说明（2026-10-01，审计 P1-2 的 DDL 门禁抓出）：
--   原版写死 `USE marketing` 且六条 UPDATE 无表存在性守卫。单库档（marketing 一库
--   装全部表）下它碰巧总能跑；但 docker-compose.data.yml 的 MYSQL_DATABASE=marketing
--   会让**每个新建卷**都带一个空的 marketing 库——四库隔离档的卷上，migrate.sh
--   ALL_DBS 走到这个空库时 `UPDATE activity` 直接 ERROR 1146 中断整条迁移链。
--   重写后：去掉 USE（migrate.sh 已按库传参）；逐表 IF EXISTS 守卫——四库档下
--   四张种子表分散在四个库，各自只在装着自己的库里生效；UPDATE 语句逐字未动。
--   已应用过本文件的老卷不受影响（台账按文件名跳过）。
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS _mkt_fix_seed_encoding;
-- DELIMITER 是 mysql 客户端指令（migrate.sh 走的正是它）：过程体内含分号，
-- 不换分隔符的话客户端会在过程体第一个分号处截断语句 → 1064
DELIMITER $$
CREATE PROCEDURE _mkt_fix_seed_encoding()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'activity') THEN
    UPDATE activity
       SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4),
           remark = CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4)
     WHERE activity_no = 'ACT2026001'
       AND name <> '2026 秋季大促';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'coupon_template') THEN
    UPDATE coupon_template
       SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4)
     WHERE template_no IN ('CT2026001', 'CT2026002')
       AND name NOT IN ('5元无门槛券', '满100减20券');
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'seckill_activity') THEN
    UPDATE seckill_activity
       SET item_name = CONVERT(CAST(CONVERT(item_name USING latin1) AS BINARY) USING utf8mb4)
     WHERE activity_no = 'SK2026001'
       AND item_name <> '旗舰手机 秒杀特惠';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'promo_rule') THEN
    -- 规则的中文名有两处：列 name 与 JSON 串里的 "name" 字段，两处都要修。
    -- 守卫按 rule_no + 它自己的目标字面量逐条写死：LIKE '%折%' 这类模糊条件会漏掉
    -- "满200减30"这种既不含"折"也不含"满减"的正确行，把它再 CONVERT 一次就反向损坏了。
    UPDATE promo_rule
       SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4),
           rule_json = CONVERT(CAST(CONVERT(rule_json USING latin1) AS BINARY) USING utf8mb4)
     WHERE rule_no = 'PR2026001' AND name <> '满200减30';

    UPDATE promo_rule
       SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4),
           rule_json = CONVERT(CAST(CONVERT(rule_json USING latin1) AS BINARY) USING utf8mb4)
     WHERE rule_no = 'PR2026002' AND name <> '数码品类8.5折';

    UPDATE promo_rule
       SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4),
           rule_json = CONVERT(CAST(CONVERT(rule_json USING latin1) AS BINARY) USING utf8mb4)
     WHERE rule_no = 'PR2026003' AND name <> '阶梯满减（满300减40/满500减80）';
  END IF;
END$$
DELIMITER ;
CALL _mkt_fix_seed_encoding();
DROP PROCEDURE _mkt_fix_seed_encoding;

-- 原版尾部的四条验证 SELECT 已随重写移除：对不存在的表 SELECT 是 ERROR 1146
-- 而不是空结果集，四库隔离档下它们会把刚修好的迁移链再次打断。要看种子中文
-- 是否可读，对装着对应表的库手工执行同款 SELECT 即可（HEX(name) 以 E7/E5 开头）。
