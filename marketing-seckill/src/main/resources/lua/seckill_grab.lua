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
-- 目标桶不足则顺序借桶（避免尾部库存浪费）
for i = 1, n do
    if i ~= target + 1 and tonumber(redis.call('GET', KEYS[i]) or '0') >= qty then
        redis.call('DECRBY', KEYS[i], qty)
        return i
    end
end
return 0
