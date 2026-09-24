# ④ 运维只读聚合 · 段内 spec

日期：2026-09-24 ｜ 分支：`feat/ops-readonly-aggregation` ｜ 母版：`2026-09-23-admin-console-business-ops-ui-design.md` §7
前序：⑤（在线配置下发）、③（业务管理面与写入口收口）均已合并进 main。

本段兑现母版 §7 的"不依赖 Prometheus 的运维读数"。写之前先把母版 §3 的八条事实和 §7 的每条
设计**逐条对现在的代码重验了一遍**——有六条变了，其中两条会让 ④ 直接做错，先列出来。

## 1. 母版的前提里，现在已经不成立的部分

| # | 母版怎么写 | 现在的事实（file:line） | 对 ④ 的影响 |
|---|---|---|---|
| 1 | 事实 #7：`local_message` 在每服务一库档会"静默少报"，需 `information_schema` 发现 + 跨库查询 + 补 `GRANT` | `docker-compose.full-app.yml:39` 与 `scripts/start-all.sh:36` 都把 FULL（进程与容器两种）钉在**单库 `marketing`**；只有 `MYSQL_DB_PER_SERVICE=1`（`start-all.sh:40,90`，**进程形态独有**）才裂成五库。而 `marketing_admin` 里**根本没有 `local_message` 表**（`docker/mysql/init/01-schema.sql:86-100` 只在四个业务库建） | 跨库发现不是"always 需要"，是**第五套形态独有**。先探测再查（§4.1），别默认所有形态都在跨库 |
| 2 | 事实 #7 附带：补 `GRANT` | `init/01-schema.sql:20-31` 已给 `marketing@%`/`@localhost` 五库 ALL PRIVILEGES；但 `docker-compose.data.yml:31-38` 只挂 `init-lite/`，那里只 grant 单库 `marketing`（`:15,18`） | **取决于当初初始化用的哪套 init 目录**。④ 不能假设自己有跨库权限：探测失败要显式报"看不见"，而不是报 0（§4.1） |
| 3 | §7：`information_schema` 的先例是 `reset-demo-data.sh:29-31` | 那条先例已被 ③ T9 删掉（脚本改走后台端点）。现存先例是 `scripts/load-probe.sh:41` 与 `docker/mysql/migrate/2026-09-23-admin-config.sql:24-80` | 引用改指 `load-probe.sh:41` |
| 4 | §7：网关"429 计数"当作既有事实 | `RateLimitFilter` **没有任何 Micrometer 指标**，拒绝路径只有一条 `log.info`（`:95`）。母版事实 #2 说的"429 计数"其实是自动装配的 `http_server_requests_seconds_count{status="429"}`，它按 uri/status 分桶、**不区分被限的是哪个 route** | "哪个路由在被打爆"这件事现在读不出来。④ 要它就得在网关补 `marketing.gateway.rate.limit.rejected{route}`（§5，文件级新增，不动判定逻辑） |
| 5 | §9：SSRF/解析用 `MockRestServiceServer` 测 | 仓库里没有任何 `RestTemplate`/`TestRestTemplate` 用法；`MockRestServiceServer` 只能绑它 | 改用 **JDK `java.net.http.HttpClient`**（零新依赖，且 `Redirect.NEVER` 是一等能力），测试用 `com.sun.net.httpserver.HttpServer` 起真实回环端口——顺带能真的验"重定向被拒"，MockRestServiceServer 做不到 |
| 6 | §7 目标清单"照抄 prometheus.yml 的两个 job" | 两个 job 都是同一组 6 个端口（`prometheus.yml:10-23,31-42`），**没有 8085/standalone**；而 LITE 才是常态服役档 | ④ 的目标清单不能照抄它。清单要按形态给（§4.3），且必须能表达"LITE 下四个业务模块 + admin 全在一个 JVM 里，没有可抓的兄弟进程" |
| 7 | §7：分桶余量由 `DB 行 × buckets` 生成精确键 | `seckill:stock:{activityNo}:{bucket}`，bucket **从 1 开始**（`SeckillStockService.java:36-38,188-189`）；桶数权威值是 `seckill_activity.buckets` 列，全局默认 16 只是兜底（`SeckillProperties.java:16`、`SeckillRuntimeConfig.java:39-41`）；这些键的 TTL 复用了 `boughtMarkTtlSeconds`=86400（`SeckillStockService.java:65,69-70,91`） | 用全局 16 会在任何一行的 `buckets` 不等于 16 时**读出不存在的键**。逐行按列生成。TTL 那件事单列成风险（§7 #3） |
| 8 | §7：审计保留期交给 `AuditRetentionJob` | 代码里没有这个 job（只在母版里）。`admin_audit_log` 只有 `idx_actor_time` 与 `idx_resource`，**`create_time` 单列和 `action` 都没有索引**（`init/01-schema.sql:389-408`） | 保留期是"要新写的东西"而不是"既有 job 的读数"。按时间删在不带索引的列上=全表扫，得先补索引（§6）。**本段先只读不删**（§8 不做） |

