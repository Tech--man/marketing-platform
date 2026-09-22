# 管理后台设计 · 第一段：地基（①）+ 账号体系（②）

日期：2026-09-22 ｜ 状态：已批准，待实施 ｜ 范围：本文只覆盖 ①②，③-⑥ 各自另立设计

## 1. 背景与动机

`marketing-platform` 是"三形态同码"的营销脚手架：LITE 服役档（`marketing-standalone` 四模块单 JVM +
Redis Stream，实测常驻 1.05 GiB，常态真跑流量）、FULL 扩容档（5 进程 + RocketMQ/Nacos/Prometheus，
或容器化多副本）、dev 开发档。它今天只有面向调用方的**交易接口**（领券、抢购、算优惠、活动状态机），
没有任何管理面：运营改配置靠手敲 SQL 与 `redis-cli`（`scripts/reset-demo-data.sh` 就是这么干的），
运维看状态靠人肉拼 `XLEN` / `mqadmin` / SQL。

目标是配一套管理后台（业务后台 + 运维）。已确认的取向：独立 SPA + 管理 BFF、含全量运维动作、
完整账号体系（DB 用户表 + JWT + 会话）、后台常驻。这组合实际是 5 个可独立交付的子系统，因此拆六段，
每段独立验收：

| 段 | 内容 | 本文是否覆盖 |
|---|---|---|
| ① 地基 | 分页契约、五个正确性地雷、错误码归一、可复用原语 | **是** |
| ② 账号体系 | marketing-admin 模块、三张表、JWT + 会话 + 强制下线、网关双 filter | **是** |
| ③ 业务后台 API | 券模板/活动/秒杀/规则 的列表分页与编辑 | 否 |
| ④ 运维只读聚合 | 消息积压、Redis 键与 TTL、任务运行态、容量水位 | 否 |
| ⑤ 运维写动作 | 在线改阈值/灰度、重投、触发 Job、订单取消回补 | 否 |
| ⑥ SPA | admin-ui（node 本地/CI 构建，dist 入仓） | 否 |

**为什么 ① 必须排在 ③ 前面**：下面地雷 A/B/E 的形状都是"写接口返回 code=0，但实际没生效或没扣钱"。
先建后台再修，等于给这些静默失败装一个体面的按钮。

## 2. 架构决策

- **落位**：`marketing-admin` 作为**业务模块**。LITE 由 standalone 扫进同一 JVM（不新增常驻进程，
  只占一点堆），FULL 是第 6 个服务（8086），dev 走聚合。这样"后台常驻"不推翻 LITE 的内存口径。
- **签发与校验分离**：网关是 WebFlux 且**没有 DataSource**，只能无状态验签；查库验密码只能在 admin 侧。
  于是 admin 模块签发 HS256 JWT，网关校验；业务 token 与 admin token **并存、按路径分区**，
  C 端 `demo-token-123` 行为零变化。
- **依赖增量**：JWT 用 JDK `javax.crypto.Mac` + Jackson 手写，**不引入 JJWT**（三处能力而已，
  且省掉一个会牵动 Jackson 版本的库）。口令哈希用 `org.springframework.security:spring-security-crypto`
  的 BCrypt —— 版本已由 Boot BOM 管，只多这一个 jar，不引 security 全家桶；不自研哈希封装。
  注意本地 `~/.m2` 目前**没有**该 artifact，首次构建需联网拉一次。
- **复用既有设施**：`ReactiveRedisTemplate<String,String>`（`RateLimitFilter.java:35,40` 已是 bean）、
  `AuthFilter.writeJson`（`AuthFilter.java:67`，包内静态）、`RedisLeaseLock` 的"不释放锁、TTL=周期"
  语义、装配回归的 `ApplicationContextRunner + H2 + 127.0.0.1:1 Lettuce` 手法。
- **原语而非端点**：①②只把 `CacheReheater`、`BizKey`、`AuditSink`、`PageResult` 立成
  marketing-common 的公共件，③④⑤ 直接往上叠，不回头改 admin 内部。

## 3. 五个正确性地雷（实测证据）

