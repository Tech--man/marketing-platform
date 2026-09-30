-- 秒杀原子抢购（分桶定位 + 借桶）
-- KEYS: 活动全部库存分桶 key，顺序即桶号 1..#KEYS（seckill:stock:{activityNo}:{i}）
-- ARGV[1]: userId（Lua 内取模定位目标桶）
-- ARGV[2]: 抢购数量（秒杀恒为 1）
-- 返回: 1..#KEYS = 命中的桶号（回补时需要）；0 = 售罄；-2 = 未预热
local n = #KEYS
local qty = tonumber(ARGV[2])
local uid = math.abs(tonumber(ARGV[1]))
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end
-- 优先命中 hash 定位桶，打散热点
local target = uid % n
if tonumber(redis.call('GET', KEYS[target + 1]) or '0') >= qty then
    redis.call('DECRBY', KEYS[target + 1], qty)
    return target + 1
end
-- 目标桶不足则借桶：从目标桶的下一号起环形扫描（2026-09-29 审查收口）。
-- 原先固定从 1 号桶顺序扫——尾段（多数桶空、余量集中少数桶）恰是竞争最烈时，
-- 所有 miss 的请求第一步都 GET 桶 1，热点回潮到单 key、分桶在最需要的时刻失效，
-- 且每次 miss 伴随 O(n) 顺扫。环形起点把"第一步看谁的邻居"摊开；
-- 覆盖顺序仍是全部桶（含回绕到 target 前一桶），无尾部浪费。
for offset = 1, n - 1 do
    local i = ((target + offset) % n) + 1
    if tonumber(redis.call('GET', KEYS[i]) or '0') >= qty then
        redis.call('DECRBY', KEYS[i], qty)
        return i
    end
end
return 0
