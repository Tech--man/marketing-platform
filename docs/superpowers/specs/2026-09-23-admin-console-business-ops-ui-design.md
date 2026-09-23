# 管理后台 ③④⑤⑥ 设计（业务面 / 运维只读 / 在线配置 / SPA）

- 日期：2026-09-23
- 状态：已批准（作为 ③④⑤⑥ 各段 spec 的共同上游）
- 上游：`2026-09-22-admin-console-foundation-auth-design.md`（①地基 + ②账号体系，已交付并五形态实测）
- 交付顺序：**⑤ 在线配置 → ③ 业务面 → ④ 运维只读 → ⑥ SPA**。每段各出一份 spec + 一份实施计划，本文件只定边界与契约，不定实施批次。

## 1. 背景与要解决的问题

服务端已有 LITE 服役档 / FULL 扩容档双形态和完整业务链路，但**运营与运维没有可操作面**：

| 动作 | 今天的做法 | 代价 |
|---|---|---|
| 改限流阈值 | 改 `application.yml` 或 compose 环境变量 → 重启 | 大促当天不敢改 |
| 改灰度比例 | 手发 SQL 或改 yml（且只有 nacos profile 才宣称能推） | 全仓零 `@RefreshScope`，实际要重启 |
| 抬演示库存 | `scripts/reset-demo-data.sh`（含三条形态分支 + 重启服务） | 脚本里最脆的一段 |
| 改活动预算 / 建活动 / 改规则 | `docker exec mysql` 手发 SQL，或走 **C 端共享 demo token** 的写接口 | 一个是无痕操作，一个是权限空洞 |
| 看积压/在途/TTL | `docker exec` 查库、`redis-cli` 扫键 | 出了故障只能 SSH |

①② 已把"谁能进后台、做了什么都留痕、刷缓存有统一入口"打完。本文件覆盖剩下四段，使后台成为运营与运维的日常入口，同时**不回头改 ①② 与 marketing-admin 的内部结构**（①② spec §3 承诺的"直接往上叠"）。

## 2. 已拍板的四个方向

1. **⑤** 通用 `admin_config` 表 + **白名单注册**；广播沿用 `discount:rule:version` 的"写 Redis + 各实例轮询重建"。
2. **④** admin **直读 DB/Redis + 代理各服务 `/actuator/prometheus`**，不依赖 Prometheus（LITE/dev 没部署它）。
3. **⑥** Vue3 + Vite，本地/CI 构建、`dist` 入仓（不进 maven 生命周期），静态文件由 **marketing-admin 的 classpath `static/`** 发出，**零新增常驻进程**。
4. **③** 配置类写操作**只留 `/api/admin/**`**，C 端不再能写配置。

## 3. 设计前必须承认的 8 条事实（逐条在代码里核实过）

这八条都推翻了我最初的某个假设，因此先列出来——后面每条设计都是在它们之上成立的，不是巧合。

