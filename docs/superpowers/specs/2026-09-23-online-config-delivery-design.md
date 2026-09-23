# ⑤ 在线配置下发 段内 spec（DB 真值 + 自包含 Redis 快照）

- 日期：2026-09-23
- 上游：`2026-09-23-admin-console-business-ops-ui-design.md` §5（母版）、`2026-09-22-admin-console-foundation-auth-design.md`（①② 已交付）
- 实施计划：`docs/superpowers/plans/2026-09-23-online-config-delivery.md`
- 状态：待评审

## 1. 这一段交付什么

改限流阈值和灰度比例**不再需要改 yml 重启**：真值在 MySQL，各进程经一份自包含 Redis 快照在 ≤5s 内收敛；快照缺失或值非法时逐条退回该进程的 yml/代码出厂值。同时把"哪些参数可以在线改"变成**代码自述**（SPI），后台只渲染与校验，不持有第二套清单。

不做（母版已定，这里只重申与实施相关的三条）：不引 nacos 配置中心、不做 `seckill.buckets` 在线化、不加 admin→业务服务的新入站端点。

## 2. 契约

### 2.1 表

```
admin_config(id, cfg_key VARCHAR(64), form VARCHAR(16), cfg_value VARCHAR(255),
             version BIGINT, updated_by VARCHAR(64), remark VARCHAR(255),
             create_time, update_time, UNIQUE KEY uk_key_form (cfg_key, form))
```

`form ∈ GLOBAL | LITE | FULL | DEV`。**删行 = 恢复出厂**，不写回原值。表与 `admin_user` 同库（单库档在 `marketing`，隔离档在 `marketing_admin`）。

`activity` 新增两列：`gray_percent INT NULL`、`gray_whitelist VARCHAR(255) NULL`（CSV）。NULL = 未配灰度 = 全量放行（保持 `smoke-test.sh:61` 的既有断言语义）。

### 2.2 Redis

```
mkt:cfg:snapshot:{form}  STRING(JSON) {version, generatedAt, entries:{key:{value,type,defVer}}}
mkt:cfg:version:{form}   STRING(INT)      读方只为"变了才取快照"而存在
mkt:cfg:seq              INCR             全局单调序号，同时写进行 version 列与快照 version
mkt:cfg:schema:{service} STRING(JSON)     该服务自述的可改参数清单
```

单键全量快照（不是每参数一键）：网关无库，载荷必须自包含；多键有半应用窗口，一次 `GET` 是原子的。

### 2.3 解析与回退

- 生效值 = `当前 form 的行 > GLOBAL 的行 > 调用方给的出厂值`，实现在唯一一处 `ConfigMerge.merge(ownForm, rows)`。
- `DEPLOY_FORM` 未设置或取值不认识 → 按 `GLOBAL` 解析并 WARN 一次。**不部署新 env 就等于没有这套机制**，行为与今天逐字节一致。
- 逐条校验：未声明的键忽略；声明了但类型不符/越界的键忽略并记 degraded 计数 + WARN。快照 JSON 坏了 → 整份按空快照应用（**退回出厂值，不是沿用旧快照**）：静默抱着陈旧值是本项目最贵的一类 bug，而阈值退回 yml 只会"放行偏宽或偏紧"，不会起不来。
- 写路径顺序固定：`INCR seq` → 事务写行 → 提交 → `SET snapshot` → `SET version`。后两步任一失败返回 `41009 配置已落库但未广播`，并提供幂等的"重新广播"动作。

## 3. 对母版的五条偏离（都在动笔或落地时对着代码核过）