| # | 问题 | 证据 | 严重度 |
|---|---|---|---|
| **E** | 预算重预热按**全额**写 Redis，不减已用 → 键丢失后预算"回涨"、可超支。与类注释自述的对账口径矛盾 | `BudgetService.java:99` vs `:24`；对照 `ActivityService.java:56`（仅创建时全额，正确）、`CouponTemplateService.java:60` 与 `SeckillStockService.java:63`（都按 `total-已用`） | **资金 bug，活路径**。触发场景就是自家 runbook：换形态 / 重建数据层后 Redis 为空、或手工 DEL |
| **A** | Redis 预扣 SETNX 一次性预热，改 DB **不刷新**（键已存在时 `setIfAbsent` 静默不写） → 运营改 `total_stock`/预算表面成功实际不生效 | `CouponStockService.java:74-78`、`BudgetService.java:38-41`；缺键行为三处各不相同，见 §5.2 表 | 后台"保存"假成功 |
| **B** | `budget_flow.biz_key` 全局唯一 + `INSERT IGNORE` 撞键时既不扣款也返回 code=0；`rollbackFlow` 按全局 biz_key DELETE，会删掉别的活动占位 | `BudgetService.java:54-60,89-91`；`01-schema.sql:60` | 静默丢钱 |
| **D** | `local_message.biz_key` 同样全局唯一，`recordIfAbsent` 用 `INSERT IGNORE` 且不返回受影响行数，而 `publish/confirm/load` 只按 biz_key 定位 | `LocalMessageService.java:45-50,55-87,120-124`；`01-schema.sql:88,174`；券侧传裸 requestId、秒杀传 `seckill:{token}` | 潜伏：跨 topic 撞键会**重投别人的 payload**，或撞 CONFIRMED 行后静默不发（用户永远轮询不到券而接口已返回 ACCEPTED） |
| **C** | 五个模块都配 `logic-delete-field: deleted`，但所有表都**没有这一列** → MyBatis-Plus 退化为物理 DELETE | 各模块 `application.yml:37`、standalone `:45`；两份 DDL 无 `deleted` | 目前全仓零处 `deleteById` 所以没炸；后台"删除"一点就真删 |

裁定：C 用**删配置**解决而不是补列——`ActivityStatus` 已有 OFFLINE/FINISHED、`coupon_template.status`
有 OFFLINE、`promo_rule.status` 有 DISABLED，再引入 `deleted` 是第二套真相且要 4 处 ALTER。

实施时的两处裁定补充：
- `idempotent_record.uk_biz_key` **保持全局**：它的键本身带场景前缀（`BizKey.of` 统一生成），
  且"同一个 requestId 跨服务各执行一次"本来就该被拒 —— 这里的全局唯一是正确语义，不是缺陷。
  只有 `budget_flow`（键由外部用户提供）与 `local_message`（键形跨 topic 会互相吞）需要改索引。
- 券侧幂等表与消息表**共用同一个键**（`grant:<requestId>`）：消费端 confirm 能从事件里的
  requestId 原样还原，不需要多带字段；也因此历史行（裸 requestId）与新行形状不同但不互扰
  —— 已确认行不再被读，迁移脚本里的可选 UPDATE 默认不执行（不为形状改历史数据）。

记录但本文不修：`local_message` DDL 注释状态写 `DEAD`、代码用 `FAILED`（`01-schema.sql:82` vs
`LocalMessageService.java:27`）；`IdempotentExecutor` 的 PROCESSING 无超时回收，进程崩溃即永久毒化该
requestId（属 ④/后续）。

## 4. 实施批次

| 批次 | 内容 | 改行为？ |
|---|---|---|
| S0 | common 纯新增：`PageResult<T>`/`PageQuery`、`BizKey.of(scene,scope,raw)`、`CacheReheater` SPI + `CacheReheatRegistry`、`AdminTokenCodec` + `AdminClaims`、`AuditSink`/`AuditRecord`、`ErrorCode` 增 `40300/40101/40102` | 否 |
| S1 | 地雷 E + A：`warmFromDb` 改按 `budget_amount - SUM(DEDUCT) + SUM(REFUND)` 从流水直读；预算/券库存/秒杀分桶三处实现 `CacheReheater`（各自公式 + 补 TTL） | **是**（只改"键缺失后重建出来的值"） |
| S2 | 地雷 B + D：唯一键加维度（`(activity_no,biz_key)` / `(topic,biz_key)`）、`deduct` 返回 `DEDUCTED/REPLAYED`（**code 仍 0**，语义放 `data`，保住 `smoke-test.sh:66`）、`rollbackFlow`/`publish`/`confirm` 带维度条件、`recordIfAbsent` 返回行数、bizKey 统一 `BizKey.of` | **是**（消歧义，不新增拒绝） |
| S3 | 地雷 C：删 5 处 `logic-delete-*` 配置，admin 侧不提供物理删除 | 否 |
| S4 | 错误码归一：仅"不存在"分支改 `NOT_FOUND(40400)`（`ActivityService.java:66`、`BudgetService.java:97`、`CouponTemplateService.java:38,86`）；`41000/41001/41003` 原样保留（smoke 依赖） | **是**（范围极小） |
| S5 | admin 模块 + 三张表 + 登录/改密/强制下线/在线列表/审计查询/reheat 入口 + standalone 落位 | 否（新增） |
| S6 | 网关 `AdminAuthFilter` + `AuthFilter` 跳过已 VERIFIED + 两套 profile 各加 admin-route + `RL_ADMIN` 限流条目 + 三形态密钥下发 | 否（业务路径不动） |
| S7 | `AuditService implements AuditSink` 落库 + `LoginGuard`（Redis 每 IP 登录限速 + `fail_count/lock_until`） | 否 |
| S8 | 单测、smoke 链路 4、三形态实测、README 口径 | — |

