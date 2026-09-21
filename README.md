# 营销管理平台脚手架（marketing-platform）

面向 **日常 QPS 万级 / 大促 10 万+ / 秒杀 50 万+** 场景的可运行微服务脚手架（该量级是 **FULL 档的设计口径**，LITE 档当前实测容量见第三节末）。
架构原则：**分层限流、规则可编排、活动可灰度、全链路可降级、最终一致、ROI 可实时度量**。

- 券中心 / 优惠计算引擎 / 秒杀中心 **深度实现**
- 活动中心（状态机 + 预算 + 灰度）/ 风控 / 分销 / ROI **基础能力或扩展点占位**
- 标准拓扑：Gateway + Nacos + 4 业务服务；Seata / Flink / ClickHouse / ES / XXL-Job 留扩展点
- 形态：上面的标准拓扑是 **FULL 扩容档**（活跃期承接洪流）；非活跃期由 **LITE 服役档** 单 JVM 聚合进程承载（Redis Stream 代替 MQ，≈1 GiB），两档共享同一份数据原地双向切换（见第三节）

## 一、架构总览

```
                        ┌──────────────────────┐
   客户端 ──8090──────▶ │  marketing-gateway   │  鉴权(Bearer) + Redis+Lua 滑动窗口限流
                        └────────┬─────────────┘  （秒杀路由阈值最严，超限返回排队码）
                                 │ Path 路由（local 静态 uri / nacos lb://）
       ┌─────────────┬───────────┼─────────────┬──────────────┐
       ▼             ▼           ▼             ▼              ▼
  8081 activity  8082 coupon  8083 discount  8084 seckill   (Nacos 注册发现, 默认关闭)
  状态机/预算    券中心        优惠计算引擎    秒杀中心
  /灰度          Lua 预扣+MQ   规则DSL+位图索引  分桶Lua+MQ下单
       │             │           │             │
       └──────┬──────┴─────┬─────┴──────┬──────┘
              ▼            ▼            ▼
           MySQL 8      Redis 7     RocketMQ 5        Prometheus(9091) 抓 /actuator/prometheus
        (4 业务库)   (限流/库存/预扣)  (削峰/最终一致)
```

技术栈：Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Alibaba Cloud 2023.0.1.0
· MyBatis-Plus 3.5.7 · Redis(StringRedisTemplate + Lua) · RocketMQ 5 · Caffeine/本地快照 · Micrometer

## 二、模块与端口

| 模块 | 端口 | 职责 |
|---|---|---|
| marketing-common | - | Result/异常、幂等执行器、本地消息表、Lua 工具、MQ 契约 |
| marketing-open-api | - | 风控 / 分销 / ROI 领域接口与占位实现（自动装配，可替换） |
| marketing-gateway | 8090 | 路由、Bearer 鉴权、Redis+Lua 滑动窗口限流 |
| marketing-activity | 8081 | 活动状态机、预算 Redis 预扣 + 流水、灰度分流 |
| marketing-coupon | 8082 | 券模板/库存预热、领券、结果轮询、核销 |
| marketing-discount | 8083 | 规则 DSL、位图倒排索引、最优组合、分摊、超时降级 |
| marketing-seckill | 8084 | 分桶预热、Lua 抢购、MQ 异步下单、支付、超时回补 |
| marketing-standalone | 8085 | LITE 服役档 / dev 开发档的聚合进程：四业务模块单 JVM + Redis Stream 消息（见第三节） |

## 三、形态与环境

这套脚手架的**核心目的**：同一份业务代码按流量在两个容量档之间原地切换——非活跃期由小机器
（LITE）常态承载服务，活跃期升档到与生产同构的 FULL 承接洪流。**切换只换应用侧进程形态与
消息通道，不动数据、不改代码**；允许双向（升档也降档）。

