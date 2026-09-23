# ③ 业务管理面与 C 端写入口收口 —— 段内设计

母版：`2026-09-23-admin-console-business-ops-ui-design.md` §6（本文件把它落到可实施的粒度）。
上一段：⑤ 在线配置下发（`2026-09-23-online-config-delivery-design.md`，已交付）。
本文所有 `文件:行` 都在 2026-09-23 的最终产物上重核过（⑤ 之后多处行号已漂，母版的引用别再直接用）。

## 1. 这一段交付什么

一句话：**把"配置类写"从 C 端路径搬进 `/api/admin/**`，且搬进 owning 服务而不是 admin 进程**，
顺带补上今天根本不存在的创建接口（券模板、秒杀活动），并把两处跨进程缺口正面结掉：
审计归属（⑤ §4 交过来的结）与 FULL 下的重预热回执（现在只回 `41010`）。

③ 是四段里唯一动 C 端契约的一段，所以它同时是最容易把 52→72 条基线弄红的一段。

## 2. 搬什么、留什么（逐端点）

**从 C 端搬走**（配置类写，搬完 C 路径不再存在）：

| 现路径 | 证据 | 新路径 |
|---|---|---|
| `POST /api/activity` | `ActivityController.java:38-42` | `POST /api/admin/activities`（owning = activity） |
| `PUT /api/activity/{no}/transition` | 同上 `:50-55` | `POST /api/admin/activities/{no}/transition` |
| `POST /api/discount/rules` | `DiscountController.java:48-66` | `POST /api/admin/discount/rules` |
| `GET /api/discount/rules` | 同上 `:68-` —— 注释自称管理端，却把整套规则 DSL 挂在 C 路径给共享 demo token 读 | `GET /api/admin/discount/rules`（分页 + VO） |

**C 端保留**（运行时读 + 交易写，一条不动）：`calculate`、`grant`、`grab`、`pay`、`consume`、
`budget/deduct`、各 `*/result` 轮询、`usable`、`stock`、`activities`、`participatable`、`gray-hit`。

**净新增能力**（今天只有 SQL 种子，没有任何接口）：券模板创建/编辑、秒杀活动创建/上下线、
活动预算编辑、活动灰度编辑（⑤ 已把真值放进 `activity.gray_percent`/`gray_whitelist`，但写入口还没有）、
库存重置、四个模块的列表分页。

**顺手归一**：`GET /api/seckill/stock/{no}` 对不存在活动返回空数组（`SeckillController.java:60-70`）
→ 改 `40400`。这是 ①② spec §10 的漏网，不另开一段。

**决策：硬切，不留兼容期。** 理由：仓库内 `POST /api/activity` 的调用方只有
`smoke-test.sh` 与 `load-probe.sh`，README 的定位是演示/预览栈，没有任何外部集成方的证据；
留一条双写路径的代价是"两个入口都能改预算"，而那正是地雷 A（DB 改了缓存不生效）的成因。
母版风险 #3 的"若已有外部集成方，需要单独一段做兼容期"—— 目前没有，将来出现时再单独开段。

## 3. 进程归属（母版 §6.0 的落地细节）

路径统一 `/api/admin/` 前缀，但端点由 owning 业务服务实现并发布：

```
/api/admin/activities/**  → marketing-activity:8081（LITE/dev 同为 standalone:8085）
/api/admin/coupon/**      → marketing-coupon:8082
/api/admin/discount/**    → marketing-discount:8083
/api/admin/seckill/**     → marketing-seckill:8084
/api/admin/{auth,users,sessions,audits,cache,config}/** → marketing-admin:8086
```

`AdminAuthFilter` 按 `ADMIN_PREFIX = "/api/admin/"` 判定（`AdminAuthFilter.java:47`），
所以**四条新前缀天然已被双 token 分区与角色粗筛覆盖，①② 的 filter 只剩一处小改**（见 §3.2）。

### 3.1 身份件：一处实现，不做五份

母版建议"形状放 common、各服务一个 30 行实现"。**这一条我改**：common 直接给
`AdminRequestIdentity`（从请求里取出 `AdminPrincipal` + `requireRole(String...)`），
业务侧每个写端点一行 `adminIdentity.require(request, "admin")`。

理由：五份 30 行实现正是本仓库在 `marketing.*` 上等值副本踩过一次的坑（Task #11）；而这个件里
唯一可能漂移的语义（角色字符串、缺凭证如何判）恰好必须五处一致。它不带 Redis 吊销回退
（那是 admin 的 `AdminSessionService` 的事），所以放 common 不引入 Redis 依赖，也不需要 DataSource
—— 与 ⑤ 的 `ConfigValues` 同一装配档位。

