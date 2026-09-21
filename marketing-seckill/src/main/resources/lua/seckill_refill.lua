-- 秒杀库存回补（超时未支付取消后，把占用还回原桶）
-- KEYS: 活动全部库存分桶 key（与抢购一致，用于存在性校验）
-- ARGV[1]: 桶号（1-based，抢购时命中的桶）
-- ARGV[2]: 回补数量
-- 返回 1 成功；-2 桶 key 已失效（活动过期清理后不再回补，交给 DB 校准）
local bucket = tonumber(ARGV[1])
local qty = tonumber(ARGV[2])
if bucket < 1 or bucket > #KEYS then
    return -2
end
if redis.call('EXISTS', KEYS[bucket]) == 0 then
    return -2
end
redis.call('INCRBY', KEYS[bucket], qty)
return 1