| 形态 | 定位 | 拓扑 | 消息通道 | 数据库 | 可靠性口径 |
|---|---|---|---|---|---|
| **LITE 服役档**<br>（preview） | 非活跃期 7×24 真跑流量，小机器常态承载 | 全栈容器化 4 容器：mysql / redis / standalone（四模块聚合）/ gateway | Redis Stream | 单库 `marketing` | Redis **AOF everysec + noeviction**、MySQL flush=1、restart 策略、日志轮转 |
| **FULL 扩容档**<br>（prod） | 活跃期承接洪流，与生产同构 | 5 独立进程（8081-8084 + 8090）+ RocketMQ + Nacos + Prometheus | RocketMQ | 4 业务库 | 中间件默认全持久化 |
| **dev 开发档** | 本机改代码，允许丢数据 | 中间件容器 + 本机 2 JVM（standalone 8085 / gateway 8090） | Redis Stream | 单库 `marketing` | 不持久化；**淘汰策略仍与 LITE 一致** |

LITE 的省内存**一律不靠牺牲可靠性换**，四条不可退让：淘汰策略必须 `noeviction`
（`allkeys-lru` 会静默丢掉库存/券预扣/幂等键，后果是超卖与重复领券）；状态要落 AOF 且挂卷；
容器要有 restart 与 json-file 日志轮转（默认 json-file 不限量，长期跑撑爆磁盘）；
GC 用 G1 而非 SerialGC（SerialGC 的 Full GC 停全部线程，表现为结算秒级尖峰）。

### 快速开始

```bash
# LITE 服役档（默认推荐）
./scripts/deploy-preview.sh           # mvn 构建 → compose up -d --build --wait → actuator 就绪收口
./scripts/smoke-test.sh               # 端到端验收（三套形态通用）
./scripts/stop-preview.sh             # -v 连数据卷清空
./scripts/reset-demo-data.sh          # 演示库存被压测吃掉后复位（不动业务数据）

# dev 开发档
./scripts/start-dev.sh                # mysql+redis 容器 → 构建 → 本机 2 JVM
./scripts/stop-dev.sh --down

# FULL 扩容档
cd docker && docker compose -f docker-compose.prod.yml up -d && cd ..
./scripts/start-all.sh                # 日志 logs/，pid run/；监控 http://localhost:9091
./scripts/stop-all.sh && (cd docker && docker compose -f docker-compose.prod.yml down)

# 注册中心（可选，仅 FULL）：http://localhost:8848/nacos，profile=nacos 开启 lb:// 路由
# ⚠ 该路径目前既没有脚本开关也没被实测过，见工单列表
```

### 宿主机端口矩阵

| 端口 | 用途 | dev | LITE(preview) | FULL(prod) |
|---|---|---|---|---|
| 8090 | 网关对外入口 | 本机进程 | 容器发布 | 本机进程 |
| 8085 | 聚合服务（调试直连） | 本机进程 | 容器发布 | - |
| 3307 | MySQL（仅绑回环） | 容器 | **不发布** | 容器 |
| 6380 | Redis（仅绑回环） | 容器 | **不发布** | 容器 |
| 9876 / 10911 / 8848 / 9091 | RocketMQ / Nacos / Prometheus | - | - | 容器发布 |

> **Redis 为什么不用 6379**：宿主机上常驻的 `redis-server`（Homebrew 之类）会占住
> `127.0.0.1:6379`，其精确绑定优先于 Docker 对 `*:6379` 的发布，本机业务进程会静默连到那个
> "外人"实例——三套环境当场退化成共用一套中间件，且只在库存/幂等数据对不上时才暴露。
> `scripts/common.sh::assert_port_not_shadowed` 在启动前挡住这类遮蔽。

### 内存实测

Apple Silicon 开发机 + OrbStack；容器取 `docker stats`，本机进程取 `ps` RSS（口径一致可横向比）。

| 形态 | 应用侧 | 中间件侧 | 合计 | 备注 |
|---|---|---|---|---|
| LITE 服役档 | standalone 554 + gateway 334 MiB | mysql 185 + redis 15 MiB | **≈ 1.04 GiB** | 2026-09-21 idle 实测 |
| dev 开发档 | 2 JVM ≈ 176 MiB | mysql 168 + redis 13 MiB | **≈ 0.35 GiB** | 改服役口径前测，待复测 |
| FULL 扩容档 | 5 JVM ≈ 306 MiB | RocketMQ 1.44 + Nacos 1.06 + mysql 0.44 + 其它 0.07 GiB | **≈ 3.3 GiB** | RocketMQ 堆需经 `JAVA_OPT_EXT` 显式给定（镜像自带的 `JAVA_MIN_MEM/JAVA_MAX_MEM` 对其启动脚本无效，不给定会按容器内存 1/4 吃 2 GiB） |

