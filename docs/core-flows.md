# 七条核心链路 + 消费者账号体系

> 本文拆自 README 的对应章节（2026-10-01 拆分）。文中"见第 X 节"的互引仍按原 README 编号：一/二节与三节速览在 README；三节详解在 docs/deployment.md；四节在 docs/core-flows.md；五节在 docs/api.md；六节在 docs/testing.md；七/八节在 docs/structure.md。

## 四、七条核心链路（"写入口收口"是横切边界，见第五节的写入口矩阵）

### 1. 领券（削峰 + 最终一致）

```
POST /api/coupon/grant (requestId 幂等键)
  → 网关限流 → 风控(占位) → 模板校验 → IdempotentExecutor 抢占
  → Redis+Lua 原子预扣库存(含单人限领) → 本地消息表 + MQ 发送
  → 返回 ACCEPTED，客户端轮询 GET /api/coupon/grant/result/{requestId}
  → 消费端幂等落库(user_coupon.request_id 唯一索引兜底；券码撞码换码重试，见下) → confirm 消息
```
- 三层幂等：`idempotent_record` 状态机 / `local_message.biz_key` 唯一 / 业务表唯一索引。
  2026-09-29 两处收口：`idempotent_record` 的 PROCESSING 带**租约**（默认 120s，
  `marketing.idempotent.processing-lease-seconds` 可调——进程崩溃后键不再永久砖化在
  "处理中"，重试可按 update_time CAS 接管）；领券消费端把 `uk_request_id`（重复投递，
  幂等回放）与 `uk_coupon_code`（券码随机撞码，换码重试上限 3 次）两类冲突分开处置
  ——原实现一律当重复消息确认掉，撞码时静默丢券且库存/限领永不归还。
  2026-09-29 第三批补齐**归还原语**（根因 A 收口）：券预扣后消息登记/投递失败即调
  rollback_stock.lua 归还（幂等 FAILED 重试不再二次预扣）；秒杀占名额后失败即 refill
  回补原桶并写 FAIL 终态；budget_flow 补 REFUND 写入方（`POST /api/admin/activities/
  {no}/budget/refund`，按原 DEDUCT 配对、每笔扣减最多退一次）；FAILED 死信有计数告警
  （`marketing.message.failed`）与管理端重驱动（`POST /api/admin/coupon|seckill/messages/
  redrive`，置回 PENDING 由补偿定时器重投）。2026-09-29 第四批三处收口：refresh
  轮换改 CAS 于旧摘要（并发双花只有一个赢家，输家按重放吊销会话）；限领计数 TTL
  改为覆盖模板剩余有效期（固定 30 天会把"活动内限领 N 张"稀释成"每 30 天 N 张"）；
  会话行有低频清理（默认保留 7 天、12h 一轮、RedisLeaseLock 防多副本）。
  2026-09-29 第五批：**活动状态与灰度在领券服务端强制**——activity 进程的
  ActivityGatePublisher 把状态+灰度镜像进 Redis（流转/改灰度即时发布 + 5s 周期全量
  重写），coupon 侧 ActivityGate 在预扣前校验（键缺失 fail-open=迁移期行为不变）；
  admin_config 写路径带 expectedVersion CAS（UI 保存时自动携带，冲突 41008 提示刷新）；
  DDL 两副本一致性有 SchemaParityTest 守卫（改一份忘另一侧=测试红）；admin_session
  与 consumer_event_log 有保留期清理（7 天/90 天）；/api/admin 无尾斜杠的错误码错位修正
- `LocalMessageRetryer`（common 内置 @Scheduled）补偿未确认消息；重启时 `StockWarmUpRunner` 按 DB 已发量重算 Redis 库存

### 2. 优惠计算（P99 < 20ms）