还有一条不冲突但值得写下来的确认：`mkt:cfg:schema:{service}` 带 180s TTL、每 60s 重投
（`ConfigSnapshotPoller.java:31,38,141-142`）——**这个键在不在就是进程的存活信号**，④ 白捡一个
"谁还活着"的读数，不需要新机制。

## 2. 本段要回答的三个运维问题（其余都是这三条的展开）

1. **异步链路堵没堵？** `local_message` 各状态计数、LITE 的 `MKT_STREAM_*` XLEN/PEL、
   审计总线 `mkt:audit:pending` 的 XLEN、FULL 的 broker 深度（**明写不可见**，不填 0）。
2. **有没有在降级？** ⑤ 的 `degraded`/`ignored`/`foreign` 三个数、discount 的 `mkt.discount.degraded`
   与超时降级标记、`marketing.config.poll.error`、租约被抢的 `mkt.job.dedup_skipped`、
   重预热执行失败 `marketing.reheat.exec.failed`。
3. **缓存和账对不对得上？** 预算预扣缓存 vs DB 余额、券模板余量 vs 已发数、秒杀分桶余量 + 已售 vs
   总库存（与 `smoke-test.sh`、`reset-demo-data.sh` 同一个恒等式，**同一份公式只能有一处实现**）。

第 3 条是本段真正的增量：前两条在 Prometheus 里也能看，第三条不看不知道——它是地雷 A/E 的
"现在这一刻有没有偏"，而重预热是**修**它的手段，④ 是**发现**它的手段。

## 3. 落点与抽象

```
marketing-admin/src/main/java/com/example/marketing/admin/observe/
  MetricSource.java          // 接口：Map<String,String> scrape(String target) → 该进程的全部样本；抓不到抛 ScrapeException
  LocalMeterSource.java      // 注入 MeterRegistry，读自己的 registry（LITE/dev：一个 JVM 装着所有业务模块）
  ProxyMeterSource.java      // JDK HttpClient GET http://{host}:{port}/actuator/prometheus，禁重定向、256KB 截断
  PrometheusTextParser.java  // 文本 → 样本(name, tags, value)；只认 name{tags} value [ts] 三种行
  OpsSnapshotService.java    // 编排：读 Redis + 读 DB + 抓指标，拼一个 OpsSnapshotView
  OpsTargets.java            // 目标清单（yml 静态列表），以及"LITE 下这些 target 全在本地" 的映射
```

- **选择条件用属性而不是运行时猜**：`marketing.admin.metrics.mode` = `local` | `proxy`，
  standalone yml 显式写 `local`（它装着四个业务模块），admin 自己默认 `proxy`——与
  `marketing.mq.type: ${MQ_TYPE:redis-stream}`（`marketing-standalone/.../application.yml:51-52`）同一手法。
  两种模式**同时可用**是错的：LITE 下 proxy 去抓 8081-8084 会全都连不上，报出一堆假 error。
- **响应恒带 `mode` 与逐 target 的 `status`/`error`**。抓不到就写"抓不到"，不给一张看着正常的空大盘
  （母版 §7 的这条是整段最重要的纪律，也是本仓库吃过的那类"静默"）。
