# ④ 运维只读聚合 · 实施计划

**Spec**：`docs/superpowers/specs/2026-09-24-ops-readonly-aggregation-design.md`
**分支**：`feat/ops-readonly-aggregation`（从 main 开，main = `f08d6f7` 之前的 `743f6aa` + spec）
**基线**（动手前的实测，别用记忆里的数）：单测 **272 用例 / 59 类** 全绿；冒烟 LITE/dev **81**、
FULL 三种入口 **82**；LITE standalone 常驻 **532.5 MiB / 768 MiB**。

## 全局约束（每个任务都受它管）

1. **构建前先 `source scripts/common.sh`**，否则 Homebrew JDK 26 会把 Lombok 打挂（本仓已踩过）。
   跑测试用 `mvn -o test`（离线，2-3 分钟）；只验一个类用
   `mvn -o -pl <module> test -Dtest=XxxTest`。
2. **每条断言都要有鉴别力**：每个任务收尾做一次变异检查（把生产码改回错的，断言必须红），
   并把结果写进本文件的"执行记录"。**做不到的断言宁可删掉**。
3. **改文档用 Edit 而不是脚本**（③ 的修正 #26：用 `str.replace` + 凭记忆写锚点 = 静默丢内容）。
   写进文档的数字必须来自命令输出。
4. **不直连 SQL 改共享种子数据做验证**；要改就用后台端点或改完还原（③ 的修正 #12/#13/#18）。
   冒烟登录口有每 IP 10 次/分钟限速，**两次冒烟之间隔 60s 以上**。
5. **④ 全程只读**：除了 T7 给网关加一个计数器、T5 的探针是纯 `MGET`，不许新增任何写路径、
   不许"顺手把不一致的数据修了"。
6. 提交信息用本仓风格：`feat(ops): ④ …` / `test(smoke): ④ …` / `docs: ④ …`。

## 阅读顺序提示

T1-T3 是"能读到别人的指标"，T4-T6 是"读到之后组一个视图"，T7 是唯一一处非 admin 改动，
T8 收口。T2 与 T4 无依赖，但 T4 的视图字段要等 T6 定稿，所以顺序做最省心。

---

### Task 1：observe 骨架 —— 样本模型、解析器、本地源

**Files**
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/observe/MetricSource.java`
- Create: `.../observe/MetricSample.java`
- Create: `.../observe/PrometheusTextParser.java`
- Create: `.../observe/ScrapeException.java`
- Create: `.../observe/LocalMeterSource.java`
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/observe/PrometheusTextParserTest.java`
- Test: `.../observe/LocalMeterSourceTest.java`

- [x] **Step 1** 先写 `PrometheusTextParserTest`（失败）：喂真样本，断言下面这些都必须成立

```java
// 关键 fixture：counter 的 _total 后缀、histogram 的派生线、# 注释、空行、坏行
private static final String SAMPLE = """
        # HELP marketing_audit_drained Total number of audit rows drained
        # TYPE marketing_audit_drained counter
        marketing_audit_drained_total 1.0
        mkt_job_dedup_skipped_total{task="local-message-retry"} 3.0
        mkt_job_dedup_skipped_total{task="seckill-timeout"} 1.0
        http_server_requests_seconds_count{method="POST",uri="/api/coupon/grant",status="200",outcome="SUCCESS"} 12.0
        mkt_discount_calc_seconds_count 9.0
        mkt_discount_calc_seconds_sum 0.0042
        mkt_discount_calc_seconds_max 0.0011
        marketing_config_snapshot_version{application="marketing-standalone"} 7.0
        this_is_not_a_metric
        broken{label="unclosed
        """;
```

断言：
1. 按**基名**取样：`parser.byName("marketing_audit_drained")` 命中 `..._total` 那行 ——
   记进 javadoc：**Micrometer 在 Prometheus 文本上给 counter 加 `_total`**，
   所以"名字对不上"必须是 `missing`（空列表）而不是 0。
