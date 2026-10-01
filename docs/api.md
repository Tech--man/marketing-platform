# API 速查与写入口矩阵

> 本文拆自 README 的对应章节（2026-10-01 拆分）。文中"见第 X 节"的互引仍按原 README 编号：一/二节与三节速览在 README；三节详解在 docs/deployment.md；四节在 docs/core-flows.md；五节在 docs/api.md；六节在 docs/testing.md；七/八节在 docs/structure.md。

## 五、API 速查（经网关 8090；C 端需登录换来的 `Authorization: Bearer <accessToken>`，后台需 admin token）

> **HTTP 状态码契约（2026-10-01 审计 P1-7 补记）**：业务侧对 `40100/40101/40102 → 401`、
> `40300 → 403`、`42900 → 429` 映射**真实 HTTP 状态**（`GlobalExceptionHandler` 以
> ResponseEntity 携带；网关鉴权/限流层同口径）；其余业务错（40000/40400/41000…）照旧
> `HTTP 200 + body.code`。**body.code 是唯一的前端契约，前端先解析 body 再看状态**
> （h5 `client.js` 的 `payload ? payload.code : status*100`），因此该映射对存量前端零影响；
> 它服务的是 `curl -f` 式脚本、按状态码重试/熔断的外部调用方与 LB 健康判定。对外变更
> 说明：**任何把 HTTP 200 当成功判定的旧集成，在 401/403/429 三段会开始看到非 200**。
> 归属说明（复审 N-10 修正）：冒烟链路 4 的 `^401$` 断言钉的是**网关侧**拒绝状态
> （`/api/admin/users` 无凭证在网关 `AdminAuthFilter` 就被 401，根本到不了 MVC
> advice）；**进程内 advice 的状态映射**由 `ActivityControllerTest` 的
> `status().isUnauthorized()` 钉住（MockMvc 直打控制器）——Boot 3.4 渲染管道把
> `setStatus` 盖回 200 的那类回归（Boot 3.4 升级窗口实测踩过，见 git log
> "审查收口第六/七批"两段提交的教训记录）走的是后者这条路，
> 排障别找错现场。