LITE 从 0.81 GiB 涨到 1.04 GiB（+27%）就是上面那四条可靠性换来的：G1 取代 SerialGC、AOF、
mem_limit 按"堆 + 元空间 + code cache + 线程栈 + direct"重算留余量。

### 容量现状（别把 LITE 当洪峰档）

**连接池（已修）**：standalone 起初 `maximum-pool-size: 10`，一个 JVM 承载四模块 + 两个消费
线程 + 补偿 Job，而慢提交会让连接被持有到 fsync 完成 —— 25 并发即打满，实测累计 54 次
`hikaricp_connections_timeout_total`，请求在"等连接"上超时到 20 秒。修到 30（与 MySQL
`max-connections=60` 留一倍余量）之后，同样的探针读数从"1↔31 msg/s 摆 30 倍"变成
**入口 98-103 msg/s、消费 22-25 msg/s、两轮方差 ±5%** —— 也就是说池是当时读数抖动的来源，
修好后测量才可用。

**消费并行度已对齐（LITE 1 → 8 worker）**：`StreamConsumerRegistrar` 起初每个 topic 只起
一条 worker，而同一段落库逻辑在 FULL 由 `@RocketMQMessageListener(consumeThreadNumber = 8)`
驱动 —— 这既是吞吐墙也是形态不等价。现在默认 8（`MQ_STREAM_CONCURRENCY` 可调），实测同一负载
（600 条 / 并发 80，让积压必然形成）：

| 消费并行度 | 消费净速率 |
|---|---|
| 1（改前） | 25 msg/s |
| 4 | 54 msg/s |
| 8（默认） | **115 msg/s** |

并行度提到 8 的同时暴露并修复了一个真 bug（见 `fix(seckill)` 提交）：同步链路先投递消息、
后无条件 `SET result=ACCEPTED`，消费端变快之后会把已写好的 `SUCCESS` 覆盖回 `ACCEPTED`，
表现为"订单已建、用户永远轮询到处理中"。FULL 侧 8 线程同样会撞，只是此前没被测出来。

**当前限制因子回到同步请求路径**：入口实测 ~90-100 msg/s（每单 4 个 autocommit × fsync）。

**可选容量旋钮**：MySQL `innodb-flush-log-at-trx-commit=2`（compose 里以注释给出，默认不开）。
实测收益只有 **1.5-2 倍**（入口 73-94 → 132-138 msg/s、消费 24 → 40-62 msg/s），不是数量级，
而代价是 OS/主机崩溃丢最近 1 秒已提交事务（对秒杀意味着"已扣库存无订单"需人工对账），
所以不做默认。

**剩余可挖**：同步入口每单仍打 4 个 autocommit，合并到 1-2 个约值同样 1.5 倍，但要注意
幂等"抢占"与业务写同事务会失去 in-flight 抢占记录的可见性（并发重复请求将看不到彼此的
PROCESSING 状态），需要连同幂等语义一起评估，不是纯性能改动。

### LITE 跑通 ≠ FULL 跑通

| 差异 | 后果 |
|---|---|
| 消息重试语义：RocketMQ broker 侧持久化 + 指数退避重试队列 vs Redis Stream 容器内 3 次后放弃（靠本地消息表补偿重投） | 削峰行为不等价；Stream 无 broker 侧堆积策略 |
| **单库共享**（LITE 一库，FULL 四库） | 跨模块 join 在**两个形态里都不会被 DB 拦住**——原四库隔离本来是一道真防线，LITE 把它拿掉了，只能靠约定 |
| 单 JVM 承载四模块 | 掩盖服务间超时、部分不可用、连接池争用（hikari 10 vs 4×20） |
| LITE 不带 Prometheus | `/actuator/prometheus` 暴露了但没人抓；`prometheus.yml` 的 target 写死 FULL 的宿主机端口 |
| ~~消费并行度：FULL 8 线程 / LITE 1 线程~~ 已对齐（默认 8） | 见上文；两形态消费并发不再不等价，但并行度需与连接池一起调（池 30 才吃得住 8 worker） |

