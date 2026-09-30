-- 券预扣回补（异步落库失败/活动下线回收时使用）
-- KEYS[1]: 库存 key
-- KEYS[2]: 用户已领 key
-- ARGV[1]: 回补数量
-- 返回：回补后库存；-1 = 库存键不存在（W2.2 守卫：拒绝在无键时回补）
--
-- W2.2（2026-09-30 第二轮复审）：原实现无条件 INCRBY——键缺失（Redis 重启无持久化、
-- overwrite 的 DEL→SET 窗口、reheat(force) 完成后一笔在途失败回补到达）时会凭空
-- 创建 coupon:stock:{id}=qty 的幽灵键，模板可用库存凭空多出 qty 张。键不存在时
-- 库存与用户计数两边都不动，残余差值由 ④ coupon mismatch 恒等式暴露。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
local used = tonumber(redis.call('GET', KEYS[2]) or '0')
if used and used > 0 then
    redis.call('DECRBY', KEYS[2], math.min(used, tonumber(ARGV[1])))
end
return tonumber(redis.call('GET', KEYS[1]))