| Method | Path | 说明 |
|---|---|---|
| GET | /api/activity/{no}/participatable · /gray-hit?userId= | 可参与校验 · 灰度命中判断 |
| POST | /api/activity/{no}/budget/deduct · GET /budget/remain | 预算扣减 / 实时余额。幂等键作用域是 **(活动, bizKey)**，同一 bizKey 用在两个活动上是两次真扣；`data` 返回 `DEDUCTED`（本次扣了钱）或 `REPLAYED`（重复请求回放，没再扣）。**需登录**，且过滥用闸（单笔上限、每用户每活动每分钟次数上限，`marketing.activity.budget-deduct.*` 可调）—— bizKey 由调用方自报，没有闸时换键即真扣 |
| POST | /api/coupon/grant · /consume | 领券受理 · 核销 |
| GET | /api/coupon/grant/result/{requestId} · /usable?userId= · /stock/{templateNo} | 轮询 / 可用券 / 模板余量 |
| POST | /api/discount/calculate | 优惠计算（购物车 → 命中规则 + 行级分摊）。规则读写自 ③ 起只在后台前缀 |
| POST | /api/seckill/grab · /pay/{orderNo} | 抢购 · 模拟支付回调 |
| GET | /api/seckill/grab/result/{token} · /activities · /stock/{activityNo} | 轮询 / 活动列表 / 分桶余量 |
| POST | /api/auth/register · /login · /refresh | 注册 / 登录 / 换一对新凭证。这三个是 `/api/**` 里**仅有的**免 access token 入口（靠网关例外清单，见第四·五节）；refresh 只认请求体里的 refreshToken，且旧值重放 = 整条会话作废 |
| GET·POST·PUT | /api/auth/me · /sessions · /logout · /password | 当前身份 / 我的会话列表 / 登出（当场失效，不等自然过期）/ 改密（成功后该账号全部会话作废——2026-09-29（H2）起覆盖范围按 **refresh 寿命**算：access 已过期但 refresh 未到期的会话一并吊销，改前这类"大多数活跃会话的常态"漏网，攻击者手里的 30 天 refresh 仍能换新凭证）。都要带 access token |
| POST | /api/admin/auth/login · /logout · /password · GET /me | 后台登录 / 登出 / 改自己口令（成功后全会话作废）/ 当前身份。**用 admin token，不是消费者 token** |
| GET | /api/admin/users · /sessions · /audits | 账号 / 在线会话 / 审计，统一 `PageResult`（含 total）；`?mine=true` 只看自己的会话 |
| PUT | /api/admin/users/{id}/status?status= | 启停账号（仅 admin；停用同时作废其全部会话） |
| DELETE | /api/admin/sessions/{jti} | 强制下线单个会话（仅 admin） |
| GET/POST | /api/admin/cache/types · /cache/reheat?type=&key=&force= · GET /cache/reheat/ack?type=&id= | 可刷新的缓存类型 · 重预热（admin+operator）：本进程有该 type 就同步带回 `DONE`，没有则投给 owning 服务、返回 `DISPATCHED`+id，用 ack 查回执（见第四节链路 4） |
| GET | /api/admin/config | 在线配置总览：本档形态、当前生效值与来源（FORM/GLOBAL/DEFAULT）、每个形态的行、ORPHAN 与未上报服务、本进程被忽略的键 |
| PUT/DELETE | /api/admin/config（body `cfgKey`+`form`+`value`+`remark` / `?cfgKey=&form=`） | 写在线覆盖 · 删行=恢复出厂。**只有 `admin` 角色**（operator 在网关可写运维，但改不动阈值），未声明的键与越界值一律 40000 |
| POST | /api/admin/config/rebroadcast | 按 DB 现状重发快照（幂等）：修 `41009 配置已落库但未广播` 的那个窗口 |
| GET | /api/admin/ops | **运维只读总览（④，仅 `admin`）**：`mode` 与逐 target 的抓取路径/样本数、进程存活三态、`local_message` 跨库未排空合计、Stream 通道深度、缓存与账的不符条数、审计量与前几个动作、限流拒绝数（带 route）。读不到的一律 `-1`/`ERROR`/`applicable=false`，**不填 0**；这个面里没有任何写 |
| GET | /ui/** | **后台界面（⑥）**：入仓的 Vue3 构建产物，由 admin 提供、网关 `ui-route` 转发。静态资源本身匿名可读（不含数据），索引页 `no-store`、指纹资源 `immutable`、整个 `/ui/*` 带 CSP `script-src 'self'`；深链（`/ui/audits`）由 admin 回退 `index.html`。所有数据仍走下面那些 `/api/admin/**` 并要 admin token |
| GET/POST | /api/admin/activities · POST /api/admin/activities/{no}/transition?event= | 活动列表 / 创建（DRAFT）/ 状态机流转（仅 `admin`）。乐观锁 `version` 不匹配回 `41008` |
| PUT | /api/admin/activities/{no}/budget · /gray | 改预算（同事务重预热，立刻反映到 C 端余额）· 改灰度（只写 DB，由每 5s 回源生效，不刷缓存） |
| GET/POST | /api/admin/discount/rules | 规则列表 / upsert（仅 `admin`；启停也走这条，body 里带 `status`）。写与快照 bump 在同一事务，规则版本号变了 C 端计算秒级跟随 |
| GET/POST/PUT | /api/admin/coupon/templates · PUT /templates/{no}/stock · /status | 券模板列表 / 新建 / 改库存（同事务重预热）/ 上下线。改小低于已发数回 40000 |
| GET/POST/PUT | /api/admin/seckill/activities · PUT /activities/{no}/stock · /status | 秒杀活动列表 / 新建 / 改总库存（同事务重建分桶）/ 上下线。`status != ONLINE` 时改库存不重建桶（不许顺手开闸） |


### 写入口矩阵（③ 的收口：谁在听这个写、要什么角色、留不留痕）

配置类写与交易类写不是一回事：前者改的是"规则"（改一次影响所有用户，且大多需要重算缓存），
后者是用户行为（幂等键 + 削峰）。③ 之后这条界线是**结构**上的，不靠约定：

| 写动作 | 路径 | 监听进程 | 角色 | 审计 | 同事务里的后续动作 |
|---|---|---|---|---|---|
| 建活动 / 流转 | `POST /api/admin/activities[/{no}/transition]` | marketing-activity | admin | ✅ | — |
| 改预算 | `PUT /api/admin/activities/{no}/budget` | marketing-activity | admin | ✅ | `reheat(force=true)` 重算预扣缓存（地雷 A） |
| 改灰度 | `PUT /api/admin/activities/{no}/gray` | marketing-activity | admin | ✅ | 只写 DB，由 `GrayRuleCache` 每 5s 回源（不刷缓存） |
| 规则 upsert | `POST /api/admin/discount/rules` | marketing-discount | admin | ✅ | bump 规则版本号 → 全实例快照刷新 |
| 券模板新建 / 改库存 | `POST·PUT /api/admin/coupon/templates[/{no}/stock]` | marketing-coupon | admin | ✅ | 改库存后 `reheat(force=true)`；低于已发数 40000 |
| 券模板上下线 | `PUT /api/admin/coupon/templates/{no}/status` | marketing-coupon | admin | ✅ | 上线只 `warmIfAbsent`（不把已发的券收回来） |
| 秒杀新建 / 改库存 / 上下线 | `POST·PUT /api/admin/seckill/activities[/{no}/{stock\|status}]` | marketing-seckill | admin | ✅ | ONLINE 时同事务按 `total-sold` 重建 16 桶；非 ONLINE 不开闸 |
| 领券 / 核销 / 抢购 / 支付 / 预算扣减 | `/api/{coupon,seckill,activity}/**` 交易路径 | 各 owning 进程 | C 端 token（网关侧） | ❌（交易不进后台审计） | 幂等键、库存扣减、异步消息 |
| 改在线配置阈值 | `PUT·DELETE /api/admin/config` | marketing-admin | admin | ✅ | 写 DB → INCR → 快照 → 版本键（失败 `41009`） |
| 重预热 | `POST /api/admin/cache/reheat` | marketing-admin（分发方） | admin+operator | ✅（含被拒的） | 本进程执行或投给 owning 服务 |
| 账号 / 会话 / 口令 | `/api/admin/users·sessions·auth/password` | marketing-admin | admin（改自己口令除外） | ✅ | 抬 `pwd_version` → 全会话作废 |

三点值得单独说的：

- **审计在哪个进程落**：admin 自己的动作直写 `admin_audit_log`；四个业务进程**不连这张表**，
  它们把 `AuditPayload` 投进 `mkt:audit:pending`（Stream，`MAXLEN 100000`，**不设 TTL**），
  由 admin 每 5s drain 落表并 `XACK`+`XDEL`。2026-09-29（H5）起 drain 是
  **落库成功才 ACK**（失败条目留 PEL，下一轮由 PEL 回收按空闲时长认领重试——
  原实现无条件 ACK+XDEL，DB 故障窗口内的审计被"确认+删除"得无 DB 行、无 PEL 记录）。
  为什么不用 ⑤ 候选的 `LPUSH+LTRIM+TTL`：
  TTL 淘汰等于**静默丢审计**。建组从 `0` 开始（默认的最新位置会让"先投递后建组"那批永远不被读）。
- **失败与被拒的写也留痕，但只覆盖"进程内"**（2026-10-01 审计 P2-10；边界为复审 N-11）：
  `/api/admin/**` 的非 GET 被**业务进程内** BizException 拒绝（40300 越权、41008
  乐观锁、41007 状态机…）时，`GlobalExceptionHandler` 投一条 `admin.request.rejected`
  审计（身份尽力解析，解析不了记匿名）。**网关侧**发出的 40100/40101/40102/40300
  （无凭证/过期/吊销/只读角色尝试写）到不了任何 advice，**不进审计**——这是当前
  明确的取舍：那类拒绝（"被挡下的写尝试"的主体人群）的可见面是**网关日志与指标**
  （`marketing.gateway.auth.*`、限流与 pre-auth 计数），不是 `admin_audit_log`。
  若将来要把网关拒绝也落审计，需在网关侧接同一条 AuditPayload 通道（网关无
  DataSource，只能直投 Stream），属独立设计项。另：被拒留痕按请求逐条 XADD，
  无同键节流——重试风暴会 1:1 撑大审计表，量级异常时先看这里。
- **乐观锁无处不在**：所有改配置的行都带 `version`，撞了回 `41008 已被他人修改`（附带你看到的
  与当前的两个数），而不是后写覆盖先写 —— 后台是多人的，运营 A 看到的页面可能已经过时十分钟。
- **C 端旧写路径直接删**，不留 301/转发别名：留着就等于"收口"只是加了一层前缀，
  而共享的 demo token 依然能改预算。命中 `40400` 是这件事的唯一可测证据，链路 6 钉的就是它。