数据库账号 `marketing / marketing123`（宿主机端口 **3307**，避开本地 mysqld 占用的 3306）；
网关演示 Token `demo-token-123`（环境变量 `GATEWAY_TOKEN` 覆盖）。

**构建要求**：Maven 必须跑在 **JDK 17**，`scripts/*.sh` 已通过 `scripts/common.sh` 自动锁定。
Homebrew 默认 JDK 已滚到 26，Lombok 1.18.x 在其上无法运行注解处理，表现为满屏
`cannot find symbol: log / setXxx`。

## 四、三条核心链路

### 1. 领券（削峰 + 最终一致）

```
POST /api/coupon/grant (requestId 幂等键)
  → 网关限流 → 风控(占位) → 模板校验 → IdempotentExecutor 抢占
  → Redis+Lua 原子预扣库存(含单人限领) → 本地消息表 + MQ 发送
  → 返回 ACCEPTED，客户端轮询 GET /api/coupon/grant/result/{requestId}
  → 消费端幂等落库(user_coupon.request_id 唯一索引兜底) → confirm 消息
```
- 三层幂等：`idempotent_record` 状态机 / `local_message.biz_key` 唯一 / 业务表唯一索引
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
- `POST /api/discount/rules` upsert 规则后 bump 版本号，全实例秒级生效
- 基准（1 万规则 / 20 行购物车，开发机）：剪枝后候选 400 条，单次计算均值 **≈0.4ms**

### 3. 秒杀（50 万 QPS 设计口径）

```
POST /api/seckill/grab
  → SETNX 防重购标记 → Lua 分桶原子扣减(hash(userId)%16 定位桶 + 顺序借桶)
  → 占名额成功即返回 token → 本地消息表 + MQ
  → 消费端建单(seckill_order unique(activity_no,user_id) 兜底) + sold_stock 原子递增
  → 轮询 GET /api/seckill/grab/result/{token}: ACCEPTED / SUCCESS:{orderNo} / FAIL:{reason}
  → POST /api/seckill/pay/{orderNo} 模拟支付；超时 5 分钟未支付由 Job 取消订单并 Lua 回补
```
- 同步路径只有一次 Redis Lua 调用，DB 写全部异步化；分桶把单 key 热点摊到 16 个 key
- 启动 `SeckillWarmUpRunner` SETNX 预热（重启/多实例不重置已售进度）

## 五、API 速查（经网关 8090，需 `Authorization: Bearer demo-token-123`）

| Method | Path | 说明 |
|---|---|---|
| POST | /api/activity | 创建活动（DRAFT） |
| PUT | /api/activity/{no}/transition?event= | 状态机流转（SUBMIT/APPROVE/REJECT/PROMOTE/OFFLINE/RE_ONLINE/FINISH） |
| GET | /api/activity/{no}/participatable · /gray-hit?userId= | 可参与校验 · 灰度命中判断 |
| POST | /api/activity/{no}/budget/deduct · GET /budget/remain | 预算扣减（bizKey 幂等）/ 实时余额<br>⚠ `biz_key` 是**全局唯一**索引（非活动内唯一），跨活动复用同一 bizKey 会被判重复而静默跳过扣减，调用方必须自带命名空间 |
| POST | /api/coupon/grant · /consume | 领券受理 · 核销 |
| GET | /api/coupon/grant/result/{requestId} · /usable?userId= · /stock/{templateNo} | 轮询 / 可用券 / 模板余量 |
| POST | /api/discount/calculate · /rules | 优惠计算 · 规则 upsert（触发快照刷新） |
| POST | /api/seckill/grab · /pay/{orderNo} | 抢购 · 模拟支付回调 |
| GET | /api/seckill/grab/result/{token} · /activities · /stock/{activityNo} | 轮询 / 活动列表 / 分桶余量 |

## 六、测试与验证

```bash
mvn test                 # 26 个单测：见下
./scripts/smoke-test.sh  # 端到端 34 条断言（三套形态通用，需服务已启动）
./scripts/reset-demo-data.sh [总库存]  # 演示容量复位（默认 5000）
```

