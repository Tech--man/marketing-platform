-- 预算原子扣减：
-- KEYS[1]: 预算 key（activity:budget:{activityNo}，值为剩余预算"分"）
-- ARGV[1]: 扣减金额（分）
-- 返回 1 成功 / 0 余额不足 / -1 key 未预热
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
    return -1
end
local remain = tonumber(redis.call('GET', KEYS[1]))
local amount = tonumber(ARGV[1])
if remain < amount then
    return 0
end
redis.call('DECRBY', KEYS[1], amount)
return 1