1. **限流阈值不是"只在网关 yml"，而是按形态覆盖**：`docker/docker-compose.preview.yml:83-86` 把 `RL_COUPON 1000→120`、`RL_SECKILL 200→120`、`RL_ACTIVITY 500→200`、`RL_DISCOUNT 2000→500`（LITE 单机异步排空实测 ≈110-140 msg/s/topic）。而 LITE 与 FULL **共用同一份 MySQL**（`docker-compose.full-app.yml:39` `MYSQL_DB: marketing`）。→ 不分形态的一个配置值必然互相踩：LITE 的保守值会掐死 FULL 的吞吐，反之把洪流灌进单机。
2. **网关在任何形态都是独立进程**，它的 `http_server_requests` 与 429 计数**不在** standalone 的 `MeterRegistry` 里 → ④ 即使在 LITE 也需要 HTTP 抓取，不能全靠本地注入。
3. **静态文件放在 admin classpath 不等于能访问**：`AdminAuthFilter.java:70` 只处理 `/api/admin/` 前缀，而 `AuthFilter.java:52` 对所有非白名单路径要 C 端 demo token（白名单当前只有 `/actuator/**`，`marketing-gateway/src/main/resources/application.yml:55-56`）→ 必须有独立 `/ui/**` 路由并加白名单；并且 `ui-route` **同时**要进 `rate-limit` map，因为"route 不在 map 里 = 完全不限流"（`RateLimitFilter.java:55-58`）。
4. **登录限速会掐住自动化脚本**：`LoginGuard` 每 IP 默认 10 次/分钟且**含成功尝试**（`marketing-admin/src/main/resources/application.yml:47`），`smoke-test.sh` 现有 3 处 admin 登录 → ③ 把更多链路搬到后台后必须**全脚本共用一次登录、复用一枚 token**。
5. **单测基线是 25 类 / 106 个 @Test**（`find` 计数，README:397 一致），smoke 基线 52 条断言、五形态各全绿。
6. 仓库还有 `marketing-open-api`（风控/分销/ROI 占位，root `pom.xml:16`）。⑤ 不碰它——它没有真实落点，做成"在线可改"会是空壳。
7. **④ 在每服务一库档会静默少报**：admin 的 DataSource 指向 `marketing_admin`（`marketing-admin/src/main/resources/application.yml:10`），其余 4 个库的 `local_message` 看不见 → 需 `information_schema` 发现 + 跨库限定名查询（先例 `scripts/reset-demo-data.sh:29-31`）并补 `GRANT`。
8. **网关拿不到 DB**：`MarketingCommonAutoConfiguration` 整体挂 `@ConditionalOnClass(DataSource) + @ConditionalOnBean(DataSource)`（:49-50），且网关只有 reactive 模板（`marketing-gateway/pom.xml:31-33`）→ ⑤ 的轮询件必须**另起一个不带 DataSource 条件的自动装配**，且实现只能用 `ReactiveRedisTemplate`，不为它引阻塞客户端。

## 4. 总体架构

```
              运维浏览器（Vue3 SPA；静态资源在 admin classpath）
                     │  Bearer <后台 JWT>，仅对 /api/admin/** 要求
   /ui/**  ──────────┤← 网关白名单 + 独立限流桶，取静态不需要任何凭证
                     ▼
   gateway   -110 AdminAuthFilter → -100 AuthFilter → -50 RateLimitFilter
              │ 读 mkt:cfg:snapshot:{form}（无 DB，只吃自包含快照 + 逐条校验）
              ├─ /api/admin/{auth,users,sessions,audits,cache,config,observe}/** → marketing-admin:8086
              └─ /api/admin/{activities,coupon,discount,seckill}/**  → 各 owning 服务（§6.0）
              ▼
   marketing-admin ──写──► MySQL：admin_config / admin_audit_log
              │ DB 事务提交后 SET snapshot + SET version；这两步失败 → 41009
              └─Redis：mkt:cfg:{snapshot|version|schema}:{form|service}
                       mkt:reheat:{pending|ack}
                     ▲ 各服务 5s 轮询 version，变了再取快照；灰度类冷启动回源 DB
```

两条不变量：

- **admin 绝不 import 业务模块**。跨模块能力一律靠"各服务把自己的元数据自述到 Redis"聚合，与 `AdminCacheController.java:60-71` 用 `registry.types()` 判能力同源。
- **配置类写操作只有一条路**：`/api/admin/**`（网关双 token 分区已经在①②把住），避免出现"两套写入口、校验与审计各自漂移"。

## 5. ⑤ 在线配置下发

### 5.1 数据模型

```
admin_config(
  id, cfg_key VARCHAR(64), form VARCHAR(16), cfg_value VARCHAR(255),
  version BIGINT, updated_by VARCHAR(64), remark VARCHAR(255),
  create_time, update_time,
  UNIQUE KEY uk_key_form (cfg_key, form))
```