依赖：S0 → S1/S2/S4 → S5 → S6/S7 → S8；S1 必须先于 S5（reheat 端点依赖 `CacheReheater`）。

## 5. 关键设计

### 5.1 分页契约

```java
public class PageResult<T> { long total; int page; int size; List<T> records;
    static <T> PageResult<T> of(long total, int page, int size, List<T> records);
    <R> PageResult<R> map(Function<T, R>);  boolean isEmpty(); }
public class PageQuery { int page; int size;                      // 页码 1 起，size 夹到 [1, MAX_SIZE]
    static PageQuery of(Integer page, Integer size);  long offset(); }
```

**实现时纠了本设计的一处错误**：原写 `PageResult.of(IPage)` 与 `PageQuery.toPage()`，但
`marketing-common/pom.xml` 里**没有 MyBatis-Plus**（只有 starter/json + optional 的
web/jdbc/redis/mq）—— 把 `IPage` 引进来会让无库场景（网关）也被拖上 ORM。改成 ORM 无关契约，
各模块自己 `PageResult.of(p.getTotal(), (int) p.getCurrent(), (int) p.getSize(), p.getRecords())`。
`offset()` 用 `long`，免得大页码乘法溢出成负数。越界参数一律夹到合法区间**并如实回显**
（`PageResult.size` 即生效值），既不静默少给也不因"你要 5000 条"直接报错。

这是全仓第一处真正使用分页的地方（`PaginationInnerInterceptor` 已在 5 个
`MybatisPlusConfig.java:20` 注册但无人用）。**不改现有两个 C 端列表接口**——`smoke-test.sh:15,132`
依赖裸 List 形状，分页留给 ③ 的新端点。

### 5.2 缺键语义与重预热（地雷 E+A 的解法）

先讲清三处**当前真实的缺键行为**（设计依据，不是推测）：

| 键 | 缺键时现在发生什么 | 后果 |
|---|---|---|
| `activity:budget:{no}` | `evalDeduct` 返 -1 → `warmFromDb` 按 `activity.budget_amount` **全额**重写再扣（`BudgetService.java:64-67,99`） | **预算回涨 → 可超支**（E） |
| `coupon:stock:{id}` | Lua `EXISTS==0` 直接返 `-2 NOT_WARMED`（`deduct_stock.lua:11-13`、`CouponStockService.java:36-49`），领券失败 | fail-closed：不丢钱，但**券发不出去**直到有人预热 |
| `seckill:stock:{no}:{bucket}` | 分桶 TTL 86400 自然回收（`SeckillStockService.java:35,64`），抢桶落空按售罄 | 活动结束后的预期回收，但运行期丢键=误判售罄 |

结论：**不给预算/券键加 TTL**（加 TTL 只是新造一个"到期后无人重算"的窗口），重算入口统一收敛到一个原语：

```java
public interface CacheReheater {
    String type();                       // budget | coupon-stock | seckill-stock
    ReheatResult reheat(String key, boolean force);
    record ReheatResult(String type, String key, long before, long after, String formula) {}
}
```

- 预算：`remain = budget_amount - SUM(DEDUCT) + SUM(REFUND)`，**从 `budget_flow` 直读**（与
  `BudgetService.java:24` 自述的对账口径一致）。缺键分支不再"全额 SETNX + 再 DECRBY"，而是
  **算出对账值后直接落值**：本笔已作为流水存在，再 DECRBY 一次就是把同一笔算两遍。
  实施中因此多修了一处：原代码在缺键分支先 `rollbackFlow` 回删自己的流水行再重试，
  留下"Redis 扣了、流水没记"的缺口（实测余额恒比权威值少一笔）—— 现在只有真失败才回删。
