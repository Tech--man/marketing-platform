-- 券预扣回补（异步落库失败/活动下线回收时使用）
-- KEYS[1]: 库存 key
-- KEYS[2]: 用户已领 key
-- KEYS[3]: 回补去重 key（coupon:rollback:{requestId}，调用方按"这次预扣的身份"生成）
-- ARGV[1]: 回补数量
-- ARGV[2]: 去重 key TTL（秒）
-- 返回：回补后库存；-1 = 库存键不存在；-2 = 该 token 已回补过（幂等重放，返回现值）
--
-- W2.2（2026-09-30 第二轮复审）：原实现无条件 INCRBY——键缺失（Redis 重启无持久化、
-- overwrite 的 DEL→SET 窗口、reheat(force) 完成后一笔在途失败回补到达）时会凭空
-- 创建 coupon:stock:{id}=qty 的幽灵键，模板可用库存凭空多出 qty 张。键不存在时
-- 库存与用户计数两边都不动，残余差值由 ④ coupon mismatch 恒等式暴露。
--
-- 幂等去重（2026-10-01 审计 P3）：补偿链路（recordIfAbsent 失败回滚、!recorded
-- 归还多余预扣、消费端终态失败回补）共用同一把 Lua，同一次预扣的回补可能被
-- 多条补偿路径重复触发（如重试 + 手工 redrive）——原实现每次都真 INCRBY，
-- 同一张券的库存被还两份（窄双退）。去重标记只在"真的动了账"时落下：
-- 键缺失（-1）不占 token，之后的合法重试仍可回补。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('SET', KEYS[3], '1', 'EX', tonumber(ARGV[2]), 'NX') == false then
    return -2
end
redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
local used = tonumber(redis.call('GET', KEYS[2]) or '0')
if used and used > 0 then
    redis.call('DECRBY', KEYS[2], math.min(used, tonumber(ARGV[1])))
end
return tonumber(redis.call('GET', KEYS[1]))