2. `mkt_job_dedup_skipped` 两条都带 `task` tag，能分别按 tag 值取到 3.0 / 1.0。
3. `http_server_requests_seconds_count` 的四个 tag 全解析出来（值里的逗号在引号内，不能当分隔）。
4. histogram 派生的 `_count`/`_sum`/`_max` 各自独立成样本，基名分别是
   `mkt_discount_calc_seconds_count` 等（**不去掉后缀**，因为调用方要的就是这几条线）。
5. 两行坏数据被跳过且计入 `parser.malformedLines()` —— **不抛**。
6. `#` 注释行永远不是样本（哪怕长得像）。
7. `application` tag 被摘掉（`byName` 的结果里不带它）：它是来源标识，不是维度。

- [x] **Step 2** 实现：`MetricSample` + 解析器

```java
/** 一条 Prometheus 样本。tags 永不为 null，值保留原始 double（不做四舍五入：counter 差值要比对）。 */
public record MetricSample(String name, Map<String, String> tags, double value) {
}
```

`PrometheusTextParser`：只暴露 `static List<MetricSample> parse(String text)` 与
`static List<MetricSample> byName(List<MetricSample> all, String baseName)`（后者内部匹配
`baseName` / `baseName_total` / `baseName_*` 三种前缀 + 下一个字符必须是 `{` 或行尾），
以及实例方法 `malformedLines()`（简单点：把 malformed 做成 parse 的第二个返回值更啰嗦，
用一个不可变 `ParseResult(List<MetricSample> samples, int malformedLines)` 返回，
`byName` 收 `ParseResult`）。**手写解析，不引 prometheus simpleclient 的 parser**：
仓库已经带了 `micrometer-registry-prometheus`，但引它的 `TextFormat.parse` 会把整套
prometheus model 拉成 admin 的直接依赖，而我们要的只是"取几个数"。

- [x] **Step 3** `MetricSource` 接口 + 本地实现

```java
public interface MetricSource {
    /** "local" | "proxy"：响应里必须带它，否则运维无法判断这张大盘读的是谁 */
    String mode();

    /** 抓一个 target 的全部样本。抓不到抛 ScrapeException（原因进 message），绝不返回空列表冒充"没有指标" */
    ParseResult scrape(String target);

    /** 本模式能服务哪些 target（proxy 用不到，local 只有 "self"） */
    boolean serves(String target);
}
```

`LocalMeterSource` 注入 `MeterRegistry`，`mode()="local"`、`serves("self")`，
`scrape` 用 `registry.getMeter(MeterId)` 遍历 `registry.forEachMeter(...)` 现场渲染成
Prometheus 风格样本（counter → `name_total`、gauge → `name`、timer → `_count`/`_sum`/`_max`），
**复用同一份 name/tag 约定**，这样上层只有一条取值路径。tag 名直接取 Micrometer 的 key。

- [x] **Step 4** 跑两个测试类，`mvn -o -pl marketing-admin test -Dtest='*observe*'` 全绿后，
  做变异检查（至少两处）：① `byName` 去掉 `_total` 兜底 → counter 断言必红；
  ② 坏行改成"抛异常" → 容错断言必红。
- [x] **Step 5** 提交：`feat(ops): ④ 指标样本模型与 Prometheus 文本解析（含 _total 与坏行容错）`

---

### Task 2：ProxyMeterSource —— JDK HttpClient、SSRF 白名单、截断与超时

**Files**
- Create: `.../observe/ProxyMeterSource.java`
- Create: `.../observe/OpsTargets.java`（target 名 → host/port 的解析 + 白名单校验）
- Test: `.../observe/ProxyMeterSourceTest.java`（`com.sun.net.httpserver.HttpServer` 起回环桩）
- Test: `.../observe/OpsTargetsTest.java`（SSRF 正面清单）