| # | 母版原文 | 改成什么 | 为什么必须改 |
|---|---|---|---|
| 1 | §5.1 `version` 由写路径在事务内取 `MAX(version)+1` | 取 `INCR mkt:cfg:seq` | `MAX+1` 只在单进程内唯一。两个管理员并发写（或 GLOBAL 与 LITE 各写一行）会拿到同一个 version，读方按 version 判"没变"就不再取第二份快照——**陈旧值静默生效**，正是母版风险 #2 要防的事。`INCR` 原子且严格递增；Redis 不可用时本来也广播不出去，同一支路走 41009，不新增失败模式 |
| 2 | §5.3 快照键 `mkt:cfg:snapshot:{form}` | 发布目标取固定集合 `{GLOBAL, LITE, FULL, DEV}`，合并后为空的形态 **DEL** 两个键 | 按"表里出现过的 form"发布有个洞：把 LITE 的最后几行删干净后，LITE 快照会停留在旧值上，读方永远收不到"恢复出厂"。固定集合 + 空则删键，才让 DELETE 真的等于出厂 |
| 3 | §5.4 灰度"Redis 只当变更通知，冷启动回源 DB 重建"；§5.5 灰度在 activity 声明为一条 `ConfigDefinition` | 灰度**不进 `admin_config`、也不走 Redis 通知**：activity 自己每 5s 回源 DB 重建 `Map<activityNo, GrayRule>`；⑤ 不建灰度写端点 | 两个原因。(a) 通知键要有人 bump，而写端点必须长在 activity（§6.0），⑤ 里没有 activity 的后台写端点；(b) 更根本的是 §6.0 与 §6.2 在⑤落地时自相矛盾——见第 4 节，⑤ 不想在缺审计件的情况下开第一个业务侧后台写端点。回源 DB 反而把"Redis 被清"这一整类风险消掉了（灰度不再依赖 Redis），代价是 5s 收敛窗口与每 5s 一条 `WHERE gray_percent IS NOT NULL` 的小查询（种子 60 行，可忽略） |
| 4 | §5.5 声明清单含"灰度在 activity" | ⑤ 的 provider 只有三个：网关（5 条限流）、discount（2）、seckill（3） | 灰度不再是 `admin_config` 键，声明它就没有校验对象。同时**不声明未接线的键**：`ConfigDefinitionProvider` 的每条都必须真的被某处 `configValues.intOr(...)` 消费，否则后台就是一个"点了没反应"的按钮（母版 §4 的"与其偷偷做个只在一档能用的按钮，不如显式报错"同源） |
| 5 | 母版事实 #8 说"网关只有 reactive 模板" | 那句讲的是**它用什么**，不是 classpath 里缺什么：`spring-boot-starter-data-redis-reactive` 会带进 spring-data-redis 核心，于是网关里**确实存在 `StringRedisTemplate` bean**（实测：装配条件按类型挡住才算数）。补一个 `ConfigSyncer` 标记接口，网关的 reactive 同步器实现它，common 的阻塞轮询器见到任一实现就让位 | 少了这个标记，网关里会同时跑"阻塞轮询线程 + reactive 轮询"两套节拍喂同一份生效值：功能看起来正常，但一次刷新有两条竞态路径，且白占一条 Lettuce 阻塞连接。这类"两份都对的东西"正是最难查的那类 |

## 4. 遗留给 ③ 的一处矛盾（现在就有解，但不在 ⑤ 做）

母版 §4 要求"配置类写只走 `/api/admin/**`"、§6.0 要求业务写端点长在 owning 服务、§6.2 又要求"所有写走 `AuditSink` 记 before/after"。三条在 FULL 分进程下不可能同时成立：`AuditSink` 与 `admin_audit_log` 都在 `marketing-admin`，而 activity/coupon/discount/seckill 进程既没有那张表的 DataSource，也不该有（每服务一库档它连不上）。⑤ 靠偏离 #3 避开了这个结（本段不开业务侧写端点）。