### 3.2 母版 §6.0 第四条理由不成立，本段换一种凭证

母版写"业务侧读 `X-Admin-*` 身份头即可（网关对这些头先 remove 后 set，客户端伪造不到）"。
**"客户端伪造不到"只对经网关的请求成立**，而 ③ 恰恰把这些端口变成了**改钱**的入口：

- FULL 进程形态：seckill 监听 `*:8084`（实测 `lsof`），同机任何人 `curl -H 'X-Admin-Role: admin'`
  就能改库存；
- LITE 服役档：`standalone` 的 8085 **按设计发布到宿主机**（`AdminIdentityService` 的类注释自己写着这一点），
  而 LITE 是常态服役档 —— 这条路径不是异常，是日常。

所以业务侧不能把裸头当授权。定稿：**网关把已验签的 token 透传成 `X-Admin-Token`，业务侧自己验签**：

1. 网关 `AdminAuthFilter.pass` 在 remove 列表里加 `X-Admin-Token`，并把它 set 成自己刚验过的那个 token
   （`Authorization` 照旧剥掉，下游不需要原始 bearer）；
2. 业务侧 `AdminRequestIdentity`：`X-Admin-Token` → common 的 `AdminTokenCodec.verify(token, now)`
   → 从 `AdminClaims` 取 uid/username/role（**头里的 role 一律不采信**）；缺头/签错/过期 → `40100`；
   角色不够 → `40300`。`AdminTokenCodec` 本来就是 common 里的普通类
   （`common/security/AdminTokenCodec.java`，HS256 手写、无 JJWT），业务侧零新密码学；
3. 吊销与"整号作废时刻"仍只在网关与 admin 判（网关已判，业务侧不必重复）；
   代价是：一枚被吊销的 token 在直连业务端口时仍可写到过期为止 —— 这条写进 §10 的已知边界，
   而不是靠给业务侧再装一套 Redis 吊销来掩盖。

**代价（必须一起做的部署改动）**：`ADMIN_JWT_SECRET` 要从"gateway + admin 两进程"扩到六个进程
（`start-all.sh`、`start-dev.sh`、`docker-compose.preview.yml`、`docker-compose.full-app.yml` 的 `&app-env` 锚点）。
缺失时的行为沿用 admin 现有口径：**服务拒绝启动**，而不是"签得出但没人能验"或"业务写免鉴权"。

这把母版那句"网关已经判过"换成了一句可测的话：**任何进程的后台写端点，都要能在没有网关的情况下
被独立判为 40100**（§9 的验收里有这条断言，直连端口打，不带 token）。

### 3.3 网关侧要做的两件事

1. local 与 nacos 两套 profile 各加四条路由（现结构 `application.yml:26-79` 与 `:112-128`），
   每条都要进 `rate-limit` map（`application.yml:60-`，事实 #3 同源：**不在 map 里＝完全不限流**）。
   后台登录口的 BCrypt 单次 50-100ms 这条理由对四条新前缀同样成立 —— 它们也都是"低频但不免费"的写；
   LITE 下四条与 `admin-route` 同指 standalone:8085；
2. `AdminAuthFilter.pass` 的 remove 列表加 `X-Admin-Token` 并 set 成刚验过的 token（§3.2）。
   这是 ①② 那套 filter 在本段唯一的改动。

## 4. 正面结掉两个跨进程缺口

### 4.1 审计归属（⑤ §4 交过来的结）

三条要求在 FULL 分进程下互相冲突：配置类写只走 `/api/admin/**`（母版 §4）、业务写端点长在
owning 服务（§6.0）、所有写要 `AuditSink` 记 before/after（§6.2）—— 而 `admin_audit_log` 与
`AuditSink` 都在 `marketing-admin`（`admin/audit/AuditSink.java:9`），业务进程既没那张表的
DataSource 也不该有（每服务一库档它连不上）。

**定稿：owning 服务把审计载荷投进 Redis Stream `mkt:audit:pending`，由 admin 定时 drain 落表。**

- 生产者（owning 服务，写端点成功后）：`XADD mkt:audit:pending * <单字段 payload=JSON>`，
  JSON 形状与 admin 的 `AuditRecord` 逐字段对齐（action/target/actor/role/ip/before/after/ts），
  载荷类放 common（业务侧不 import admin）；
