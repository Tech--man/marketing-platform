-- 预算退款的差额封顶原子化（2026-10-01 审计 P2-4）
-- KEYS[1]: activity:budget:{activityNo}
-- ARGV[1]: 请求退款金额（分）
-- ARGV[2]: 退后期望余额（分）——调用方按对账公式算好传入（此刻 REFUND 流水已落，
--           公式已含本笔）
-- 返回：实退金额（分）；-1 = 预算键不存在（调用方只落流水，公式重建自愈）
--
-- 原 Java 实现是 GET → 算 min/max → INCRBY 三步 check-then-act：并发两笔退款
-- 各自读到 stale 的 current、各自按同一旧基线差额 INCRBY——Redis 侧同一基线退
-- 两次，方向偏"多退"。判定收进一次 EVAL 后，第二笔看到的 current 是第一笔
-- INCRBY 之后的新值，max(0, expected-current) 自然收敛。封顶公式与 W2.2 语义
-- 逐字一致：实退 = min(金额, max(0, 期望-当前))，Redis 虚高（孤儿流水/漂移）
-- 时退 0，只留流水由对账/重预热收敛——绝不凭空造钱。
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
    return -1
end
local amount = tonumber(ARGV[1])
local expectedAfter = tonumber(ARGV[2])
local current = tonumber(redis.call('GET', KEYS[1])) or 0
local effective = math.min(amount, math.max(0, expectedAfter - current))
if effective > 0 then
    redis.call('INCRBY', KEYS[1], effective)
end
return effective