- `form ∈ GLOBAL | LITE | FULL | DEV`。**"scope"这个词不用**：它在本仓库已被 `BizKey` 的作用域占用，再借一次一定有人读错。
- 解析优先级：`当前 form 的值 > GLOBAL 的值 > 代码出厂默认`。**删掉一行就是回到出厂默认**（"恢复出厂"是删行而不是写回原值，否则 yml 改了值会永远被一行陈旧 DB 值压住）。
- `version` = 该 form 快照的单调递增序号，由写路径在同一事务内取 `MAX(version)+1` 得到，逐行冗余存一份（只为展示"这条是第几版生效的"）；Redis 快照里的 version 是该 form 的全局版本。
- DDL 进 `docker/mysql/init/01-schema.sql` 与 `init-lite/01-schema-lite.sql` 两份，另给已建卷的实例一份 `docker/mysql/migrate/2026-09-23-admin-config.sql`。隔离档必须补 `CREATE DATABASE marketing_admin` + **两条 GRANT**（`@'%'` 与 `@'localhost'`）——①② 实施时就是漏 `CREATE DATABASE` 让整段初始化中断过的地方。

### 5.2 形态标识从哪来

新增环境变量 `DEPLOY_FORM`：`deploy-preview.sh` 置 `LITE`、`start-dev.sh` 置 `DEV`、`start-all.sh`/`deploy-full.sh` 置 `FULL`。**未设置时按"只有 GLOBAL"解析**，即行为与今天完全一致（这是有意的：不部署新 env 就等于没有这套机制，不会出现"升级镜像后阈值莫名变了"）。

### 5.3 Redis 载荷：单键全量快照

```
mkt:cfg:snapshot:{form}  STRING(JSON) = {version, generatedAt, entries:{key:{value, type, defVer}}}
mkt:cfg:version:{form}   STRING(INT)
mkt:cfg:schema:{service} STRING(JSON) = 该服务自述的可改参数清单
```

不做"每参数一键 + 一个版本键"：(a) 网关无库，载荷必须自包含；(b) N 个键存在半应用窗口——改了 A 又改 B，读方可能拿到一新一旧；一次 `GET` 拿整份是原子的。

### 5.4 回退按"缺值是否安全"分类，不按归属分

| 参数类 | 快照丢失 / 解析失败 / 值越界时 | 理由 |
|---|---|---|
| 限流阈值 | `FALLBACK_YML`：逐条忽略，退回该 form 的 yml 出厂值；**绝不过限，也绝不让网关拒绝服务** | 阈值配错最多是放行偏宽或偏紧，起不来才是事故 |
| 灰度 percent / whitelist | **不允许缺值**：真值落 DB 列（`activity` 新增 `gray_percent`、`gray_whitelist`），Redis 只当变更通知，冷启动回源 DB 重建（沿用 `RuleCacheManager.java:40-53`，异常返 -1 继续用旧快照 :68-76） | `GrayService.java:24-27` 的"无规则=全量放行"是 smoke 断言行为（`smoke-test.sh:61-62`）。只存 Redis 的话，Redis 一旦被清，曾设 5% 的活动会**意外全量** —— 那是把刚修掉的静默不一致换个地方复发 |

灰度列的**写**由 activity 服务在 `/api/admin/activities/**` 下执行（见 §6.0 的进程归属），`marketing-admin` 不跨库写业务表；广播也仍由 activity 自己完成，这样"写库 + 发通知"不会跨两个进程。

写入顺序固定：**DB 事务提交 → SET snapshot → SET version**。后两步失败必须返回 `41009 配置已落库但未广播`，而不是像 `RuleCacheManager.java:60-62` 那样只 warn（那处是规则快照，可容忍；这是限流与灰度）。同时提供"重新广播"动作（幂等，仅按 DB 重发快照），④ 的对比页负责暴露"期望值 ≠ 当前生效值"。