- [ ] **Step 1** `OpsTargetsTest` 先红。规则必须**正面清单**而不是黑名单（"排除内网"这类
  黑名单写法漏一个 127.0.0.1 就完了）：

```java
// 允许：marketing-(activity|coupon|discount|seckill|admin|gateway|standalone) 与 mkt-[a-z-]+ 与 standalone 与 127.0.0.1
// 端口只允许 {8081..8086, 8090}；path 恒为常量，不接受入参
// 必须拒：127.0.0.1:3307（MySQL）、127.0.0.1:6379（Redis）、任何外站、
//         "a@evil"（userinfo）、"x/../../etc"（path 注入）、"marketing-activity:9999"（端口越界）、
//         空 target、大小写变体（MARKETING-ACTIVITY 也应拒——避免下游 host 解析差异）
```

- [ ] **Step 2** `ProxyMeterSource`：

```java
private static final Duration TIMEOUT = Duration.ofSeconds(1);
private static final int MAX_BYTES = 256 * 1024;   // 母版 §7；spec §3 的 64KB 是解析后的驻留上限
private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)      // 白名单之外的重定向就是 SSRF 通道
        .connectTimeout(TIMEOUT).build();
```
- 只 `GET http://host:port/actuator/prometheus`（path 是常量）；
- 状态码非 2xx → `ScrapeException("HTTP " + code)`；3xx → 因为 NEVER，
  HttpClient 直接把 3xx 交回，判成 `ScrapeException("redirect refused")`；
- 读 body 用 `body().ofByteArray()` 之后再判长度？不行（大响应会先吃进堆）：
  用 `ofInputStream()` + 最多读 `MAX_BYTES`，超了就 `ScrapeException("truncated over 256KB")`
  ——**截断必须报错而不是静默半份**，半份文本会被解析成"少了几个 target"，那正是本段最不该有的错。
- 任何 `IOException`/`InterruptedException` → `ScrapeException` 并把 cause 摘要进 message。
  超时后 `Thread.currentThread().interrupt()` 保持中断位干净（只在异常分支做，别把中断吞了）。

- [ ] **Step 3** `ProxyMeterSourceTest` 用 `HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)`
  起四个 handler：正常返回样本、返回 500、返回 302 到 `http://127.0.0.1:1/`（断言**没有**第二请求，
  用一个计数 handler 挂在目标路径上验证它没被调到）、返回 300KB 大 body。
  这是选 JDK HttpClient 而非 `RestTemplate` 的回报：重定向被拒这件事在这里是真的被验了。
  注意端口是 0 分配的随机口 —— `OpsTargets` 的端口白名单在测试里通过
  `new ProxyMeterSource(hostResolver, port -> true)` 之类的能力注入口绕过？不要：
  改为 `OpsTargets` 允许 `127.0.0.1` + 全端口段太宽。正确做法：测试用**白名单内的端口**跑桩
  （从 8081..8090 里 `findFreePort` 优先试），或让桩直接绑 8090。
  **不要为测试放宽生产规则。**
- [ ] **Step 4** 变异检查两处：① `Redirect.NEVER` → `ALWAYS`，重定向断言必红；
  ② 白名单端口段改成"任意"，SSRF 断言必红。
- [ ] **Step 5** 提交：`feat(ops): ④ 跨进程指标抓取（JDK HttpClient + 正面清单 SSRF，拒重定向）`

---

### Task 3：装配 —— mode 属性、目标清单、另起一个不带 DataSource 条件的自动装配

**Files**
- Create: `.../observe/OpsProperties.java`（`@ConfigurationProperties(prefix = "marketing.admin.ops")`）
- Modify: `marketing-admin/src/main/java/com/example/marketing/admin/config/AdminSecurityConfig.java`
  （`@EnableConfigurationProperties` 增列）
