-- 活动闸门键的版本化 CAS 写入（W2.1，2026-09-30 第二轮复审；W2.4 扩展到灰度键）
-- KEYS[1]: activity:gate:status:{no} 或 activity:gate:gray:{no}
-- ARGV[1]: 新值（形状 status|version 或 percent|w1,w2|version）
-- ARGV[2]: 新 version（十进制整数串）
-- 返回 1=已写入，0=被拒（键上 version 更新，写入者持有旧快照）
--
-- 语义：仅当新 version >= 键内 version 才覆盖。
-- 版本段 = 值里**最后一个** | 之后的部分（W2.4）：状态键只有两段，首尾一致；
-- 灰度键的白名单段（uid CSV）本身不含 |，但值有三段，取首段之后的整串会
-- 解析不出数字——取尾段对两种键形状都正确。
-- 旧形状（裸 status / 无版本的 percent|w1,w2）视为 version=0——迁移期 activity
-- 新代码写的带版本值天然胜出；反向（新代码读到旧代码写的裸值）同样按 0 处理。
-- 键不存在直接写入（首发布/清库后重建）。
local cur = redis.call('GET', KEYS[1])
if not cur then
    redis.call('SET', KEYS[1], ARGV[1])
    return 1
end
local sep = 0
local pos = string.find(cur, '|', 1, true)
while pos do
    sep = pos
    pos = string.find(cur, '|', pos + 1, true)
end
local curVer = 0
if sep > 0 then
    curVer = tonumber(string.sub(cur, sep + 1)) or 0
end
local newVer = tonumber(ARGV[2]) or 0
if newVer >= curVer then
    redis.call('SET', KEYS[1], ARGV[1])
    return 1
end
return 0