- 端点：`GET /api/admin/ops`（总览，任何后台角色可读）+ `GET /api/admin/ops/metrics?target=&name=`
  （定点窥视单个 target 的白名单指标）。角色沿用 `AdminRoles.OPERATIONAL`（admin+operator），
  读用 `require(request)` 不挑角色——与 `/audits`、`/config` 一致。
- **不进网关的新路由**：`/api/admin/ops` 已经在 `admin-route` 的 `Path=/api/admin/**` 里，
  且 ③ 给四个业务前缀加过路由的教训是"每条新前缀都要两处 profile 各加一条 + 进 rate-limit map"。
  ④ 全是后台只读，**故意不开新前缀**。

## 4. 具体口径

### 4.1 异步积压

| 读数 | 来源 | 形态差异 |
|---|---|---|
| `local_message` 按 status 计数（PENDING/SENT/CONFIRMED/FAILED） | `information_schema.tables` 找出**所有**含该表的库（`load-probe.sh:41` 同款 SQL），逐库 `SELECT status, COUNT(*) … GROUP BY status`，用 `JdbcTemplate`（admin 已有，`AdminConfigStore` 同风格） | 单库形态命中 1 个库；每服务一库命中 4 个（`marketing_admin` 无此表）。**跨库失败/无权限 → 该库标 `UNKNOWN` 并给原因，不并进去** |
| 死信 | 同上 `status='FAILED'` + 最老一条的 `biz_key`/`retry_count` | `FAILED` 是真会堵的：`LocalMessageService.java:118,124` 只打 log，没有指标 |
| Stream 积压（LITE/dev） | `XLEN` + `XPENDING` 的 summary，键 `MKT_STREAM_MKT_COUPON_GRANT`、`MKT_STREAM_MKT_SECKILL_ORDER`、`mkt:audit:pending`、`mkt:reheat:{type}:pending`（type 来自 `CacheReheatRegistry.types()` ∪ 已知键扫描） | FULL 下 `MKT_STREAM_*` 恒不存在 → 标 `NOT_APPLICABLE`（不是 0）。审计与重预热两条总线**三种形态都在** |
| RocketMQ broker 深度 | 读不到 | 恒 `NOT_APPLICABLE` + 一句"客户端读不到 broker 队列深度，要准数看 broker 控制台"——与 `load-probe.sh:12,17` 同口径 |

### 4.2 降级与兜底

直接取 §1 列出的那六个既有指标；**不新增业务侧指标**（除 §5 的网关拒绝计数）。
每个读数带"上一次的取值时间"，因为 `*MetricSource*` 是按需抓的、不是常驻的。

### 4.3 缓存 vs 账（本段的增量）

```
预算：activity.budget_amount*100 - Σ(扣款流水) + Σ(退款流水)  ==  activity:budget:{no}
券：  template.total_stock - issued                              ==  coupon:stock:{templateId}
秒杀：Σ(1..buckets 各桶余量) + sold_stock                        ==  seckill_activity.total_stock
```

- **判定长在 owning 模块里，④ 只读它的读数**。原先设想的是"④ 自己按 `CacheKeys` 生成精确键做
  MGET + 按 SQL 算期望值"，实施时否掉了：`marketing-admin` 不依赖任何业务模块（跨不过模块边界），
  要在 ④ 里做就必须把三套公式各抄一份 SQL——**两份口径早晚分叉，而分叉的表现是自检说一切正常**。
  最终形状：common 里一个 `CacheConsistency` 契约（与 `CacheReheater` 并列，`type()` 同名同源）+
  `CacheConsistencyRegistry` 把它绑成 gauge `marketing.cache.consistency{type}`；
  三个模块各自实现，复用的正是 `reheat` 已经在用的那一份权威算法
  （`BudgetService.computeRemainCents`、`CouponTemplateService.remainOf`、`SeckillWarmUpService.planFor/remainOf`）。
  ④ 无论 local 还是 proxy 模式都读同一个 gauge，不需要跨库权限、不需要新入站端点。
- 读数是**每条不符计数**（0 / N / **-1=判定不了**），不是逐键明细。逐键明细要么靠抄公式、
  要么靠给 owning 服务加后台可读端点（母版 §10 明列不做），两者都比"多一个数"贵。
  运维要定位到具体键时，`POST /api/admin/cache/reheat` 是按键的、幂等的，直接修就是。