- Create: `marketing-admin/src/main/resources/` 的 yml 段（`marketing.admin.ops.*`）
- Modify: `marketing-standalone/src/main/resources/application.yml`（显式 `mode: local` + targets）
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/observe/OpsAssemblyTest.java`
  （`ApplicationContextRunner`）

- [ ] **Step 1** 属性形状（照 `AdminProperties` 的写法：字段 + 默认值，不用 `@DefaultValue` 注解）：

```yaml
marketing:
  admin:
    ops:
      metrics-mode: ${ADMIN_METRICS_MODE:proxy}   # standalone 里显式写 local
      consistency-sample-size: 20
      targets:                                     # 名字 → host:port；只接受白名单内值
        marketing-activity: marketing-activity:8081
        marketing-coupon: marketing-coupon:8082
        marketing-discount: marketing-discount:8083
        marketing-seckill: marketing-seckill:8084
        marketing-gateway: marketing-gateway:8090
        self: 127.0.0.1:8086
```
standalone 那份的差异：**只有 `self` 一个 target**（`127.0.0.1:8085`），
因为四个业务模块 + admin 全在这个 JVM 里，写五条 8081-8084 就是在制造 5 条假 error。
（母版 §7 的"目标清单照抄 prometheus.yml"作废：那两个 job 都没有 8085，见 spec §1.6。）

- [ ] **Step 2** `OpsAssemblyTest` 三条断言（先红）：
  ① `ADMIN_METRICS_MODE` 缺省时注入的是 `ProxyMeterSource`；
  ② 设成 `local` 时是 `LocalMeterSource`，且 **proxy bean 不存在**（两套同时在 = LITE 下必然一半读数假）；
  ③ yml 里出现白名单外的 target（例如 `x: evil.com:80`）时**启动期失败**，
  message 点名是哪个条目——配置错要响在启动，不是响在第一次点大盘。
- [ ] **Step 3** 实现 + 复跑全量 `mvn -o test`（新增的 bean 不能把 ⑤ 的
  `AdminSecurityAutoConfigurationTest`、standalone 的 `StandaloneComponentScanTest` 挤红）。
- [ ] **Step 4** 提交：`feat(ops): ④ 指标源按属性装配，LITE 只留 self 一个 target`

---

### Task 4：异步积压读数 —— local_message（含跨库发现）、Stream 长度、broker 不可见

**Files**
- Create: `.../observe/BacklogStore.java`（JdbcTemplate，风格照 `AdminConfigStore`）
- Create: `.../observe/StreamDepth.java`（`StringRedisTemplate` 读 XLEN / XPENDING）
- Test: `.../observe/BacklogStoreTest.java`（H2 `MODE=MySQL` 真 SQL）
- Test: `.../observe/StreamDepthTest.java`（mock `StringRedisTemplate`）

- [ ] **Step 1** `BacklogStoreTest` 先红，四条：
  ① 单库形态 `schemas()` 只回一个，且就是当前连接库（**先探 `CURRENT_SCHEMA()` 里有没有该表，
  有就不走 information_schema**——spec §7.5 省掉一次无谓的跨库权限依赖）；
  ② 每库的 `statusCounts()` 返回 `Map<String,Long>`，PENDING/SENT/CONFIRMED/FAILED 之外出现过的
  状态值也要原样带出（不要白名单，白名单会静默丢掉新状态）；
  ③ 库能发现但查询被拒（H2 里模拟：造一个只有表名在 information_schema 可见、实际无权访问的情形
  做不到 → 改为**注入一个会抛的 JdbcTemplate 替身**）时，该库标 `UNKNOWN` + 原因，
  **其他库的值仍照常汇总**；
  ④ 没有任何库含该表 → 结果是空列表 + 一句原因，不是 0 条 PENDING。
- [ ] **Step 2** SQL（口径抄 `LocalMessageService.java:99-103` 的判定，别另发明一套）：

```java
"SELECT status, COUNT(*) FROM " + schema + ".local_message GROUP BY status"
"SELECT MIN(create_time), MAX(create_time) FROM " + schema + ".local_message WHERE status IN ('PENDING','SENT')"
```
`schema` 只能来自 `SELECT DISTINCT table_schema FROM information_schema.tables WHERE table_name = 'local_message'`
的结果集，**不接受任何外部输入**（拼表名进 SQL 是本段唯一可能的注入面，写死这条来源）。
死信另取一条：`SELECT topic, biz_key, retry_count FROM <schema>.local_message WHERE status='FAILED' ORDER BY id LIMIT 5`。
- [ ] **Step 3** `StreamDepth`：`XLEN` 用 `opsForStream().opsForXInfo`? 没有 ——
  实际用 `execute(RedisCallback)` 走 `XLEN`/`XPENDING key group`（③ 的 T7/T8 已经用过 `execute` 的路子，
  沿用它，别引新 API）。返回 `record StreamDepthView(String key, long len, long pending, boolean applicable, String note)`。
  topic 键从 `RedisStreamEventPublisher.STREAM_KEY_PREFIX` 与 `MqTopics` 常量拼，**不写字面量**；
  `MQ_TYPE=rocketmq` 时（读 `marketing.mq.type` 属性即可）两条 `MKT_STREAM_*` 直接
  `applicable=false` + note 写"本形态走 RocketMQ，broker 队列深度客户端读不到"。
- [ ] **Step 4** 变异检查两处：① 跨库失败被吞成 0 → ③ 号断言必红；② `NOT_APPLICABLE` 被并成 0 →
  ④ 号断言必红。
- [ ] **Step 5** 提交：`feat(ops): ④ 积压读数（local_message 跨库探测 + Stream 深度，不可见不填 0）`

---

### Task 5：缓存 vs 账 —— 本段的真正增量

**Files**
- Create: `marketing-common/src/main/java/com/example/marketing/common/cache/CacheKeys.java`
- Modify: `marketing-activity/.../service/BudgetService.java`（`:190` 的键改走 CacheKeys）
- Modify: `marketing-coupon/.../service/CouponStockService.java`（`:105` 同上）
- Modify: `marketing-seckill/.../service/SeckillStockService.java`（`:36-38` 与 `keysOf` 同上）
- Create: `.../observe/CacheProbe.java`
- Create: `.../observe/ConsistencyStore.java`（DB 侧期望值，JdbcTemplate）
- Test: `.../observe/CacheProbeKeyShapeTest.java`（**与业务侧键形共用同一份函数**）
- Test: `.../observe/ConsistencyTest.java`（H2 + mock 的 Redis）
- Test: 三个业务模块各补一条"写进 Redis 的键 == CacheKeys.xxx"的断言

- [ ] **Step 1** `CacheProbeKeyShapeTest` —— 这是本任务的全部价值所在，先写它。
  **前提修正**（实施前实测）：`marketing-admin` 不依赖任何业务模块，所以 ④ 拿不到
  `SeckillStockService.keysOf(...)`（它确实是 public，在 `:186`，但跨不到模块）。
  做法照 ⑤ 已有的先例 —— `common/transport/StreamKeys.java` 就是为了"键形只有一份"而存在的：
  **新建 `marketing-common/.../cache/CacheKeys.java`**，三个纯函数：
  `budget(String activityNo)`、`couponStock(Object templateId)`、`seckillBucket(String activityNo, int i)`，
  然后 `BudgetService:190`、`CouponStockService:105`、`SeckillStockService:36-38` 三处改为调它
  （**行为零变化**，每个模块的既有单测就是回归网），④ 也调它。
  断言两件事：① 三个函数生成的键与"业务服务实际写进 Redis 的键"一致（在各自模块的单测里断言
  `verify(valueOps).set(CacheKeys.budget("ACT1"), ...)` 这种形式）；
  ② 秒杀桶序号**从 1 开始**、上界是**该活动行的 `buckets` 列**（全局默认 16 只兜 null）。
  键形漂移在编译期就没了，而这正是 ④ 自己最大的风险。
- [ ] **Step 2** 恒等式（`ConsistencyStore`）：

```
预算 期望 = activity.budget_amount * 100 - Σ(budget_record 扣) + Σ(退)   // 与 BudgetService 的 reheat 公式同式
券   期望 = coupon_template.total_stock - issued
秒杀 期望 = seckill_activity.total_stock - sold_stock
```
  预算那条公式**已经有一份**在 `BudgetService.reheat` 里（`CacheReheater` 的 "budget" 实现）。
  ④ 不能再抄一份——抄两份就是下一个地雷。做法：`BudgetService` 暴露
  `public long expectedRemainCents(String activityNo)`（reheat 内部改调它），④ 调它拿期望值，
  再 `MGET` 实际值比对。**"公式只能有一处"写进 javadoc 与单测**。
- [ ] **Step 3** 输出形状：

```java
public record ConsistencyView(String kind, String key, Long expected, Long actual,
                              String status,          // OK | MISMATCH | MISSING_KEY | NOT_SAMPLED
                              String note, long ttlSeconds) {
}
```
  `MISSING_KEY` 单列（不是 0 也不是 MISMATCH）：键不存在 = 从没预热或已过期，
  修法与"值不对"不同（前者要 warm，后者要 reheat）。秒杀那条顺带把**剩余 TTL** 报出来——
  spec §7.3 的 86400s 桶 TTL 就靠这一列被看见。
  抽样：`ORDER BY id LIMIT ops.consistency-sample-size`，note 明写"抽样"。
- [ ] **Step 4** 变异检查两处：① 把 `MISSING_KEY` 并成 `MISMATCH` → 断言必红；
  ② 把"两处公式合一"改回抄一份 → `expectedRemainCents` 那条一致性单测必红。
- [ ] **Step 5** 提交：`feat(ops): ④ 缓存与账的恒等式对拍（公式单点、键形共用静态方法）`

---

### Task 6：总览视图与端点 —— `GET /api/admin/ops`

**Files**
- Create: `.../observe/OpsSnapshotService.java`
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/controller/AdminOpsController.java`
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/dto/OpsSnapshotView.java`（+ 若干子 record）
- Test: `.../controller/AdminOpsControllerTest.java`、`.../observe/OpsSnapshotServiceTest.java`

- [ ] **Step 1** 视图（record 树，字段即 spec §2 的三问 + §4.4/§4.5）：

```java
public record OpsSnapshotView(
        String mode, String ownForm, Instant takenAt,
        List<TargetView> targets,          // name, status(OK|ERROR|NOT_APPLICABLE), error, sampleCount
        BacklogView backlog,               // localMessage(按库), streams, rocketmqNote
        List<DegradedView> degraded,       // name, value, source, at
        List<ConsistencyView> consistency,
        LivenessView liveness,             // processes(schema 键在不在), jobs(租约键 + 剩余 TTL + holder 前 8 位)
        AuditTableView audit,              // rows, oldestAt, topActions
        List<String> notes) {              // 人读的口径说明（"抽样"、"锁键不存在≠没人跑"…）
}
```
- [ ] **Step 2** 两个断言先写：
  ① 某个 target 抓取失败时，`targets` 里那条是 `ERROR` + 原因，而**其余读数照常返回**
  （一个 target 挂掉不能把整张大盘变 500，也不能变空）；
  ② `GET /api/admin/ops` 无凭证 → 40100；只读角色可读（它是只读面）。
- [ ] **Step 3** 实现（并发度 1、串行抓；`takenAt` 用注入的 `Clock`，测试用固定时钟）。
  `liveness.notes` 里必须写 spec §4.4 那句：**Redis 抖动时 `RedisLeaseLock` 会照常执行**，
  所以锁键缺失不等于"没人在跑"。
- [ ] **Step 4** `AdminOpsControllerTest`（standaloneSetup + mock）：断言 41010 **不出现**在只读面
  （④ 不分形态可用，只是 target 集不同——这是对 ③ 那条"只在某档可用要显式报错"纪律的边界确认：
  确实可用的东西不要报错）。
- [ ] **Step 5** 提交：`feat(ops): ④ GET /api/admin/ops 只读总览（逐 target 状态，不整片失败）`

---

### Task 7：网关补 route 维度的拒绝计数

**Files**
- Modify: `marketing-gateway/src/main/java/com/example/marketing/gateway/filter/RateLimitFilter.java`
- Create: `marketing-gateway/src/test/java/com/example/marketing/gateway/filter/RateLimitFilterTest.java`
  （**这个 filter 至今没有测试类**，本任务顺手补最小的一条）

- [ ] **Step 1** 测试先红：构造 filter（`SimpleMeterRegistry`），走一次"Lua 返回 0"的分支，
  断言 `marketing.gateway.rate.limit.rejected{route="seckill-route"}` 计数 1，
  且 429 响应体**逐字节不变**（`code` 42900、`data.queueCode` 前缀 `Q`）。
  **手法照同目录的 `AuthFilterTest`**（实测：`marketing-gateway/pom.xml` 里**没有** reactor-test，
  既有反应式测试全部用 `MockServerWebExchange` + `.block()` + Mockito 桩 `chain.filter(any())`，
  `AuthFilterTest.java:31,50`）——不为了一个计数器往测试里引新依赖。
- [ ] **Step 2** 生产改动只有两处：构造器加 `MeterRegistry`，拒绝分支加一行 `counter(...).increment()`。
  **不动 Lua、不动判定顺序、不动响应体**（spec §5）。
- [ ] **Step 3** 复跑网关模块测试 + ③ 的 `GatewayConfigDefinitions` 反漂移测试。
- [ ] **Step 4** 提交：`feat(gateway): ④ 限流拒绝补 route 维度计数（判定逻辑零改动）`

---

### Task 8：冒烟链路 7 + 五形态复跑 + 文档收口

**Files**
- Modify: `scripts/smoke-test.sh`（新增链路 7，五条断言；位置在链路 6 之后、收尾之前）
- Modify: `README.md`（④ 一节、API 表加 `/api/admin/ops`、覆盖矩阵、计数）
- Modify: `docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md`（§13 追加 ④ 偏离）

- [ ] **Step 1** 链路 7 五条（基线 81/82 → 86/87）：

```bash
head2 "链路 7：运维只读聚合（同式对拍积压 → 不可见不许填 0 → 恒等式能看见 MISMATCH）"
# ① 总览可读且带 mode：没有 mode 就等于"不知道这张大盘读的是谁"
OPS=$(curl -s -m 20 -H "$AAUTH" "$GW/api/admin/ops")
expect "运维总览可读且自报指标源模式" '"mode":' "$OPS"
# ② 同式对拍：④ 报的 PENDING 必须等于脚本自己直连 MySQL 查同一条 SQL
#    断言它等于 0 是假信号（跑起来就有 in-flight），断言"两处相等"才是真信号
MYPEND=$(mysql_admin -N -e "SELECT IFNULL(SUM(status IN ('PENDING','SENT')),0) FROM ${MYSQL_DB:-marketing}.local_message" 2>/dev/null | tr -d '\r')
expect "④ 的积压计数与直连 SQL 同式相等" "\"pendingSent\":${MYPEND}" "$OPS"
# ③ 通道差异：LITE 有 Stream 深度，FULL 显式 NOT_APPLICABLE
# ④ 恒等式：先把预算缓存改错（只读面不许修，所以 MISMATCH 必须持续存在），重预热后回 OK
# ⑤ 白名单外的 target → 40300
```
 ④ 的具体做法：`mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET budget_amount = budget_amount + 1 WHERE activity_no='$ACT_NO'"`
 （与链路 4 同一手法），然后**不**重预热，断言 `/api/admin/ops` 里那条预算恒等式是 `MISMATCH`；
 再 `POST /api/admin/cache/reheat?type=budget` → 断言回 `OK`。
 这一条同时验了"④ 只读"与"③ 能修"，是本段最有价值的断言。
 ③/⑤ 的判据按形态分岔（读 `/cache/types` 那套既有做法），不猜环境变量。