- 消费者（admin）：每 5s `XREADGROUP` 一批（≤500）落 `admin_audit_log` 后 `XACK`，
  沿用本仓库 LITE MQ 已有的 consumer group + XDEL 手法，**连"跑完 XLEN 恒 0"这条断言口径都是现成的**；
- 上界 `XADD ... MAXLEN ~ 100000`，不给 TTL。

为什么不用 ⑤ §4 记的候选 `LPUSH + LTRIM + TTL`：**TTL 淘汰等于静默丢审计**，而"静默不一致是本仓库
最贵的一类 bug"正是 ⑤ 立下的口径；Stream 的 PEL 与 `XLEN` 可被 ④ 直接报成 pending 数，
Redis 被清空这件事也仍然看得见（键没了 vs 消费组没了，是两种诊断）。
`AuditService.record` 的"写失败只 warn"语义与 `AuditSink` 不下放 common 这两条不变（母版 §6.2 的理由仍成立）。

### 4.2 FULL 下的重预热回执（把 41010 变成真路径）

`AdminCacheController` 现在在 FULL 分进程下回 `41010 本形态不适用`（⑤ T9 迁的码），
因为 admin 进程里 `registry.types()` 为空。③ 把它补成跨进程闭环：

1. admin 收 `POST /api/admin/cache/reheat?type=&key=&force=true`，本进程有该 reheater → **同步执行并返回**
   （保住 `smoke-test.sh:324` 那条 LITE 断言不动）；
2. 本进程没有 → `XADD mkt:reheat:pending`，返回 `Result` 带 `status=DISPATCHED` 与回执等待提示，
   owning 服务侧的 `ReheatPoller` 取到后**在自己进程执行**，把结果 `XADD mkt:reheat:ack`；
3. admin 提供 `GET /api/admin/cache/reheat/ack?id=` 读回执（PENDING / DONE+before/after / FAILED）。

这条沿用 ⑤ 的同一把轮询骨架（daemon `ScheduledExecutorService` + `ConfigSyncer` 让位标记），
**不新增 admin→业务服务的入站端点，也不为此加一套新鉴权**（母版 §10 的"不做"依然成立）。
业务侧写端点自己改预算/库存时**不走这条路**：DB 写与 `reheat(force=true)` 在同一 service 方法、
同一事务内完成（§5），这条路只服务"运维手动刷一把"。

## 5. 与预扣缓存的强制联动（防地雷 A 复发）

依据没变：上线走 `ActivityService.java:56` 的 `budgetService.warmIfAbsent`（SETNX），
所以**改了 `budget_amount` 而不 DEL 重建，缓存永远不会生效**。因此：

1. 库存/预算/分桶的写方法只允许落在 owning 模块的 service，DB 写与 `reheat(force=true)` 同方法内完成；
   admin 侧不得"写完 DB 再另外调一次刷新"；
2. 每处一个 Mockito 断言 `verify(reheater).reheat(key, true)` —— 注释掉那行就必须红（⑤ 的变异检查手法）；
3. 字段编辑**不套幂等表**，改用仓库里已就位却从未被用的乐观锁：`@Version` 现在标在
   `ActivityEntity.java:40`、`SeckillActivityEntity.java:42`、`SeckillOrderEntity.java:40`、
   **`CouponTemplateEntity.java:42`、`PromoRuleEntity.java:39`**（母版只列了三条，实际四张可编辑表都有），
   `OptimisticLockerInnerInterceptor` 在四个模块 + standalone 五份 `MybatisPlusConfig` 里都已装配。
   冲突返回 **`41008 数据已被他人修改`**（⑤ 已建这个码，本段第一次真的用它）；
4. 动作型端点（状态流转、重投、重预热）用 `IdempotentExecutor` +
   `BizKey.of("admin", resourceType + ":" + id, requestId)` —— 命名空间与 ⑤ 之前定的 bizKey 口径一致。

## 6. 契约

- 列表统一 `Result<PageResult<XxxView>>`（复用 `PageResult`/`PageQuery`）；**VO 只在 admin 侧强制**，
  C 端返回实体的形状不动 —— 免得把 72 条基线卷进无关改动；
- 写端点的请求体一律显式 DTO + `@Valid`，错误走 `40000`（⑤ 已经立了"body 解析不了也是 40000"）；
- 角色细筛：创建/编辑/上下线/库存 = `admin`；只读列表 = `admin|operator|viewer`。
  与 ⑤ 的阈值端点同一把尺子（operator 可写运维，但改不动业务配置与阈值）；
