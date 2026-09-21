-- 券预扣回补（异步落库失败/活动下线回收时使用）
-- KEYS[1]: 库存 key
-- KEYS[2]: 用户已领 key
-- ARGV[1]: 回补数量
-- 返回回补后库存
redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
local used = tonumber(redis.call('GET', KEYS[2]) or '0')
if used and used > 0 then
    redis.call('DECRBY', KEYS[2], math.min(used, tonumber(ARGV[1])))
end
return tonumber(redis.call('GET', KEYS[1]))
