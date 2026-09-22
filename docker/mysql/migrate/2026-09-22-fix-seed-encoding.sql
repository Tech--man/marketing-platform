-- ============================================================
-- 修复：init 文件里的中文种子被按 latin1 客户端连接写坏（双重编码）
-- 日期：2026-09-22
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

SET NAMES utf8mb4;

-- 单库档在 marketing，四库档在 marketing_activity/_coupon/_seckill/_discount：
-- 下面按当前库执行（先 USE 成你实际用的库；LITE/dev/FULL 容器默认都是 marketing）
USE marketing;

UPDATE activity
   SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4),
       remark = CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4)
 WHERE activity_no = 'ACT2026001'
   AND name <> '2026 秋季大促';

UPDATE coupon_template
   SET name = CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4)
 WHERE template_no IN ('CT2026001', 'CT2026002')
   AND name NOT IN ('5元无门槛券', '满100减20券');

UPDATE seckill_activity
   SET item_name = CONVERT(CAST(CONVERT(item_name USING latin1) AS BINARY) USING utf8mb4)
 WHERE activity_no = 'SK2026001'
   AND item_name <> '旗舰手机 秒杀特惠';

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

-- 验证（跑完应看到可读中文，且 HEX(name) 以 E7/E5 开头而不是 C3A7）
SELECT activity_no, name, remark FROM activity WHERE activity_no = 'ACT2026001';
SELECT template_no, name FROM coupon_template ORDER BY template_no;
SELECT rule_no, name, LEFT(rule_json, 46) AS json_head FROM promo_rule ORDER BY rule_no;
SELECT activity_no, item_name FROM seckill_activity WHERE activity_no = 'SK2026001';