- 每个写端点都要留审计（§4.1），LITE 与 FULL 走同一条代码路径，只差在"本地落表"还是"投 Stream"。

## 7. 冲击面（必须一起改，否则基线红）

- `scripts/reset-demo-data.sh`：现在靠 `docker exec redis-cli` + 直连 MySQL + **重启容器/进程**三条形态分支
  来重建分桶（`:29-91`，全脚本最脆的一段，也是"换库布局要先重置分桶"那类隐性知识的载体）。
  ③ 之后改成：登录换 admin token → `PUT /api/admin/seckill/SK2026001/stock`，分桶由 owning 服务自己在
  同事务里重建 → **三条形态分支全删**。冷栈守卫（非零退出）保留。
- `scripts/smoke-test.sh`：链路 0 的创建/流转换路径换 token（C token 打 `/api/admin/**` 必 `40100`，
  这两条已有断言在同脚本 `:291-292`），登录提前到脚本头部与链路 4/5 共用一次；
  新增**链路 6**（母版 §9）：C 端 token 打已搬走的 `POST /api/activity` 必 `404/405`、
  admin token 打 C 端交易路径仍通、审计 Stream 跑完 `XLEN` 归 0、`41008` 冲突真能撞出来。
- `scripts/load-probe.sh`：只有 `:18` 的提示语变。
- `README.md`：API 表增删若干行、"写入口矩阵"新增一节、覆盖矩阵与计数刷新。

## 8. 批次

T1 common 身份件（验签式）+ 审计载荷 + Stream 键名；T2 网关四前缀路由与限流（两套 profile）
+ `X-Admin-Token` 透传 + `ADMIN_JWT_SECRET` 扩到六进程（四套入口脚本/编排）；
T3 activity（搬两条 + 列表 + 预算/灰度编辑 + reheat 同事务）；T4 discount（规则读写搬家 + 编辑）；
T5 coupon（券模板创建/编辑/列表 —— 净新增）；T6 seckill（活动创建/上下线 + 库存编辑 + stock 404 归一）；
T7 审计 Stream 投递 + admin drain；T8 重预热回执（41010 → DISPATCHED/ack）；
T9 C 端写路径删除与三处脚本冲击面；T10 五形态复跑 + README 收口。

## 9. 验收

- 单测 +≈24：每端点一条"没调 `reheat(key,true)` 就红"、一条 `@Version` 冲突出 `41008`、
  一条 read-only/operator 写 `40300`、**一条"只有 `X-Admin-Role: admin` 裸头而没有 token 时必须 40100"**
  （§3.2 的那条边界，变异检查：把 `AdminRequestIdentity` 退回读头，这条必须红）、
  审计载荷编解码往返、drain 的 XACK 与 MAXLEN 行为、
  `41010` 在 LITE 仍同步、FULL 走 DISPATCHED。关键断言逐条做变异检查。
- smoke 链路 6 新断言 + 基线 **72 条在迁移后必须同时全绿**（母版 §9 的约束按 ⑤ 之后的新基线读）。
- 五形态复跑：LITE 容器 / FULL 进程 / FULL 容器 / dev / 每服务一库，每档 72+ 全绿；
  LITE 内存继续 `docker stats` 复核（standalone 超 640 MiB 才动 `mem_limit`，⑤ 复跑区间 517-599）。
- 每服务一库档跑冒烟记得 `MYSQL_DB=marketing_activity`（⑤ 执行记录里的这条口径同样适用于本段的新断言）。

## 9.1 已知边界（写在这里，不用再加一套机制去掩盖）

被吊销的会话（登出/改密/停用）在**直连业务端口**时仍可写到 token 自然过期：吊销判定在网关与 admin，
业务侧只做无状态验签。经网关的路径不受影响（网关查吊销位与整号作废时刻）。
要把这个窗口也关掉，就得给四个业务进程各装一套 Redis 吊销回退 —— 那是第五份等值实现，
代价大于收益；正确做法是把业务端口留在不可信网络之外（README 已立此规）。

## 10. 不做

admin 直接持有业务库 DataSource（§3 的归属一旦破了，§5 的约束就形同废除）；
把 `AuditSink` 提升到 common；给业务服务加 admin→业务的入站 HTTP 调用；
C 端旧写路径的兼容期/双写；把列表 VO 反推到 C 端响应。