- [ ] **Step 2** `bash -n scripts/smoke-test.sh`；LITE 真跑一遍，逐条看红。
- [ ] **Step 3** 五形态复跑：A LITE → B FULL 进程（**中间不清 SQL**）→ C FULL 容器（deploy-full 后等
  broker 一分钟）→ D dev → E 每服务一库（`MYSQL_DB=marketing_activity`）。
  每格记录：断言数、`mode` 取值（A/D 应 `local`，B/C/E 应 `proxy`）、standalone `docker stats`。
- [ ] **Step 4** README：新增"运维读数（④）"一节（三问 + 五形态各自的 target/mode 差异 +
  为什么"不可见不填 0"）；API 表加两行；覆盖矩阵五格刷新；计数改成 T8 实测值。
- [ ] **Step 5** 母版 §13 追加 ④ 偏离（至少：`information_schema` 只在第五套形态需要、
  网关没有 route 维度 429 计数、prometheus.yml 无 LITE 目标、桶数取列不取全局、
  保留 job 与索引推迟、解析用 JDK HttpClient + 回环桩替掉 MockRestServiceServer）。
- [ ] **Step 6** 提交：`docs: ④ 口径收口（运维读数一节 + 五形态复跑记录）`

---

## 执行记录（实施时逐条填，数字必须来自命令输出）