### 5.5 白名单注册的所有权与不一致裁决

- `marketing-common/.../config/ConfigDefinitionProvider`（SPI，形状照 `CacheReheater`）：`key / type / min / max / defaultValue / form / fallback / description`。
- 声明长在**参数所属的模块**：限流在网关（`GatewayConfigDefinitions.java`）、灰度在 activity、`calcTimeoutMs`/`maxRulesPerOrder` 在 discount、`tokenTtl`/`payTimeout`/`boughtMarkTtl` 在 seckill。
- 装配用一个**新的** `ConfigSchemaAutoConfiguration`，不能塞进现有 `MarketingCommonAutoConfiguration`（它整体被 `@ConditionalOnBean(DataSource)` 挡住，网关进不去 —— 事实 #8）。
- 各服务在启动与每次刷新时把自述写进 `mkt:cfg:schema:{service}`；admin 读这些键渲染表单，**不 import 任何业务模块**。
- **不一致时代码赢**：DB 有而代码未声明 → 服务忽略，④ 出 `ORPHAN` 清单（不自动删、不生效）；代码有而 DB 无 → 用代码默认值（=现状）。
- **`seckill.buckets` 不进白名单**：它同时是 `seckill_activity.buckets` 列（`SeckillController.java:68` 还硬编码兜底 16），在线改全局值与行值会变成两套真相。
- 权限：配置写 = 仅 `admin` 角色；`operator` 只读 + 可触发重预热（与 ①② 的 `AdminRoles.OPERATIONAL` 分层一致）。所有写走 `AuditSink` 记 before/after。

## 6. ③ 业务管理面

### 6.0 先解决进程归属：后台的业务写端点长在**owning 服务**上

一句被我把说过两次的约束合起来会自相矛盾：既要"配置类写只留 `/api/admin/**`"（§4），又要"预算/库存的 DB 写与 `reheat` 必须同在 owning 模块的 service 方法里"（§6.3）。在 FULL 分进程形态下，`marketing-admin` 既注入不到 activity 的 Bean，也没有业务库的 DataSource（每服务一库档它的连接串只指向 `marketing_admin`）。

定论：**路径统一在 `/api/admin/` 前缀下，但端点由 owning 业务服务自己实现并发布**：

```
/api/admin/activities/**  → marketing-activity:8081（LITE/dev 同为 standalone:8085）
/api/admin/coupon/**      → marketing-coupon:8082
/api/admin/discount/**    → marketing-discount:8083
/api/admin/seckill/**     → marketing-seckill:8084
/api/admin/{auth,users,sessions,audits,cache,config,observe}/** → marketing-admin:8086
```

四条理由：

1. `AdminAuthFilter` 按 `startsWith("/api/admin/")` 判定（`AdminAuthFilter.java:70`），**这些新路由天然已被双 token 分区与角色粗筛覆盖，①② 的 filter 零改动**；
2. 写与 `reheat(force=true)` 能真正待在同一个 service 方法、同一个事务里，不被跨进程拆开（§6.3 的前提成立）；
3. `marketing-admin` 仍然是薄壳，不 import 业务模块 —— 与 §4 那条不变量一致，而不是绕过它；
4. 业务侧读 `X-Admin-*` 身份头即可（网关对这些头先 remove 后 set，客户端伪造不到，见 ①② 的 `AdminAuthFilter.pass`）。

代价与应对（写进各段 spec 的必做项）：

- **网关要为四条新前缀各加路由，local 与 nacos 两套 profile 都要加**，且每条都要进 `rate-limit` map（事实 #3 同源）；LITE 下它们与 `admin-route` 同指 standalone:8085。
- 业务服务需要一个**只依赖 header 的轻量身份件**（读 `X-Admin-Uid/Role` + 角色判定），与 admin 的 `AdminIdentityService` 共享语义但不共享类（它带 Redis 吊销回退，业务侧不需要——网关已经判过）。形状放在 common 作接口、各服务一个 30 行的实现，避免五份漂移。
- 若将来某段想把某个业务写收回 admin 进程，必须先解决"它没有业务库写权限"这件事，不允许靠加 DataSource 蒙过去 —— 那等于把 §6.3 的约束形同废除。

