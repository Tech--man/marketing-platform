-- 活动闸门键的版本化 CAS 写入（W2.1，2026-09-30 第二轮复审）
-- KEYS[1]: activity:gate:status:{no}
-- ARGV[1]: 新值（形状 status|version）
-- ARGV[2]: 新 version（十进制整数串）
-- 返回 1=已写入，0=被拒（键上 version 更新，写入者持有旧快照）
--
-- 语义：仅当新 version >= 键内 version 才覆盖。
-- 旧形状（裸 status、无 | 段）视为 version=0——迁移期 activity 新代码写的带版本值
-- 天然胜出；反向（新代码读到旧代码写的裸值）同样按 0 处理，任何版本都能覆盖。
-- 键不存在直接写入（首发布/清库后重建）。
local cur = redis.call('GET', KEYS[1])
if not cur then
    redis.call('SET', KEYS[1], ARGV[1])
    return 1
end
local sep = string.find(cur, '|', 1, true)
local curVer = 0
if sep then
    curVer = tonumber(string.sub(cur, sep + 1)) or 0
end
local newVer = tonumber(ARGV[2]) or 0
if newVer >= curVer then
    redis.call('SET', KEYS[1], ARGV[1])
    return 1
end
return 0