```
POST /api/discount/calculate（购物车 → 命中规则 + 行级分摊）
  → volatile 本地快照(位图倒排索引) → 候选剪枝(标签 OR 位图)
  → 逐条精确匹配(活动/用户标签/商品范围/门槛) → 互斥组最优组合(≤2 万组合精确枚举, 超限贪心)
  → 按比例分摊(DOWN) + 尾差归范围内末项 + 行级剩余额度 cap
  → 专用线程池 orTimeout(50ms)，任何异常/超时降级返回原价(degraded=true)
```
- 规则三级缓存：本地快照 → Redis 版本号(`discount:rule:version`) → DB 重建（synchronized + double check）
- `POST /api/admin/discount/rules` upsert 规则后 bump 版本号，全实例秒级生效。2026-09-29（H8）起
  版本号用 **Redis INCR 单调序号**（毫秒时间戳同毫秒撞号会让后一次变更永久丢失）、bump 挪到
  **事务提交后**（提交前 bump，别实例会在窗口里建出"新版本号+旧数据"的快照且永不重建）、
  重建完成后再读一次版本号做双检；bump 写失败时本实例立即按 DB 重建（数据已提交）
  （③ 之前这条在 C 前缀下，任何拿到共享 demo token 的人都能改规则 DSL —— 已收进后台前缀，
  GET 也一并收，因为规则列表本身就是可反推定价策略的资产）
- 基准（1 万规则 / 20 行购物车，开发机）：剪枝后候选 400 条，单次计算均值 **≈0.4ms**

### 3. 秒杀（50 万 QPS 设计口径）

```
POST /api/seckill/grab
  → SETNX 防重购标记 → Lua 分桶原子扣减(hash(userId)%16 定位桶 + 顺序借桶)
  → 占名额成功即返回 token → 本地消息表 + MQ
  → 消费端建单(seckill_order unique(activity_no,user_id,**active**) 兜底) + sold_stock 原子递增
  → 轮询 GET /api/seckill/grab/result/{token}: ACCEPTED / SUCCESS:{orderNo} / FAIL:{reason}
  → POST /api/seckill/pay/{orderNo} 模拟支付；超时 5 分钟未支付由 Job 取消订单并 Lua 回补
```
- 同步路径只有一次 Redis Lua 调用，DB 写全部异步化；分桶把单 key 热点摊到 16 个 key
- 预热口径收在 `SeckillWarmUpService`（启动 `SeckillWarmUpRunner` 与运维重预热共用一份）：
  SETNX 预热，重启/多实例不重置已售进度；**非 ONLINE 或已过结束时间的活动拒绝重预热**
  （否则 force 重预热等于把已下线活动的库存重新开闸）
- **取消与重抢（H7，2026-09-29 对齐）**：超时取消回补库存、删防重标记（允许重抢），同时把
  订单 `active` 置 0——唯一索引带 active，只约束"一人一张**有效**单"。改前索引把 CANCELLED
  行也算占用：重抢的 insert 必撞旧行，幂等回放把已取消单号当 SUCCESS 写回（用户拿到永远
  付不了款的单号）。消费端保留防御分支：回放撞上 CANCELLED 单时回补名额写 FAIL。
  存量卷执行 `docker/mysql/migrate/2026-09-29-seckill-active.sql`。

### 4. 管理后台（两套凭证不互通 + 审计 + 运维入口）

```
POST /api/admin/auth/login（账号口令，dev 种子见下）
  → AdminAuthFilter（网关 order -110）验签 → 查 admin:revoked:{jti} / admin:user:bump:{uid}
  → 角色粗筛（写方法拒 read-only）→ 剥掉 Authorization、注入 X-Admin-{User,Role,Jti,Uid}
  → ConsumerAuthFilter（order -105）见 AdminAuthFilter 的 VERIFIED 标记即让路：
    它托管整片 /api/**，后台那半边靠这个标记而不是靠排除前缀
  → 会话以 admin_session 表为准；改密/停用抬 pwd_version 并写 bump 键 → 整号会话一次杀光
  → 动作写 admin_audit_log（口令类字段写前脱敏），GET /api/admin/audits 查
```
- 登录口另有每 IP 限速（默认 10 次/分钟，含成功尝试），与网关那条 `RL_ADMIN` 是两回事：
  后者防"整个后台被打爆"，前者防"专打登录口的撞库"（BCrypt 单次 50-100ms 在 CPU 上）
- 口令校验用 BCrypt；账号不存在时也跑一次 dummy hash，否则登录口就是掐表式用户名枚举接口
- dev 种子账号（README 公示，正式部署第一件事就是改掉）：`admin/rootdev123`（admin）、
  `operator/demo123`（operator）、`viewer/demo123`（read-only）