| 任务 | 交付 | 新增用例 | 实测证据 |
|---|---|---|---|
| T1 | `MetricSample`/`ParseResult`、`PrometheusTextParser`（`_total`、派生线白名单后缀、引号内逗号与 `}`、坏行计数、摘 `application`）、`ScrapeException`、`MetricSource`、`LocalMeterSource`（复用 `PrometheusMeterRegistry.scrape()` 走同一个解析器） | 16 | admin 模块 54 用例全绿（原 38）；变异检查四处全咬：去 `_total`、坏行改抛异常、派生后缀不校验、不摘 `application` |

## 落地时对计划的修正

1. **`LocalMeterSource` 不自己渲染样本**（计划 Step 3 写的是"现场渲染成 Prometheus 风格样本"）：
   那样就有两份"Micrometer → 线格式"的命名规则会漂，而漂的表现正是本段最不能有的"读不到"。
   实际做法是要求注入的 registry 是 `PrometheusMeterRegistry`，拿它 `scrape()` 出来的文本
   交给**与跨进程抓取同一个解析器**；不是这个实现就抛 `ScrapeException`（不返回空结果冒充"没指标"）。
   顺带两枚实测坑：micrometer 1.12.5 的包名是 `io.micrometer.prometheus`（不是 1.13 的
   `prometheusmetrics`），且 `PrometheusNamingConvention.defaultNaming()` **不存在**，
   构造用 `new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)`。
2. **timer 的基名带单位后缀**：代码里叫 `mkt.discount.calc`，线格式是
   `mkt_discount_calc_seconds_{count,sum,max}`。所以取 timer 必须写 `mkt.discount.calc.seconds`。
   没有让解析器去猜后缀（猜错的表现是静默少一条线），而是加了一条断言钉住"按代码里的名字取 = 空列表"。
3. **micrometer 1.12 在 tag 列表尾部多打一个逗号**（`mkt_job_dedup_skipped_total{task="x",}`）。
   解析器按"引号外逗号才是分隔符"实现，天然容住，但这条单独写了 fixture 测试：
   它一旦被当成坏行，**所有带维度的计数会静默消失**，而大盘照样是绿的。