- 抽样上限在各模块自己定（`CONSISTENCY_SAMPLE = 5`，注释写明"每条两次读"）：
  自检的取值时刻是"有人来抓"（④ 读、或 Prometheus scrape），不是常驻轮询——
  常驻会把 LITE 那台弱主机的空闲 CPU 吃在这件事上。
- 恒等式不成立时**不修**：读数报 N，并给一句"可用 `POST /api/admin/cache/reheat` 修正"。
  ④ 只读是它的价值前提；能改就变成第二个 ③。

### 4.4 进程存活与租约

- `mkt:cfg:schema:{process}` 在/不在（TTL 180s、每 60s 重投）→ 每进程的 liveness。
- `mkt:job:{task}` 三个租约键的"存在与否 + 剩余 TTL + 持有者值（UUID 前 8 位）"。
  剩余 TTL 接近 0 = 下一次可能被别的实例抢走，这是**正常**；`dedup_skipped` 计数器猛涨才是要看的东西。
  Redis 挂时 `RedisLeaseLock.java:45-49` 会**照常执行**（`acquired=true`）——所以"锁键不存在"≠"没人在跑"，
  这条必须写进读数说明，否则运维会在 Redis 抖动后误判成"job 全停了"。

### 4.5 审计表自身

`COUNT(*)`、最老一条的 `create_time`、按 action 的 top N（`JdbcTemplate` group by）。
用于回答"该不该跑保留清理"，以及 ③ 的 drain 是否把量堆到了不该去的地方。

## 5. 网关补一个拒绝计数（唯一的非 admin 改动）

`RateLimitFilter` 构造器加 `MeterRegistry`，在拒绝分支 `counter("marketing.gateway.rate.limit.rejected",
"route", route.getId()).increment()`。**不碰 Lua、不碰判定顺序、不碰 429 响应体**。
理由：这是唯一一处"④ 想看但任何指标都不存在"的读数，而 §1.4 说清了它不能被
`http_server_requests{status="429"}` 替代（没有 route 维度）。
放在本段末尾做，是因为它会改动 ③ 刚钉过的 74→82 基线之外的东西——加完要复跑网关那条反漂移单测。

## 6. 保留期：本段只让它"看得见"，不动删

`AuditRetentionJob` 与 `admin_audit_log` 的 `create_time` 索引**都不在本段**。
触发条件留给用户拍板：等表大到需要谈保留期的时候，一并给"加索引 + job + 天数取自 ⑤ + 删除条数落审计"
四件套，缺一条就不要动（只加 job 不加索引 = 每天一次全表扫）。见 §8。

## 7. 风险（本段自己带来的）

1. **④ 是新的攻击面**：它能被指向任意 host:port。红线（母版 §7）落成正面清单校验 + 单测：
   host 只允许 `^(marketing-[a-z]+|standalone|127\.0\.0\.1|mkt-[a-z-]+)$`、
   port 只允许 `{8081..8086,8090}`、path 恒为常量 `/actuator/prometheus`、
   `HttpClient.Redirect.NEVER`、响应 256KB 截断、只解析白名单指标名；
   用户可控的只有 `target`/`name` 两个字符串，不在清单里 → `40300`。
   **测试必须包含**：`127.0.0.1:3307`（MySQL 端口）、带 `@` 与 `/` 的 host、一个 302 到外部的桩。
2. **解析器写歪了会静默少报**：Prometheus 文本里 counter 带 `_total` 后缀、histogram 带 `bucket`/
   `_sum`/`_count`、还有 `# TYPE` 注释行。取样例文本做 fixture（不连真进程的单测），
   并对"名字对不上就报 missing 而不是 0"做断言。
3. **秒杀桶键的 TTL 复用 `boughtMarkTtlSeconds`（86400）**（`SeckillStockService.java:65,91`）。
   活动跑满一天以上且中途无流量时，桶键会先于活动过期 → C 端读余量拿到"键不存在"。
   这不是 ④ 引入的，但 ④ 的"缓存 vs 账"卡片**第一次会把它暴露出来**（桶全空 vs sold 不变）。
   本段只负责让它可见，改不改 TTL 语义另议（它牵动 ⑤ 的 `seckill.bought-mark-ttl-seconds`）。