- **`POST /api/admin/cache/reheat` 有两条执行路径**：重预热公式由各业务模块实现并注册成 Bean
  （预算在 activity、券在 coupon、桶在 seckill），端点只做分发。
  · 本进程有该 type（LITE 聚合、dev）→ 同步执行并带回 `before/after`；
  · 本进程没有（FULL 分进程的 admin）→ 投进 `mkt:reheat:{type}:pending`，返回
    `DISPATCHED` + 一个 id；owning 服务执行后把 `DONE`/`FAILED` 写进带 10 分钟 TTL 的
    `mkt:reheat:{type}:ack:{id}`，用 `GET /api/admin/cache/reheat/ack?type=&id=` 查。
    执行侧抛了也一定有 `FAILED` 回执 —— 没有它后台就只能把"没回音"猜成"还在排队"。
  只有"该 type 在集群里连消费组都没有"（owning 服务没起来）才回 `41010 本形态不适用`：
  投给一条没人读的流正是"看起来提交了但永远没动静"那种最贵的静默。
  （`41010` 与业务失败 `41000` 分开：前者该换个地方执行或把服务起起来，后者该找业务方）
  键按 type 分而不共用一条流：共用消费组时 Redis 会把 `budget` 的请求投给任意一个消费者，
  没有该 reheater 的进程只能失败或空 ACK，真正的 owner 永远看不到它。
- **配置类写自 ③ 起只存在于 `/api/admin/**`**（详见下面的"写入口矩阵"）：`POST /api/activity`、
  `PUT /api/activity/{no}/budget`、`POST /api/discount/rules`、`POST /api/coupon/templates` 等
  C 端旧路径已删除且**不留转发别名**，命中就是 `40400`。
- 已知边界：业务服务端口绑 `*:808x`（FULL 进程形态），所以后台身份**不认裸 `X-Admin-*` 头**
  —— 网关验签后注入的是 `X-Admin-Token`，服务侧（含 admin 进程自己，2026-09-29 起同一纪律）
  用同一枚 HS256 密钥再验一次签名；绕过网关只带裸头直连必然 `40100`（链路 6 同时钉"带合法
  token 时身份放行"，否则 `40100` 也可能只是"端口不通"的另一种写法）。LITE 的 standalone:8085
  已改为**仅绑回环**发布（此前是全网卡，与"对外只暴露网关"的注释相悖）；四个 C 端**交易**路径
  本来就不校验 token（鉴权在网关），所以**任何形态下都不该把业务服务端口暴露到不可信网络**

### 5. 在线配置下发（改阈值与灰度不重启）

**真值在哪**：`admin_config(cfg_key, form, cfg_value, version, updated_by, remark)`，唯一键
`(cfg_key, form)`；灰度是例外，真值在 `activity.gray_percent` / `gray_whitelist` 两列。
**删行 = 恢复出厂**（不写回原值，否则 yml 改了会被一行陈旧 DB 值永远压住）。

**生效路径**：admin 写库后按四种形态各生成一份**全量合并快照**写进
`mkt:cfg:snapshot:{form}`，各进程每 5s 比对 `mkt:cfg:version:{form}`、变了才取快照。
单键全量而不是每参数一键：网关没有库，载荷必须自包含，而多键存在"改了 A 又改 B、读方拿到
一新一旧"的半应用窗口。版本号取自 `INCR mkt:cfg:seq`（不是 DB 的 MAX+1——并发撞号会让读方
停在陈旧快照上而不再取第二份）。

| 键 | 声明方 | 消费点 | 缺值时 |
|---|---|---|---|
| `gateway.ratelimit.{activity,coupon,discount,seckill,admin}-route.limit` | 网关 | `RateLimitFilter` 经 `RateRuleResolver` | 退回本进程 yml 的 `RL_*`；`window-seconds` 恒取 yml |
| `discount.calc-timeout-ms` / `discount.max-rules-per-order` | discount | `DiscountCalcService` / `PromoEngine` | 退回 `DiscountProperties` 默认 50 / 5 |
| `seckill.token-ttl-seconds` / `pay-timeout-seconds` / `bought-mark-ttl-seconds` | seckill | `SeckillRuntimeConfig`（6 处调用点的唯一取值口） | 退回 600 / 300 / 86400 |
| 灰度 `activity.gray_percent` | activity | `GrayRuleCache` 每 5s 回源 DB | 列为 NULL = 未配灰度 = 全量放行 |

