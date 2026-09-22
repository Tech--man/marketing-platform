-- ============================================================
-- 迁移：bizKey 唯一索引的作用域修正（地雷 B / 地雷 D）
--   适用：本变更之前已建好的 MySQL 卷（含常驻数据卷 mkt-data_mysql-data）
--   新库不需要执行（init 与 init-lite 两份 DDL 已直接是新索引）
--
--   为什么要改：
--   · budget_flow 的 bizKey 由调用方提供（POST /api/activity/{no}/budget/deduct），
--     全局唯一让两个活动复用同一 bizKey 时第二次既不扣款也返回成功；
--     失败回滚 DELETE ... WHERE biz_key=? 还会删掉别的活动已成功的扣款记录。
--   · local_message 的 biz_key 同样全局唯一，而券侧登记的是外部传入的 requestId，
--     只要有人传 "seckill:xxx" 就会撞秒杀消息的键形：券那条被 INSERT IGNORE 静默丢掉，
--     接口返回 ACCEPTED 但券永远不会发。
--
--   执行前必须先清干净在途消息，否则旧行（券侧登记的是裸 requestId）在新代码下
--   永远无法被 confirm（confirm 用的是 grant:<requestId>），会一路重试到 FAILED：
--     SELECT status, COUNT(*) FROM local_message GROUP BY status;   -- 期望只有 CONFIRMED
--   有残留时先跑一轮冒烟或等补偿收敛，再执行本迁移。
-- ============================================================

-- 对每个业务库都要执行一遍（单库档只有 marketing；四库隔离档还有 marketing_activity 等）。
-- 注意：idempotent_record 的 uk_biz_key 保持全局 —— 它的键本身已带场景前缀（BizKey.of），
-- 且同一 requestId 跨服务本来就不该各执行一次，这里的全局唯一是正确的语义。

SET @db := DATABASE();

-- 1) budget_flow：uk_biz_key(biz_key) → uk_activity_biz(activity_no, biz_key)
--    旧索引可能叫 uk_biz_key；重复执行安全（不存在就跳过）。
ALTER TABLE budget_flow DROP INDEX uk_biz_key;
ALTER TABLE budget_flow ADD UNIQUE KEY uk_activity_biz (activity_no, biz_key);

-- 2) local_message：uk_biz_key(biz_key) → uk_topic_biz_key(topic, biz_key)
ALTER TABLE local_message DROP INDEX uk_biz_key;
ALTER TABLE local_message ADD UNIQUE KEY uk_topic_biz_key (topic, biz_key);

-- 3) 券侧历史行的键形变化（裸 requestId → grant:requestId）只影响"尚未确认"的行；
--    已 CONFIRMED 的历史行保持原样，仅作审计留存。若要连历史行一起规整（可选）：
-- UPDATE local_message SET biz_key = CONCAT('grant:', biz_key)
--   WHERE topic = 'MKT_COUPON_GRANT' AND biz_key NOT LIKE 'grant:%';