③ 必须正面解决，已有倾向：owning 服务把审计记录 `LPUSH` 进 `mkt:audit:pending`（定长 `LTRIM` + TTL，防 admin 长时间不消费把 Redis 撑大），由 `marketing-admin` 定时 drain 落 `admin_audit_log`。这样 before/after 仍由唯一知道它们的进程给出，而表的所有权不下放。这条已在 ③ 的段内 spec 定稿（`2026-09-23-admin-business-console-design.md` §4.1）：**改用 Redis Stream
`mkt:audit:pending` + consumer group drain，而不是这里候选的 `LPUSH + LTRIM + TTL`**——TTL 淘汰等于
静默丢审计，与 ⑤ 自己立下的"静默不一致最贵"直接冲突。不接受"先不落审计"这条仍然成立。

## 5. 生效路径（逐参数）

| 键 | 声明方 | 消费点 | 缺值时 |
|---|---|---|---|
| `gateway.ratelimit.{activity,coupon,discount,seckill,admin}-route.limit` | 网关 | `RateLimitFilter` 经 `RateRuleResolver` | 该路由退回 yml `RL_*` 出厂值；`window-seconds` 恒取 yml |
| `discount.calc-timeout-ms` | discount | `DiscountCalcService:63` | 退回 `DiscountProperties` 默认 50 |
| `discount.max-rules-per-order` | discount | `PromoEngine:46` | 退回默认 5 |
| `seckill.token-ttl-seconds` | seckill | `SeckillRuntimeConfig` ← `SeckillStockService:152,165` | 退回 600 |
| `seckill.pay-timeout-seconds` | seckill | `SeckillRuntimeConfig` ← `SeckillTimeoutJob:50` | 退回 300 |
| `seckill.bought-mark-ttl-seconds` | seckill | `SeckillRuntimeConfig` ← `SeckillStockService:64,86,105` | 退回 86400 |

路由不在 yml `rate-limit` map 里仍然完全不限流（`RateLimitFilter:55-58` 的现状不改，⑤ 只改"在 map 里时 limit 从哪来"）。

## 6. 验收

- **单测 +≈24**（母版估 ≈14，实施拆细后按实际条数为准）：合并优先级/未知键忽略/越界忽略/坏 JSON 退出厂/poller 版本比对与 Redis 异常保持现值/schema registry 重复键启动失败/发布顺序与 41009/未声明键拒写/删除即出厂/灰度 CSV 解析与 percent=0/在线值优先于 yml。每条关键断言做变异检查。
- **smoke 新增链路 5（10 条）**：改限流不重启生效、DEL 快照键后退回 yml 且仍服务、越界值被拒、未声明键被拒、恢复出厂回默认、GLOBAL 与 LITE 互不串、灰度 SQL 改 0 后 ≤8s 不再命中、删掉灰度 Redis 键后仍不是全量、`41010` 迁移后的链路 4 两条。
- **五形态复跑**（母版 §9）：LITE 容器 / FULL 进程 / FULL 容器 / dev / 每服务一库档，每档 `DEPLOY_FORM` 取值与快照键一致性实测；LITE 内存继续 `docker stats` 复核（standalone 超 640 MiB 才动 `mem_limit`，今天 529-599 MiB）。
- 基线：**现有 52 条断言全绿**，链路 4 只改 41000→41010 与一处文案 needle。

## 7. 风险与兜底

1. **分形态阈值解析错 = 把 FULL 的 1000/s 灌进单机 LITE**：`ConfigMerge` 是唯一实现处 + 逐条校验 + 未知 form 退回 GLOBAL + 链路 5 直接断言"两档读不到对方的值"。
2. **已落库未广播**：`41009` + 重新广播；后续 ④ 的期望/实际对比页负责暴露。
3. **网关多一条常驻轮询**：单进程一个 daemon 线程、每 5s 两次 Redis GET，且读的是常量键；无库、无阻塞客户端（事实 #8 守住）。
4. **⑤ 改了 `GrayService` 的配置来源**：yml `marketing.gray` 两块（`marketing-activity/src/main/resources/application.yml:49-53`、`marketing-standalone/src/main/resources/application.yml:53-56`）删除后，ACT2026001 的 percent=100 必须由种子列给，否则 `smoke-test.sh:63-64` 立刻红——这一步单独成一个任务，就是为了能被单独审。