4. **LITE 弱主机上多抓一次 = 多几毫秒**：抓取只在请求 `/api/admin/ops` 时发生、不常驻，
   每 target 解析结果限 64KB 且不驻留（母版 §9 末的内存约束），单 target 超时 1s、并发度 1。
5. **`local_message` 跨库计数在单库形态是白做的一步探测**：先查 `CURRENT_SCHEMA()` 里有没有这张表，
   有就直接查，没有再走 `information_schema`——省一次无谓的跨库权限依赖。

## 8. 明确不做

删审计行/归档、给审计表加索引、把 ④ 的读数写进 Prometheus（本段的存在前提就是不依赖它）、
新增业务侧指标（除 §5 一条）、把 `MeterRegistry` 提升成跨进程共享、任何缓存"自动修"、
新的网关前缀、P95/P99 直方图（要先给 `mkt.discount.calc` 配 `percentiles-histogram`，
那是 §9 之后的另一件事）。

## 9. 验收

| 层 | 内容 |
|---|---|
| 单测 | `PrometheusTextParserTest`（真样本 + `_total`/histogram/注释行/坏行）、`LocalMeterSourceTest`（SimpleMeterRegistry 取样，断言 counter 名字归一后仍读得到）、`ProxyMeterSourceTest`（`com.sun.net.httpserver` 起回环桩：正常/超时/500/重定向被拒/超 256KB 截断）、SSRF 白名单（含 `127.0.0.1:3307`、`a@b`、带 `/` 的 host、port 9999）、`OpsSnapshotServiceTest`（跨库发现：单库只命中一个；无权限时报 UNKNOWN 而不是 0；`NOT_APPLICABLE` 不被并成 0）、`CacheProbeKeyShapeTest`（**键形与业务侧逐字相等**） |
| 变异检查 | 至少四处：解析器改成"读不到给 0"、SSRF 白名单放开成"任何 host"、`mode=local` 时仍去 proxy、跨库探测失败被吞 |
| 冒烟 链路 7 | ① `GET /api/admin/ops` 200 且带 `mode`；② `local_message` 的 PENDING 计数 == `docker exec mysql` 直查同一条 SQL（**同式对拍**，不是断言它等于 0）；③ LITE 下 `MKT_STREAM_*` 的 XLEN 有值、FULL 下显式 `NOT_APPLICABLE`；④ 三个恒等式全部 `OK`，且**故意**用直连 SQL 把预算缓存改错 → ④ 报 `MISMATCH` → 重预热后回 `OK`（这条同时验 §4.3 的两端与"④ 不自己修"）；⑤ 白名单外的 target 参数 → `40300` |
| 形态 | 五套入口各复跑（新基数），LITE 内存 `docker stats` 复核（standalone 超 640 MiB 才动 mem_limit） |

## 10. 批次（写进实施计划，一段一段验）

1. **T1** `observe` 包骨架：`MetricSource`/`LocalMeterSource`/`PrometheusTextParser` + 三族单测（不接线）
2. **T2** `ProxyMeterSource`（JDK HttpClient + SSRF 白名单 + 截断/超时/拒重定向）+ 回环桩测试
3. **T3** 装配：`marketing.admin.metrics.mode` 属性、standalone 写 `local`、admin 默认 `proxy`；
   目标清单 yml；`AdminSecurityAutoConfiguration` 之外**另起**一个 `observe` 装配（admin 有 DataSource，
   网关没有——沿用 ⑤ 那条教训）
4. **T4** 异步积压读数（`local_message` 跨库 + Stream XLEN/PEL + broker `NOT_APPLICABLE`）
5. **T5** 缓存 vs 账（`CacheProbe` + 键形对拍单测 + 抽样上限）
6. **T6** 存活与租约 + 审计表自身统计；`OpsSnapshotView` 定稿；`GET /api/admin/ops`
7. **T7** 网关 `marketing.gateway.rate.limit.rejected{route}` + 反漂移单测复跑
8. **T8** 冒烟链路 7（五条断言）+ 五形态复跑 + README（新增"运维读数"一节、覆盖矩阵与计数刷新）+ 母版 §13 追加 ④ 偏离