### 6.1 边界：哪些端点搬、哪些留

- **从 C 端搬走**（配置类写）：`POST /api/activity`、`PUT /api/activity/{no}/transition`（`ActivityController.java:38-54`）、`POST /api/discount/rules`、`GET /api/discount/rules`（`DiscountController.java:48-72`：注释自称管理端却挂在 C 路径，并把整套规则 DSL 暴露给共享 demo token）。
- **C 端保留**（运行时读 + 交易写）：`calculate`、`grant`、`grab`、`pay`、`consume`、`budget/deduct`、各 result 轮询、`usable`、`stock`、`activities`、`participatable`、`gray-hit`。
- 顺手归一：`GET /api/seckill/stock/{no}` 对不存在活动返回空数组 → `40400`（`SeckillController.java:60-70`，①② spec §10 的漏网）。
- 新增 admin 端点：活动 / 券模板 / 秒杀活动的创建与上下线、规则编辑、预算与库存编辑、列表分页。券模板与秒杀活动**今天根本没有创建接口**（只由 `docker/mysql/init*/01-schema.sql` 种子写入），所以这部分是净新增能力。

### 6.2 契约

- 列表统一 `Result<PageResult<XxxView>>`（复用 `PageResult/PageQuery`）；**VO 只在 admin 侧强制**，C 端返回实体的形状不动，免得把 52 条基线断言卷进无关改动。
- 动作型端点（状态流转、重投、重预热）用 `IdempotentExecutor` + `BizKey.of("admin", resourceType + ":" + id, requestId)`。
- 字段编辑**不套幂等表**，改用仓库里已就位但从未被使用的乐观锁：`@Version` 已标在 `ActivityEntity.java:36`、`SeckillActivityEntity.java:42`、`SeckillOrderEntity.java:40`，`OptimisticLockerInnerInterceptor` 已在各 `MybatisPlusConfig` 装配。冲突返回新码 `41008 数据已被他人修改`。
- 审计统一由新增 `AuditInterceptor` 在 admin 侧落（复用 `RequestSummary` 脱敏）；`AuditService.record` 的"写失败只 warn"语义不动；`AuditSink` 仍不外移 common（`AuditSink.java:6-8` 的理由成立：目前仍只有 admin 发审计）。

### 6.3 与预扣缓存的强制联动（防地雷 A 复发）

依据：`ActivityService.java:56` 上线走 `warmIfAbsent`（SETNX），所以**改了 `budget_amount` 而不 DEL 重建，缓存永远不会生效**。因此：

1. 库存/预算的写方法只允许落在 **owning 模块的 service** 里，DB 写与 `reheat(force=true)` 在**同一方法内**完成——admin 侧不得"写完 DB 再另外调一次刷新"。
2. 每段单测用 Mockito `verify(reheater).reheat(key, true)` 钉死：注释掉那一行就必须红。
3. FULL 分进程形态下 admin 进程没有 reheater（`registry.types()` 为空），沿用 ⑤ 的同一把轮询器做**自述 + 回执**：写 `mkt:reheat:pending` → owning 服务轮询到并自行执行 → 写回 `mkt:reheat:ack`。**不新增 admin→业务服务的入站端点，也不为此加一套新鉴权**。LITE 命中本地 registry 时保持同步返回，以保住 `smoke-test.sh:284-288` 那两条同步断言。

### 6.4 冲击面（必须一起改，否则基线红）