**形态隔离**：`DEPLOY_FORM` 决定读哪一份（`form 行 > GLOBAL 行 > 出厂值`）。**不部署这个
env 就等于没有这套机制**——只认 `GLOBAL` 的覆盖，行为与本段之前逐字节一致；取值拼错同样
退回 GLOBAL 并告警，因为"把 FULL 的 1000/s 套到单机 LITE 上"是这里最贵的一种错。
灰度不走 Redis 通知而直接回源 DB：`Redis 被清空 → 曾设 5% 的活动意外全量`这个后果不该存在。

**稳态必须安静**：没人写过在线配置时（版本键不存在），轮询器不能每 5s 重取一次空快照再刷条
INFO——区分"从没取过"与"取过且为空"，否则六个进程各 12 条/分钟，真降级信号会被埋掉。

**失败语义**：未声明的键与越界值在**写侧**就被 `40000` 拒（写了也没人消费 = 后台上一个假按钮）；
读侧对快照里每个条目按本地声明逐条校验，不合格的忽略并退回出厂值（记
`marketing.config.entry.ignored`），所以限流配置写错的后果最多是"放行偏宽或偏紧"，绝不会让
网关拒绝服务或起不来；快照 JSON 坏了则整份按空应用（退回出厂，而不是抱着陈旧值不放）；
落库成功但广播失败返回 `41009 配置已落库但未广播`，配一个幂等的"重新广播"动作修复。

### 6. 运维读数（④：不接 Prometheus 的只读大盘）

**为什么自建**：这台机器上随时可能没有 Prometheus，而"服务是不是活着、异步堵了多少、
缓存和账对不对"这三个问题在**故障时**最该能问。所以读数的取数路径只有 JDK HttpClient +
Redis + MySQL，Prometheus 只是可选的第二消费者。

**三问的取数口径**：

| 问题 | 读数来自 | 判据长在哪儿 |
|---|---|---|
| 进程活着吗 | 各进程 `mkt:cfg:schema:{process}` 的 TTL（⑤ 的 schema 键每 60s 重投、TTL 180s） | 三态：有键=在跑 / 无键但**本档该有**=没在跑 / 本档压根没这个进程=**不适用**（`null`，不参与判活） |
| 异步堵了多少 | `local_message` 的 `PENDING+SENT` 跨库求和 + Redis Stream 的 `XLEN`/`XPENDING` | 跨库靠 `information_schema` 发现哪张库里有 `local_message`（H2/MySQL 大小写敏感差异见第六节噪音 ⑦） |
| 缓存和账对不对 | gauge `marketing.cache.consistency{type}` | **判定长在 owning 模块里**：`CacheConsistency` 契约由各模块用自己那套重预热公式实现（预算 `Σ流水` 对账、券 `remainOf`、秒杀分桶求和），④ 只读那个数——同一段代码既是修的手也是查的眼，才不会"修完还报不符" |

**为什么"不可见"绝不填 0**：FULL 档的消息通道是 RocketMQ，队列深度只在 broker 里，客户端读
不到 → 那一行是 `applicable=false` + `len/pending=-1` + 一句说明，而不是 0。`-1` 在这张盘上
只有一个意思：**判定不了**。把"读不到"画成 0，就是让大盘在最该说话的时候说"一切正常"——
积压合计里有任一来源不可见，总数也一并报 `-1`，不装作知道。

**抓谁由清单说了算，清单在启动时校验**：`marketing.admin.ops.targets` 是
`name → host:port` 的正面白名单（host 只接受 `marketing-*` / `standalone` / `127.0.0.1` /
`mkt-*`，端口只接受 8081-8087 与 8090/8091（8091 是网关独立管理端口），路径是常量
`/actuator/prometheus`），请求期不再接受
任何外部输入；`Redirect.NEVER`、1s 超时、响应超过 256KB 直接判失败（半份指标比没指标更坏）。
清单里任意一条不合法 → **启动即失败**，而不是点大盘时才发现。

**模式不是开关，是清单算出来的**（`mode` 字段自证）：本进程内的模块走 `local`（直接读
`MeterRegistry`），其余进程走 `proxy`（HTTP 抓）。所以 LITE/dev 是 `local+proxy`——网关在**任何
形态**下都是独立进程，没有哪种形态能"本地读到它"；FULL 三种入口是 `proxy`。抓不到的那一条
标 `ERROR` 并保留面板，整片大盘不会因为一个进程死了而不可读。