**单测（26）**：三层幂等语义、非法状态流转拒绝、比例分摊尾差归末项、末行占满顺延、
互斥组最优（priority desc → discount desc）、叠加超限精确枚举、1 万规则基准耗时、
**装配层回归**（聚合形态扫描边界 + common 条件装配矩阵，用 ApplicationContextRunner + H2
不依赖中间件）。后者把"预览栈起不来"这类装配 bug 从一次 2-3 分钟的构建+部署排查压到秒级。

**冒烟（34 条，四链路）**：链路 0 活动中心（草稿→提审→灰度→上线→终态、非法流转 41001、
重复活动号 41000、预算扣减与 bizKey 幂等、超预算 41003、灰度命中、可参与位切换）；
链路 1 领券；链路 2 优惠计算；链路 3 秒杀 + 并发防超卖。

并发段的库存基线**从接口读、不写死**，并断言恒等式 `分桶余量 + DB 已售 == 总库存`
（对超时取消抖动免疫，超卖/漏扣/回补异常都会破坏它）。因此可连续重复运行：已实测
连跑 3 轮均 34/34。

## 七、扩展点（占位 → 生产的升级路径）

| 占位 | 生产替换 |
|---|---|
| `AllowAllRiskCheckService` | 风控中心 RPC + 设备指纹 + 黑名单布隆过滤器（接口不变） |
| 网关演示 Token 鉴权 | OAuth2/JWT 网关鉴权 + 用户维度限流键 |
| `@Scheduled` 超时回补/消息补偿 | XXL-Job / SchedulerX 分片调度 |
| 本地消息表最终一致 | 强一致场景接 Seata AT（订单/预算服务已按 bizKey 幂等设计） |
| volatile 规则快照 | 规模增长后演进 ES 标签检索 / 多维索引 |
| MQ 削峰计数 | Flink 实时 ROI 大盘 + ClickHouse 明细 |
| Nacos 默认关闭（local 静态路由） | `--spring.profiles.active=nacos` 一键开启注册发现与配置中心 |

## 八、目录结构

```
marketing-platform/
├── pom.xml                     # 父 POM（版本矩阵统一管理）
├── marketing-common/           # 幂等/本地消息/Lua/Result/异常（自动装配）
├── marketing-open-api/         # 风控/分销/ROI 领域接口 + 占位实现
├── marketing-gateway/          # 8090 路由/鉴权/限流
├── marketing-activity/         # 8081 状态机/预算/灰度
├── marketing-coupon/           # 8082 券中心
├── marketing-discount/         # 8083 优惠计算引擎
├── marketing-seckill/          # 8084 秒杀中心
├── docker/
│   ├── docker-compose.prod.yml    # FULL 扩容档中间件：MySQL/Redis/RocketMQ/Nacos/Prometheus
│   ├── docker-compose.preview.yml # LITE 服役档全栈（2 JVM 容器 + AOF/noeviction/restart/日志轮转）
│   ├── docker-compose.dev.yml     # dev 开发档中间件（mysql + redis，小内存调参）
│   ├── mysql/init/01-schema.sql   # 4 库 DDL + 种子数据（FULL，自动执行）
│   ├── mysql/init-lite/           # 单库 DDL + 种子数据（LITE / dev，自动执行）
│   └── prometheus/prometheus.yml
└── scripts/
    ├── common.sh               # 公共前置：JDK 17 锁定 + 健康等待
    ├── start-dev.sh / stop-dev.sh             # dev 开发档（2 个本机 JVM）
    ├── deploy-preview.sh / stop-preview.sh    # LITE 服役档（全栈容器）
    ├── start-all.sh / stop-all.sh             # FULL 扩容档（5 个 JVM）
    └── smoke-test.sh           # 三链路端到端冒烟（三套形态通用）
```

种子数据：活动 `ACT2026001`、券模板 `CT2026001`(5元无门槛)/`CT2026002`(满100减20)、
秒杀 `SK2026001`(200 件/16 桶)、规则 `PR2026001~003`(满减/折扣/阶梯)。