`scripts/reset-demo-data.sh` 整体改写成"登录 + 调后台改库存端点"，删掉 `:44-88` 的三条形态分支（它目前最脆的一段，也是"换库布局要先重置分桶"那类隐性知识的载体）；`smoke-test.sh` 链路 0 换路径、换 token、且全脚本共用一次登录（事实 #4）；`load-probe.sh` 只有 `:18` 的提示语变；README API 表 4 行 + 计数 + 新增"写入口矩阵"一节。

## 7. ④ 运维只读聚合

- 抽象 `admin/observe/MetricSource.scrape(service)`；两实现：`LocalMeterSource`（注入 `MeterRegistry`）与 `ProxyMeterSource`（HTTP）。选择条件用属性 `marketing.admin.metrics.mode`：standalone yml 显式写 `local`、admin 自己默认 `proxy`（与 `marketing.mq.type: ${MQ_TYPE:redis-stream}` 同一手法，`marketing-standalone/src/main/resources/application.yml:51-52`）。响应**恒带 `mode` 与逐 target 的 `error`**：抓不到要显式报错，不能给一张看着正常的空大盘（事实 #2）。
- 目标清单来自 yml 静态列表（条目名照抄 `docker/prometheus/prometheus.yml:16-21,35-40`），**不用 nacos**：LITE/dev 没有 nacos（`marketing-gateway/src/main/resources/application.yml:15`），而且"任意注册者可被发现"本身就是 SSRF 面。
- SSRF 红线：URL 全部服务端拼装；host 过 `^(marketing-[a-z]+|standalone|127\.0\.0\.1|mkt-[a-z-]+)$`、port 限定 `{8081..8086,8090}`、path 是常量 `/actuator/prometheus`、**禁跟随重定向**、响应 256KB 截断、只取白名单指标名；用户可控的只有 `service` 名，不在白名单 → `40300`。
- 具体口径：
  - `local_message` 积压：`information_schema` 发现所有含该表的库并跨库查询（事实 #7），并补 `GRANT`；
  - RocketMQ 通道下 broker 深度不可读 → 与 `load-probe.sh:12,17` 同口径**明写"不可见"而不是填 0**；
  - Stream 长度仅 LITE 读 `MKT_STREAM_*`（`RedisStreamEventPublisher.java:23,44-46`）；
  - 分桶余量**禁止 SCAN**：由 DB 行 × `buckets` 生成精确键做 MGET + TTL（`SeckillStockService.java:36,68`）；
  - `gw:rl:*` 只做 `route + 字面量 ip` 的定点窥视（ip 必须先解析成字面量，`RateLimitFilter.java:61`），并在网关拒绝分支补 `gateway.rate.limit.rejected{route}` Counter（文件级新增，不碰 ①② 判定逻辑）；
  - `admin_audit_log` 保留期交给 `AuditRetentionJob`（`RedisLeaseLock` 去重、天数取自 ⑤、默认 90 天），**只删不归档**，删除条数落审计。

## 8. ⑥ SPA