**④ 只读**：这个面里没有任何写。发现不符时的修法是 ③ 的 `POST /api/admin/cache/reheat`，
面板的 `note` 里直接把这句话写给看盘的人。冒烟链路 7 钉的就是这条闭环：先把自己要碰的那条
预热成一致（拿基线），再把缓存改错 → 断言条数**涨了**，重预热 → 断言**回落到基线**。
只改缓存不碰 DB，所以红了不可能是数据坏了。

**弱主机代价**（实测）：一次点盘 = 六次 1 秒超时的 HTTP + 六次文本解析（每份 150-340 条样本），
LITE 下 standalone 跑完七条链路仍是 551.6 MiB / 768 MiB，没触到 `mem_limit` 阈值。
保留策略与历史趋势（Prometheus 抓取、告警）**刻意推迟**：那是"看得更久"，不是"现在能看见"。

### 7. 后台界面（⑥：同源挂在 `/ui/`，零新增进程）

**形态**：`marketing-admin-ui/`（Vue3 + Vite + Element Plus，**不进 root pom、不进 maven 生命周期**）
构建出的 `dist` **入仓**到 `marketing-admin/src/main/resources/static/ui/`。
所以 clone 下来 `mvn package` 就能跑出一个带界面的 jar，机器上没有 node 也一样——
这是这个仓库给"常态服役档"留的确定性，代价是产物必须由人重建（见下面的三道闸）。

**为什么同源**：仓库零 CORS 配置，也不打算加。`/ui/**` 由网关新增的 `ui-route` 转给 admin
（local 与 nacos **两套 profile 各一条**，LITE 复用同一对 `ADMIN_HOST/ADMIN_PORT` 指到 standalone:8085），
并且必须进 `rate-limit` map——**route 不在 map 里 = 完全不限流且不报错**。
它不需要任何白名单条目：C 端鉴权托管的是 `\u002fapi/**`，`/ui/**` 与 `/h5/**` 天然不在罩子里。

**history 回退在 admin，不在网关**：`UiWebMvcConfig` 给 `/ui/**` 注册资源并回退 `index.html`，
网关不该懂前端路由。两个只起进程才看得见的坑（都是真栈实测抓的，纯函数单测两次都绿）：
空路径时 `createRelative("")` 返回的是**目录**且 `exists()` 为真；而即便跳过它，
`ResourceHttpRequestHandler.processPath` 对空路径也直接判 404——最后用 `addViewController`
把 `/ui` 与 `/ui/` 显式转发到 `index.html`。

**缓存头只能有一个主人**：`/ui/index.html` 是 `no-store`（旧索引配上新的指纹文件名就是白屏），
`/ui/assets/**` 是 `max-age=31536000, immutable`（文件名带内容指纹）。
所以**不能**用 `ResourceHandlerRegistry.setCacheControl(...)`——它会在 handler 里再写一次
`Cache-Control`，把 `immutable` 覆盖成 `no-store`，看起来"更保守"实际让每发 assets 都回源。
另外整个 `/ui/*` 带 CSP `script-src 'self'; object-src 'none'; base-uri 'self'`。

**token 存 localStorage**：`Authorization: Bearer` 是网关唯一识别路径，改 cookie 要么动 ③ 的鉴权链
要么加反代；不写 cookie 就没有 CSRF。三个鉴权码在界面上走**三条**路——40101 跳登录并带
`?next=`（这次没写进去，回得去）、**40102 不回原页也绝不自动重放**（那一发可能已经落库）、
40100 只清 token。倒计时用登录响应的 `expiresInSeconds`，不前端写死 TTL。不做 refresh token。

**dist 的三道闸**（CI 于 2026-10-01 起接了 mvn/vitest/check-scripts 三步——注意
**重建对拍仍未进 CI**：`build-ui.sh` 之后 `git diff --exit-code` 的步骤还没有，改了
`.vue` 不重建仍是"作者记得做"，见第六节"已知噪音"）：
① `scripts/build-ui.sh` 是唯一重建入口（并把 `rev`/时间注进 `index.html` 指纹）；
② `scripts/check-ui-dist.sh` 比对 jar 与仓库的逐项 sha256，双向；
③ `UiDistIntegrityTest`（不连库、不起上下文）断言索引页存在、它引用的指纹资源都在、
**且 dist 里没有没被引用的孤儿文件**。第三道已经在第一次跑时抓到真漂移：Maven 的资源拷贝
只覆盖不删除，`target/classes/static/ui` 会攒下上一版的 `assets/*` 被打进 jar——
所以现在 `build-ui.sh` 构建后顺手清掉那个目录。