- 券库存：`remain = total_stock - COUNT(user_coupon where template_id=?)`。缺键自愈**本来就有**
  （`CouponGrantService.java:82-85` 拿到 NOT_WARMED 会 warm 后重试一次），本次只是把口径收进
  `CacheReheater` 并补上 `force=true` 的覆盖能力 —— 地雷 A 的"改了 DB 不生效"才是这里缺的东西。
- 秒杀：逐桶 `reheat`，`remain = total - sold`，按 `allocateBuckets` 重投，**保留 86400 TTL**
  （自然回收是有意设计）。预热口径从 `SeckillWarmUpRunner` 抽到 `SeckillWarmUpService`，
  启动与运维共用一份；并加守卫：**非 ONLINE 或已过结束时间的活动拒绝重预热**（force 等于重新开闸）。
- `force=false` 走 SETNX（只补缺、不覆盖既有值），`force=true` 才 DEL 后重建 —— 运营改完 DB 走
  `force=true`，误触与显式覆盖在接口上就是两个参数，不靠调用方自觉。
- `CacheReheatRegistry` 注入所有实现按 type 分发，未知 type → `40000`。④⑤ 直接复用这套原语。

三处 `CacheReheater` 由各业务模块自己实现并注册为 Bean（预算在 activity、券在 coupon、桶在 seckill），
admin 只提供 HTTP 入口 —— "重算公式的知识属于谁"要跟领域走，别让 admin 变成了解全部业务的地方。

### 5.3 账号、token 与会话

```
admin_user(id, username UNIQUE, password_hash, display_name, role, status,
           pwd_version, fail_count, lock_until, last_login_time, create_time, update_time)
admin_session(id, jti UNIQUE, user_id, username, login_ip, user_agent,
              expire_at, revoke_reason, revoked_at, create_time)
admin_audit_log(id, actor_id, actor_name, role, action, resource_type, resource_id,
                method, path, request_summary, result_code, error_msg, ip, cost_ms,
                create_time, idx_actor_time(actor_id,create_time), idx_resource(resource_type,resource_id))
```

claims：`{sub:username, uid, rol, ver=pwd_version, jti, iat, exp}`，HS256，skew 固定 30s，
Access TTL 900s。三级失效：

| 键 | 作用 |
|---|---|
| `admin:revoked:{jti}` | 登出/强制下线单会话；TTL = token 剩余寿命 |
| `admin:session:{jti}` (HASH) | 在线列表快路径；**DB 为权威源**，不一致以 DB 为准 |
| `admin:user:bump:{userId}` | 值为 epoch 秒，`iat` 早于它即整号失效 → 改密/停用一次杀光所有会话 |

`ver` claim 与 `pwd_version` 比对，作为 bump 键丢失（Redis 被清）时的第二道防线。过期会话由
`@Scheduled` + `RedisLeaseLock` 归档。种子账号 `admin/rootdev123`、`operator/demo123`（BCrypt，
dev 口令写进 README，与 `demo-token-123` 同等待遇）。

### 5.4 网关两个 filter 的关系

`AdminAuthFilter`（`GlobalFilter, Ordered`，order **-110**）只匹配 `Path=/api/admin/**`，
permit-list 只有 `/api/admin/auth/login`：验签 → `hasKey admin:revoked:{jti}` → 角色粗筛 →
注入 `X-Admin-User`/`X-Admin-Role` 并 **移除 Authorization**（防下游误读）→ 置 exchange 属性 VERIFIED。
`AuthFilter.filter()` 开头加一句 `if (Boolean.TRUE.equals(exchange.getAttribute(VERIFIED))) return chain.filter(exchange);`
——比往 `marketing.gateway.whitelist` 塞路径更稳，不依赖配置项被人改坏。

限流：route id 不在 `rate-limit` map 里就**完全不限流**（`RateLimitFilter.java:55-58`），所以
`admin-route: limit: ${RL_ADMIN:50}` 必须与路由同时加，且 **local 与 nacos 两套 profile 都要加**
（漏一套 → FULL 形态 404 或裸奔）。登录防爆破不塞进网关（`/api/admin/**` 只有一个桶），
由 admin 侧 `LoginGuard` 做每 IP 计数 + 账号锁定。

### 5.5 四库隔离档

admin 表放**第 5 个库 `marketing_admin`**（`start-all.sh` 现有 `MYSQL_DB_PER_SERVICE` 的
`marketing_${name#marketing-}` 映射自动产出，零改动）。代价：两份 DDL 都要 `CREATE DATABASE` +
两条 `GRANT`（`'marketing'@'%'` 与 `@'localhost'`）——上一段工作就是漏了
`CREATE DATABASE marketing_activity` 导致四库档 MySQL 初始化整体中断，这条已写进 compose 注释。
审计与业务实体跨库不可 join，但审计只存 `resource_id` 字符串，无实际损失。