- 顶层新目录 `marketing-admin-ui/`，**不进 root pom**：Vue3 + Vite + vue-router + pinia + Element Plus（unplugin 按需引入），`base: '/ui/'`；产物拷入 `marketing-admin/src/main/resources/static/ui/` 入仓；`.gitignore` 加 `marketing-admin-ui/node_modules/`。
- 网关：新增 `ui-route`（`Path=/ui/**` → `ADMIN_HOST:ADMIN_PORT`），**local 与 nacos 两套 profile 各加一条**（①② §5.4 的教训：漏一条就是"只在升档后才暴露"）；`whitelist` 加 `/ui/**`；`rate-limit` 加 `ui-route`。①② 的两个 filter 代码零改动。
- history fallback 放 **admin**：新增 `AdminWebMvcConfig` 注册 `ResourceHandler` + `PathResourceResolver` 回退 `index.html`。不放网关 rewrite——网关不该懂前端路由。
- 缓存：`/ui/index.html` `no-store`；`/ui/assets/**`（内容指纹）`max-age=31536000, immutable`。
- token 存 **localStorage**：`Authorization: Bearer` 是 `AdminAuthFilter.java:162-168` 唯一识别路径，改 cookie 要么动 ①② 要么加反代；不写 cookie 就没有 CSRF。残余 XSS 风险用 CSP `script-src 'self'` + 900s TTL（`AdminProperties.java:19`）+ 已有的 `admin:revoked:{jti}` 单会话吊销兜住。前端按 `40101/40102/40100` 分别动作（码在 ①② 已分开，`ErrorCode.java:14-18`），用已存在的 `LoginView.expiresInSeconds` 做倒计时与一键重登；**不做 refresh token**。
- `dist` 一致性门禁（仓库无任何 CI，`.github` 不存在，所以"CI 校验"没有落点）：① `scripts/build-ui.sh` 是唯一构建入口，把构建版本号注入 `index.html` 注释；② `scripts/check-ui-dist.sh` 比对 jar 内 `BOOT-INF/classes/static/ui/**` 与仓库目录的逐项 sha256，不一致非零退出；③ `UiDistIntegrityTest`（admin 模块、不连库）断言 `index.html` 在 classpath 且其引用的指纹文件全部存在——挡"半提交"。

## 9. 验收

| 段 | 单测（手法沿用 H2 `MODE=MySQL` / `ApplicationContextRunner` / Mockito，关键断言必须做变异检查） | smoke 新链路 | 形态复跑 |
|---|---|---|---|
| ⑤ | +≈14：夹取/越界/未知键忽略/快照编解码/version 重建/灰度回源 DB/schema 聚合去重 | 链路 5：DEL 快照键 → ≤6s 内阈值回 yml 且 ④ 报 degraded；改限流不重启生效；改灰度生效且 Redis 清空不变全量 | LITE / FULL 进程 / FULL 容器 / dev + 每服务一库档 |
| ③ | +≈20：每端点一条"没调 reheat 就红"、一条 `@Version` 冲突、一条 read-only 写 `40300` | 链路 6：C 端 token 打已搬走的写路径必 `404/405`，admin token 打 C 端交易路径仍通（双向钉住边界） | 同上 |
| ④ | 文本解析 fixture + SSRF 黑名单（`127.0.0.1:3307`、含 `@`/`/` 的 host、重定向）用 `MockRestServiceServer` | 链路 7：PENDING 计数 == `docker exec mysql` 直查；LITE 有 XLEN、FULL 显式 `NOT_APPLICABLE` | 同上 |
| ⑥ | `UiDistIntegrityTest` + `check-ui-dist.sh` 退出码 | 链路 8：`/ui/` 200 且 `no-store`、指纹资源 `immutable`、无 token 打 `/api/admin/users` 仍 `40100` | LITE + dev（dist 同一份） |

基线约束：**现有 52 条断言在 ③ 迁移后必须同时全绿**；每段收尾刷新 README 的覆盖矩阵与"测试与验证"计数（今天：25 类 / 106 个 @Test、52 条断言）。LITE 内存继续用 `docker stats` 复核，standalone 超 640 MiB 才动 `mem_limit`（今天 529-599 MiB）；静态文件走 classpath 不占堆，但 `ProxyMeterSource` 的解析结果须限 64KB/target 且不驻留（standalone `-Xmx320m`）。

## 10. 明确不做

nacos 配置中心、refresh token / OAuth2 / SSO / LDAP、RBAC 角色表与权限点表（角色仍是 `admin_user.role` 一列 + 两层判定）、`marketing-open-api` 的在线策略、审计归档到对象存储、Redis SCAN 类窥视、admin→业务服务的新入站端点、把 `AuditSink` 提升到 common、把 `seckill.buckets` 做成在线参数、把 SPA 构建挂进 maven 生命周期。

## 11. 风险

