-- 券库存原子预扣（防超发 + 单人限领）
-- KEYS[1]: 库存 key（coupon:stock:{templateId}，剩余库存）
-- KEYS[2]: 用户已领 key（coupon:user:{templateId}:{userId}）
-- ARGV[1]: 本次领取数量
-- ARGV[2]: 单人限领数
-- ARGV[3]: 用户计数 key TTL（秒）
-- 返回 1 成功 / 0 库存不足 / -1 超个人限领 / -2 未预热
local stockKey = KEYS[1]
local userKey = KEYS[2]
local qty = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if redis.call('EXISTS', stockKey) == 0 then
    return -2
end
local used = tonumber(redis.call('GET', userKey) or '0')
if used + qty > limit then
    return -1
end
local stock = tonumber(redis.call('GET', stockKey))
if stock < qty then
    return 0
end
redis.call('DECRBY', stockKey, qty)
redis.call('INCRBY', userKey, qty)
redis.call('EXPIRE', userKey, tonumber(ARGV[3]))
return 1