**界面只把后端已有的判断显示出来，不复制一份权限**：角色只用来灰化入口，
真正的判定在每个写端点（operator 改阈值仍是 40300）。前端那张状态机表也只裁按钮不当校验，
非法流转照样显示后端的 41001 原文。

**实测**：dist **252 KiB**（未压缩，JS 96KB + CSS ~52KB），全量注册 Element Plus 时要 1388 KiB——
按需引入是必要的而不是洁癖，因为这份产物进的是常态服役档那个 jar。
冒烟链路 8 六条（首页 200、索引 `no-store`、深链回退、指纹 `immutable`、缺文件必须 404、
无凭证打 `/api/admin/users` 仍 40100）在 LITE 与 FULL 容器档各全绿；
真浏览器走了一遍登录 → 十页渲染 → 界面改秒杀库存并核对 Redis 分桶真的按 `total-sold` 重建。

## 四·五、消费者账号体系（C 端身份的唯一来源）

C 端不再有"一枚所有人共用的 demo token"。身份链路是：

```
POST /api/auth/login（identifier + password，BCrypt 校验）
  → account 服务签发一对 JWT：accessToken(15min) + refreshToken(30d)，会话落 consumer_session
  → ConsumerAuthFilter（网关 order -105）验签 → 查 consumer:revoked:{jti} / consumer:bump:{uid}
  → 剥掉 Authorization、注入 X-User-{Id,Name,Jti} + 透传那枚 X-User-Token
  → 业务服务用同一个 codec 无状态自验（ConsumerRequestIdentity）
  → 刷新走 /api/auth/refresh：旧 refresh 进 Redis 黑名单，重放即吊销整条会话
```

三条不可协商的口径：

1. **托管的是整片 `/api/**`，公开读数靠例外清单**。方向反过来写一次，新增一条交易路由就默默免鉴权了。
   当前例外：活动详情/可参与/预算余量、券模板余量、秒杀列表与分桶余量 —— 判据是"游客本来就该看到"
   且"不落任何属于某个消费者的数据"。
2. **身份只认签名 token，不认裸 `X-User-*` 头**。FULL 档业务端口绑 `*:808x`、LITE 的 standalone:8085
   也发布，直连的人随手一个 `X-User-Id: 70001` 就能读走任何人的卡包 —— 与后台 ③ 同一条理由，
   冒烟两侧都各钉了一条断言。
3. **请求体里没有 `userId` 了**。领券/核销/抢购/试算/灰度命中原先由调用方自报归属，现在一律从验过的
   claims 里取。`CalcInput.userId` 上是 `@JsonIgnore`：反序列化根本不收。

已知边界（写清楚，不藏）：
- 吊销位与会话作废只在网关判，业务侧是无状态验签 —— 被吊销的 access token 直连业务端口可用到自然过期，
  上限 15 分钟。与后台同形。
- `CalcInput.userTags` 已收口（2026-09-29）：C 端入口把它覆写为空集，管理端也禁止创建
  `user:` 前缀的人群规则 —— 服务端没有可信人群来源之前，人群折扣不该有"自报命中"的路径。
  引擎的 `user:` 匹配语义保留，接入人群服务后在此恢复按 userId 填标签。
- `/api/seckill/grab/result/{token}` 只做登录门，没做本人校验：token 是 UUID 且结果只含状态与单号。
  `/api/coupon/grant/result/{requestId}` 做了本人过滤（那里返回的是可兑付的券码）。
- `CONSUMER_JWT_SECRET` 必须与 `ADMIN_JWT_SECRET` 是**不同**的值：同值的话两套凭证的隔离只剩
  claim 形状的侥幸。deploy-preview/deploy-full 的入口会拒绝同值与留空。
- 上面这三条语义 + 登出与改密的"当场生效"，都由**冒烟链路 9** 端到端钉住（配对形状：先验有效、
  再验作废、最后验新状态可用）。五套形态的实跑还当场抓到过一处真缺陷：重放时代码按已被轮换掉的
  `refresh_hash` 找受害会话，必然查不到 → 只拒不吊销。