1. **两档共库 + 分形态阈值**：`form` 解析错就等于把 FULL 的 1000/s 灌进 LITE 或反向。三重兜底：逐条类型/边界校验、越界退回出厂值、④ 显示"当前生效值与来源"；并把它写成 smoke 断言。
2. **"已落库未广播"窗口**：`41009` + 重新广播动作 + ④ 的期望/实际对比。这与 ①② 里"bump 键必须先于 DB 写"是同族问题，静默不一致是本项目最贵的一类 bug。
3. **③ 是唯一动 C 端契约的段**：`POST /api/activity` 等一旦收口，外部既有调用方会断。仓库内只有脚本与 smoke 依赖它们；若已有外部集成方，需要单独一段做兼容期。
4. **只在 LITE 可用的能力必须显式报错**，不能静默成功：`reheat` 已经立了这个先例（`AdminCacheController.java:60-71`，当前用 `41000`）。⑤ 的跨进程重预热与 ④ 的 broker 深度沿用同一处理方式，并统一改用新码 **`41010 本形态不适用`**（`41000` 继续留给真业务错误；`reheat` 从 41000 迁到 41010 时，smoke 链路 4 那两条 FULL 分支断言同步改）。

## 12. 下一步

按 ⑤ → ③ → ④ → ⑥ 逐段展开：每段进入实施前先按本文件出一份段内 spec（`docs/superpowers/specs/`）+ 实施计划，批次划分与验证清单在那一层给。

## 13. 实施偏离（⑤ 落地时确认，已回写）

⑤ 的段内 spec 是 `2026-09-23-online-config-delivery-design.md`（含完整理由与实测证据），这里只留摘要：

1. §5.1 的 version 取自 `MAX(version)+1` → 实际取 `INCR mkt:cfg:seq`：并发写撞出同一个版本号会让读方永久停在陈旧快照上（正是本文件风险 #2 要防的静默不一致）。
2. §5.3 的发布目标按"表里出现过的 form" → 实际按固定四形态遍历，合并为空时**删**键：否则把某形态的行删干净之后，"恢复出厂"不生效。
3. §5.4/§5.5 的灰度"Redis 只当变更通知 + activity 声明一条 ConfigDefinition" → 实际改为 activity 每 5s 回源 DB，灰度既不进 `admin_config` 也不声明：通知键需要有人 bump，而 bump 者（业务侧后台写端点）的审计归属在 ③ 才解决（段内 spec §4 记了这个结）。回源 DB 顺带把"Redis 被清 → 曾设 5% 的活动意外全量"这一整类风险消掉。
4. §5.5 "各服务把自述写进 `mkt:cfg:schema:{service}`" 的 `{service}` 取 `spring.application.name`（进程名），而 `ConfigDefinitionProvider.service()` 是模块名——LITE 下业务模块的自述挂在 `marketing-standalone` 上，因此载荷里每条声明额外带一个 `owner` 字段供后台显示归属。
5. 事实 #8 的准确表述：网关缺的是 DataSource 与**阻塞客户端的用法**，不是 classpath 上没有 `StringRedisTemplate`（reactive starter 会把 spring-data-redis 核心带进来）。因此新增 `ConfigSyncer` 标记接口让阻塞轮询器在网关让位，否则两套节拍同时喂同一份生效值。
6. 附带修掉一个真缺陷：请求体解析失败原先落进 catch-all 返回 50000"系统繁忙，请稍后再试"——客户端错误被说成服务器忙，会把人引向错误的排查方向。现在 `HttpMessageNotReadableException → 40000`。
7. §5.2 的读侧稳态补了一条约束：**没写过在线配置时（版本键不存在）轮询必须静默**。计划的短路条件 `version == applied && version != 0` 在"键不存在=0"这件事上把首次与稳态混为一谈，实测六个进程各刷 12 条/分钟 INFO。加 `primed` 标志区分"从没取过"与"取过且为空"后，④ 拿到的 `degraded`/日志才是可用信号。