### 5.6 落位清单（14 处）

顶层 `pom.xml:14-23` ｜ admin pom（`repackage` + `<classifier>exec</classifier>`）｜
`marketing-standalone/pom.xml:19-38` ｜ `MarketingStandaloneApplication.java:34-40` 加
`"com.example.marketing.admin"`（**绝不能写根包**，`:23-27` 注释说明会让 common 的
`@ConditionalOnBean(DataSource)` 静默失效）｜ `StandaloneComponentScanTest.java:29-34` 同步 ｜
admin `Dockerfile` ｜ `docker-compose.full-app.yml`（不发布端口） ｜ `docker-compose.preview.yml`
（`ADMIN_HOST=standalone`、`ADMIN_PORT=8085`、`RL_ADMIN`） ｜ 网关两套 profile 路由 + 限流 ｜
`scripts/start-all.sh:42` 与 `:87-95` ｜ 两份 DDL ｜ `prometheus.yml` 两 job 各加 8086 ｜ README
端口表 / 端口矩阵 / 内存表 / 目录树 / 扩展点表。

## 6. 验收

1. **单测** 26 → ≈44：`PageResultTest`、`BizKeyTest`、`AdminTokenCodecTest`（正常/篡改/exp/skew/角色/
   `ver` bump）、`CacheReheatRegistryTest`、`BudgetServiceTest`（重复 bizKey→REPLAYED 不双扣、
   rollback 不删别活动行、重预热按公式而非全额）、admin 装配回归（H2 + `schema-admin.sql`）。
2. **smoke 链路 4（34 → 42）**：登录拿 token ｜ 错密码 40100 ｜ 无 token 401 ｜ **C 端 token 打
   `/api/admin/**` 被拒（两套不互通）** ｜ 只读角色调写接口 40300 ｜ 分页返回 `total` 且 records ≤ size ｜
   同 bizKey 二次扣减 `code:0` + `data:REPLAYED` ｜ **抬 DB → `POST /api/admin/cache/reheat` →
   `GET /budget/remain`、`/api/coupon/stock/CT2026001` 等于新口径**（这条在修 S1 之前必红，是 A/E 的回归锚）。
   恒等式 `分桶余量 + DB 已售 == 总库存` 不得受影响。
3. **三形态各一轮**：`deploy-preview.sh`、`start-all.sh`（含 `MYSQL_DB_PER_SERVICE=1` 四库档验证
   admin 独立库）、`start-dev.sh`；README 覆盖矩阵补行。
4. **内存实测**：admin 进同一 JVM 预计 +20-40 MiB。先保持 `-Xmx320m` 测 `docker stats`，只有 standalone
   超 640 MiB 才把 `mem_limit` 768m→896m，不先动堆；数字进 README 内存表。

## 7. 风险

- S1/S2/S4 改语义，回归网 = 现有 34 条 + 新增第 7、8 条。
- DDL 唯一键变更**只作用于新建卷**；已存在的 `mkt-data_mysql-data` 需手工 `ALTER`，README 写迁移语句
  但不自动执行。回退路径：`stop-preview.sh -v` 清卷重建 + `reset-demo-data.sh` 复原演示数据。
- standalone 扫进 admin 后 BCrypt 登录占 50-100ms，不进 C 端热路径。
- 本轮**不收口** `/api/discount/rules`、`POST /api/activity` 这类"写配置却只有共享 token 保护"的
  C 端入口 —— 已知风险，留给 ③ 改成要求 admin 角色。
- 跨主机 FULL 的 skew 依赖 NTP，README 记一句。

## 8. 明确不做

业务 CRUD/编辑接口（③）、运维只读大盘（④）、运维写动作 HTTP 面（⑤，本文只留 `CacheReheater` 原语）、
任何前端与 node 工具链（⑥）、RBAC 角色表/权限点表/菜单表、refresh token / OAuth2 / LDAP / SSO、
登录验证码与 IP 白名单、审计保留期治理与查询 UI、`DEAD/FAILED` 注释统一、`IdempotentExecutor`
PROCESSING 超时回收、分库迁移工具、动态配置下发（另：`GET /api/seckill/stock/{no}` 对不存在活动仍返回 code=0 + 空数组，属同类 404 归一的漏网，留给 ③）（口径定为"DB 真值 + Redis 广播"，实现留 ⑤）。
