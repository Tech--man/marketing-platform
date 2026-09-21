-- 滑动窗口限流（ZSET 实现）
-- KEYS[1]: 窗口 key
-- ARGV[1]: 当前时间毫秒
-- ARGV[2]: 窗口大小毫秒
-- ARGV[3]: 窗口内阈值
-- ARGV[4]: 成员唯一标识
-- 返回 1 放行，0 限流
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2]))
local count = redis.call('ZCARD', KEYS[1])
if count >= tonumber(ARGV[3]) then
    return 0
end
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[2] * 2)
return 1
