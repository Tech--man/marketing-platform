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
| **FULL 扩容档**<br>（prod） | 活跃期承接洪流，与生产同构 | 两种交付形态二选一：**本机进程** 5 JVM（8081-8084 + 8090）／**容器化** 一容器一服务且可 `--scale` 多副本（只有网关发布端口）；配 RocketMQ + Nacos + Prometheus | RocketMQ | 默认同一单库（可选每服务一库） | 中间件默认全持久化 |
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
./scripts/load-probe.sh 600 80 3      # 吞吐测量：入口 / 消费排空 / 端到端 + 轮间方差

# dev 开发档
./scripts/start-dev.sh                # mysql+redis 容器 → 构建 → 本机 2 JVM
./scripts/stop-dev.sh --down

# FULL 扩容档 · 本机进程形态
./scripts/start-all.sh                # 自带数据层 + RocketMQ（进程形态广播 127.0.0.1）
                                      # 日志 logs/，pid run/；PROFILES=nacos 走注册发现
                                      # 四库隔离档：MYSQL_DB_PER_SERVICE=1 ./scripts/start-all.sh
(cd docker && docker compose -f docker-compose.prod.yml up -d)   # 想连带 nacos/prometheus 才需要
                                      # 注册中心控制台 http://localhost:8848/nacos，监控 :9091
./scripts/stop-all.sh && (cd docker && docker compose -f docker-compose.prod.yml down)

# FULL 扩容档 · 容器化形态（一容器一服务，可 --scale 多副本）
./scripts/deploy-full.sh              # mvn 构建 + 镜像 + 起全量（网关 8090，其余不发布端口）
./scripts/deploy-full.sh --scale marketing-seckill=2 --scale marketing-coupon=2
SKIP_BUILD=1 ./scripts/deploy-full.sh --scale marketing-discount=2   # 复用现成镜像，不重建
docker compose -f docker/docker-compose.full-app.yml down            # 拆应用侧，中间件/数据层不动
```

### 数据层与端口矩阵

MySQL 与 Redis **不属于任何形态**：由 `docker/docker-compose.data.yml` 单独常驻，三套形态共用
同一份数据 —— 这是"原地双向切换"的结构前提（若每套形态各起一个 MySQL 去挂同一个卷，
并发拉起就是卷级损坏）。三个启动脚本都会幂等地把它 `up -d --wait` 起来。

> `docker/mysql/init*/` 里的 DDL **只在新建数据卷时执行**。已存在的卷不会自动跟上索引变更，
> 需要手工跑 `docker/mysql/migrate/` 下的脚本（按时间命名，逐个执行）。
> 例：`docker exec -i mkt-mysql mysql -umarketing -pmarketing123 marketing < docker/mysql/migrate/2026-09-22-bizkey-scope.sql`
> 迁移脚本头部写了执行前置（比如改消息表索引前要先确认无在途消息）。

| 端口 | 用途 | 归属 |
|---|---|---|
| 3307 | MySQL（仅绑回环，单库 `marketing`） | 数据层 `mkt-mysql` |
| 6380 | Redis（仅绑回环，AOF + noeviction） | 数据层 `mkt-redis` |
| 8090 | 网关对外入口 | 形态侧：dev/FULL 本机进程、LITE 容器 |
| 8085 | 聚合服务（调试直连） | 形态侧：dev 本机进程、LITE 容器 |
| 9876 / 10911 / 8848 / 9091 | RocketMQ / Nacos / Prometheus | 仅 FULL 形态 |

**注册中心模式已实测**（`PROFILES=nacos ./scripts/start-all.sh`）：5 个服务全部注册进 Nacos；
再起第二个 discount 实例（`SERVER_PORT=8093`）后 Nacos 显示两个 host，经网关打 20 次同步请求，
`mkt_discount_calc_seconds_count` 计数 **8083 与 8093 各 10 次** —— lb:// 轮询负载均衡成立，
这是 FULL "加实例承接洪流" 的前提。

**FULL 的异步链路两种交付形态都已实测**（2026-09-22，见上节"快速开始"的两条命令）：
本机进程形态与容器化形态各跑满 34/34，`local_message` 全程无 PENDING 残留、Redis 里
没有任何 Stream 键（证明确实走 RocketMQ 而不是 LITE 通道），broker 侧两个消费组积压为 0。
容器化形态还额外验到多副本三件事：入口侧网关 lb 把流量打到两个 seckill 副本（自启动起各自
累计 61 / 62 次 `grab` 请求，两边都不是全量即证明被分流）；消费侧同一消费组把消息分给两个副本
（`seckill_order_persisted` 分别 35 / 26，且冒烟的"账实一致"断言未被破坏，说明不是各自重复消费）；
`@Scheduled` 跨副本互斥（两副本 `mkt_job_dedup_skipped_total` 累计 109 次跳过，即同一周期只有一个实例真跑）。

**一台 broker 只能广播一个地址，两种交付形态要的却不同**，所以 broker 配置分成两份、
由各自的启动脚本调和：本机进程形态用 `rocketmq/broker.conf`（`brokerIP1=127.0.0.1`），
容器化形态用 `rocketmq/broker.container.conf`（`brokerIP1=host.docker.internal`，经
`docker-compose.prod.container.yml` 这一层覆盖挂载）。为什么不能一个取值通吃：在
Apple Silicon + OrbStack 上实测，容器内 `host.docker.internal` 通，宿主机上原生 socket 也通，
但**宿主机 JVM 里的 RocketMQ Netty 客户端连不通它**（`RemotingConnectException`）；反过来
广播 127.0.0.1 时容器把回环当成自己。两个脚本都用 `--force-recreate rocketmq-broker`：
bind 挂载钉的是 inode，只改 conf 内容时 compose 认为服务没变、不会重建（这个坑今天踩过一次，
表现是"改了地址但 broker 还在广播旧值"）。

**FULL 的四库隔离档也实测过**（`MYSQL_DB_PER_SERVICE=1` + 独立的一套 MySQL）：34/34，
数据确实按服务落在 `marketing_activity` / `marketing_coupon` / `marketing_seckill` 各自库里
（活动 4 行、券 1 行、订单 61 行），两张 `local_message` 补偿表零残留。这条路径此前**从未跑过，
而且是坏的**：`docker/mysql/init/01-schema.sql` 给 `marketing_activity` 授了权、也 `USE` 了它，
却没有 `CREATE DATABASE` —— GRANT 不建库，初始化会在第一个 USE 处报错并让 MySQL 容器整体起不来。

**那次偶发已经定位并修掉了**：容器化 FULL 冒烟曾在部署后首轮失败 1 次（33/34），当时只截了
输出尾部没留下失败断言。后来 OrbStack 崩过一次（磁盘满后遗症），重启后的第一轮冒烟把同一处
抓了现行：**链路 2 优惠计算 `degraded:true`** —— 容器刚起来的 JIT 与连接冷启动踩到 `calcTimeoutMs`。
我上一版写的"查 `mkt_discount_degraded_total=0` 排除了降级那条"是**错的**：那个 target 是 compose
服务名的轮询解析，抓到的可能不是出事的那个实例，计数为 0 不构成排除。
修法是把 `smoke-test.sh` 链路 2 的预热改成**用同一个请求体重试到不降级为止**（原来用一个
无规则的探针预热，压根不触发要断言的规则匹配路径，等于没预热）；重试耗尽仍降级则如实判失败。

**磁盘水位是 broker 的隐形开关**：所在分区使用率超过 90% 时它以 `CODE:14 service not available`
拒写（实测踩过 92%），表现与"链路坏了"完全一样，但消息不丢——全部退回本地消息表。
本次实测到补偿链路真实闭环：拒写窗口内积压 61 条 PENDING，磁盘回到 78% 后由
`LocalMessageRetryer` 重投、容器化消费端落库，61 条全部转 CONFIRMED（`sold_stock` 13 → 74），
零丢失。运维上先 `df` 再看代码，`--build` 一次全量镜像重建约多占 2 GB。

互斥的只有**形态侧**（8090/8085 与 FULL 的 5 个进程端口），切换时先停上一套的应用侧即可，
数据层不用动。

> **Redis 为什么不用 6379**：宿主机上常驻的 `redis-server`（Homebrew 之类）会占住
> `127.0.0.1:6379`，其精确绑定优先于 Docker 对 `*:6379` 的发布，本机业务进程会静默连到那个
> "外人"实例——三套环境当场退化成共用一套中间件，且只在库存/幂等数据对不上时才暴露。
> `scripts/common.sh::assert_port_not_shadowed` 在启动前挡住这类遮蔽。

### 原地双向切换（同一份数据）

```bash
# LITE 服役档 → FULL 扩容档·本机进程形态（升档）
./scripts/stop-preview.sh             # 只停应用容器，数据层不动
./scripts/start-all.sh                # 自己拉起数据层 + RocketMQ，并把 broker 调和成
                                      # brokerIP1=127.0.0.1（进程形态要的地址）

# LITE 服役档 → FULL 扩容档·容器化形态（要水平扩容时）
./scripts/stop-preview.sh
./scripts/deploy-full.sh --scale marketing-seckill=2   # 中间件带容器版 broker conf，自动重建

# FULL → LITE（降档）
./scripts/stop-all.sh                                    # 进程形态
# docker compose -f docker/docker-compose.full-app.yml down   # 容器化形态
(cd docker && docker compose -f docker-compose.prod.yml down)  # 只拆扩容档专属中间件
./scripts/deploy-preview.sh
```

两侧都不需要搬数据：`MYSQL_DB` 默认指向共享单库，Redis 状态（库存桶 / 幂等标记 / 限流窗口）
随 AOF 卷原地保留。2026-09-22 实测的完整链路：LITE 34/34 → 原地升 FULL 进程形态 34/34 →
再原地换 FULL 容器形态（1 副本、2 副本各 34/34）→ 降回 LITE 34/34，四段全程共用同一个
`marketing` 库与同一套 Redis 状态，累计数据连续（秒杀 `sold_stock` 从 13 一路涨到 362，
逐段递增、无丢失无翻倍）。更早一轮还验过 LITE 留下的订单与活动在升档后立即可见。

切换时**唯一需要留意的是消息通道**：升档瞬间 LITE 侧 Stream 里未被消费的消息不会自动转到
RocketMQ。正确性靠本地消息表兜住（未 confirm 的消息由 `LocalMessageRetryer` 按退避重投，
新进程装配的是新通道，因此会自动改投），代价是最多一个退避周期（≤ 300s）的处理延迟；
降档方向同理。因此切换应选低峰期，并在切换后确认
`SELECT status, COUNT(*) FROM local_message GROUP BY status` 无长期 PENDING/SENT 残留。

### 内存实测

Apple Silicon 开发机 + OrbStack；容器取 `docker stats`，本机进程取 `ps` RSS（口径一致可横向比）。

| 形态 | 应用侧 | 数据层与中间件 | 合计 | 测量口径 |
|---|---|---|---|---|
| **LITE 服役档** | standalone 567 + gateway 323 MiB | mysql 172 + redis 8 MiB | **≈ 1.05 GiB** | `docker stats` |
| **dev 开发档** | 2 个本机 JVM ≈ 180-244 MiB | 数据层 184-256 MiB | **≈ 0.36-0.49 GiB** | JVM 部分是 `ps` RSS，**macOS 下会低估**（文件映射与压缩页不计），只宜横向比；区间是两次实测，差值主要是 MySQL 缓冲池预热程度 |
| **FULL 扩容档**（容器化，5 服务单副本） | 5 容器 ≈ 2.7 GiB（484-689 MiB/个） | nacos 1.11 + rocketmq 1.68 + 数据层 0.26 + prometheus 0.03 GiB | **≈ 5.8 GiB** | `docker stats`；`--scale marketing-discount=2` 时实测约 +0.5 GiB/副本 |
| **FULL 扩容档**（本机进程，5 JVM） | 5 JVM `ps` RSS 合计 253 MiB（**刚启动即采样**；同一进程跑 10 分钟后到 309 MiB，ps RSS 随负载爬升） | rocketmq 1.77 GiB（nacos/prometheus 未起） | **≈ 2.0 GiB** | 混合口径 + 采样时点不一致，只作量级参考，别与上三行比 |

> 口径说明：跨形态比较一律用 `docker stats`。本机进程的 `ps` RSS 在 macOS 上系统性偏低
> （实测同一服务在容器里 440-600 MiB、在宿主机 `ps` 只报 44-132 MiB），混用两种口径会得出
> 错误结论 —— 本文早期版本就因此把 FULL 写成"≈3.3 GiB"。

LITE 从 0.81 GiB 涨到 1.05-1.1 GiB 就是上面那四条可靠性换来的：G1 取代 SerialGC、AOF、
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

**入口阈值随形态收口（LITE 的"早拒优于慢扛"）**：网关四条路由的 `limit` 已参数化
（`RL_COUPON` / `RL_SECKILL` / `RL_ACTIVITY` / `RL_DISCOUNT`，默认值即 FULL 口径）。
LITE 服役档按实测排空能力定档：会进异步队列的领券/秒杀 **120/s**、活动 200/s、
纯同步的优惠计算 500/s。实测超阈值爆发（400 条 / 并发 100）得 **327 受理 + 73 个 HTTP 429**，
受理吞吐稳定在 104 msg/s ≈ 排空能力 —— 洪流不会变成无限排队。

**可复现测量入口**：`./scripts/load-probe.sh [条数] [并发] [轮数]`。探针的排空口径是
**业务落库数**而不是队列长度——RocketMQ 的队列在 broker 里读不到（`XLEN` 恒为 0），
只有"受理成功的条数最终变成多少张券"对两种通道同时成立；队列深度只在 LITE 额外打印，
用来算真实净排空。同一参数（400 条 / 并发 80，机器：10 核 OrbStack）两形态实测：

| 形态 | 入口 | 受理 | 端到端 | 尾部排空 | 队列 |
|---|---|---|---|---|---|
| LITE（8 worker 消费） | 2.1-2.7s | 240-327 条（其余 117-160 个 **429**，形态阈值生效） | ~70 msg/s | 1.3s | 发送结束积压 68-182，净排空 50-138 msg/s（小积压时不可信） |
| FULL（1 coupon 副本） | 4.5-7.0s | **400/400，0 个 429**（FULL 口径阈值更高） | 55-68 msg/s | 0.34-1.34s（消费跟得上入口） | broker 侧不可读 |
> 上表取"连跑两轮后的稳态"。冷启动首轮明显偏差：FULL 刚起来的第一个 400 条测到入口 8.4s、
> 端到端 18 msg/s、尾部排空 13.3s —— JIT、连接池、broker 路由注册都还没热，别拿它当容量。

> 别把这张表读成"FULL 比 LITE 慢"：FULL 的入口多了一跳 nacos `lb://` 和一次向 **x86 模拟
> （Rosetta）运行的 broker** 同步投递，这两项在这台 Apple Silicon 上是纯开销，真机 x86 上
> 不成立。这台机器上真正成立的结论是：LITE 的洪流保护（早拒 + 有界积压）按设计生效，
> 而 FULL 把同一批流量从"拒掉 40%"变成"全收 + 无积压"，代价是入口单请求更贵。

**这就是 LITE 服役档的容量口径**：低峰常态承载足够，再往上就是升 FULL 的场景，而不是继续调 LITE 的参数。

**剩余可挖**：同步入口每单仍打 4 个 autocommit，合并到 1-2 个约值同样 1.5 倍，但要注意
幂等"抢占"与业务写同事务会失去 in-flight 抢占记录的可见性（并发重复请求将看不到彼此的
PROCESSING 状态），需要连同幂等语义一起评估，不是纯性能改动。

### LITE 跑通 ≠ FULL 跑通

| 差异 | 后果 |
|---|---|
| 消息重试语义：RocketMQ broker 侧持久化 + 指数退避重试队列 vs Redis Stream 容器内 3 次后放弃（靠本地消息表补偿重投） | 削峰行为不等价；Stream 无 broker 侧堆积策略 |
| **broker 广播地址按交付形态分两份**：一台 broker 只广播一个 `brokerIP1`，而本机进程要 127.0.0.1、容器要 `host.docker.internal`（宿主机 JVM 连不通那个 fake-IP，实测 RemotingConnectException） | 两个启动脚本各自 `--force-recreate` broker；挂错会当场异步全失败并退回本地消息表（不丢但延迟），见第三节 |
| **单库共享**（两个形态默认都是单库 `marketing`） | 跨模块 join 在**两个形态里都不会被 DB 拦住**——原四库隔离本来是一道真防线，现在只能靠约定（FULL 想隔离：`MYSQL_DB_PER_SERVICE=1 ./scripts/start-all.sh` + 挂 `docker/mysql/init` 的四库 DDL，已实测 34/34；代价是数据不再与 LITE 共用，也就不能原地来回切） |
| 单 JVM 承载四模块 | 掩盖服务间超时、部分不可用、连接池争用（LITE 一个 30 连接的池养四模块 + 8 worker + 补偿 Job；FULL 是 4×20 各管各的） |
| LITE 不带 Prometheus | `/actuator/prometheus` 暴露了但没人抓。`prometheus.yml` 两个 job 分别覆盖 FULL 的两种交付形态（`marketing-local` 打宿主机端口、`marketing-full-container` 打 compose DNS 名），**只有当前形态的 targets 会 UP** |
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
| POST | /api/activity/{no}/budget/deduct · GET /budget/remain | 预算扣减 / 实时余额。幂等键作用域是 **(活动, bizKey)**，同一 bizKey 用在两个活动上是两次真扣；`data` 返回 `DEDUCTED`（本次扣了钱）或 `REPLAYED`（重复请求回放，没再扣） |
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

**已知噪音 ①**：网关启动时会固定打一条 `Unable to load io.netty.resolver.dns.macos
.MacOSDnsServerAddressStreamProvider` 的 ERROR —— macOS 上 netty 原生 DNS 解析器的可选本地库缺失，
回落到系统解析器，功能无影响。不为它往交付物里加平台特定依赖（`netty-resolver-dns-native-macos`
只在 macOS 有意义，会污染 Linux 部署）。

**已知噪音 ②（已防护）**：网关偶发 `reactor.netty.http.client.PrematureCloseException:
Connection prematurely closed BEFORE response` → 该请求 500。机制：上游 Tomcat 空闲 **61.3s**
主动关 keep-alive 连接（本机实测），而 reactor-netty 连接池默认 `max-idle-time` 不限、
`eviction-interval=0` 不清理，池里的连接可能比上游活得久，复用到一条已被对端 FIN 掉的连接就命中。
现在把客户端改成先退役：`max-idle-time=30s` + `eviction-interval=10s`（`GW_POOL_MAX_IDLE` /
`GW_POOL_EVICT_INTERVAL` 可调）。诚实边界：这是**预防性**修复——这个竞争窗口在这台机器上没能
确定性复现（专门攒 20 条连接再空闲 70s 后重打，两轮 40 次未触发），只在长跑中撞到过一次，
所以只能说"窗口按配置消掉了"，不能说"复现→修复→不再复现"闭环验证过。除此之外，三套形态
跑完冒烟的 ERROR 计数为 0（除这两条）。

**冒烟（39 条，四链路）**：链路 0 活动中心（草稿→提审→灰度→上线→终态、非法流转 41001、
重复活动号 41000、超预算 41003、灰度命中、可参与位切换）+ **预算算术守卫**：
被拒扣减不留痕、重复扣减在 `data` 里标 `REPLAYED`、同 bizKey 换活动仍真扣 `DEDUCTED`；
链路 1 领券；链路 2 优惠计算；链路 3 秒杀 + 并发防超卖。

并发段的库存基线**从接口读、不写死**，并断言恒等式 `分桶余量 + DB 已售 == 总库存`
（对超时取消抖动免疫，超卖/漏扣/回补异常都会破坏它）。因此可连续重复运行：已实测连跑 3 轮全绿。

**三套形态的端到端覆盖矩阵**（每格都是真跑 `smoke-test.sh` 的结果，不是推断）。
断言集在长（S2 加了 5 条预算/消息守卫），所以标了跑时的断言数 —— **34 那几格是当时版本全绿，
不代表已在 39 条断言下复跑过**；复跑齐了要在这里更新，别拿旧格子当新结论。

| 形态 | 最近一次 | 通道证据 |
|---|---|---|
| LITE 服役档（容器） | **39/39** | Redis Stream 键 + XDEL 生效（跑完 XLEN 恒 0）；`local_message` 零在途、键形 `grant:<requestId>` 两侧一致 |
| dev 开发档（本机 2 JVM） | 34/34 | 同上 |
| FULL · 本机进程形态 | 34/34 | `local_message` 全 CONFIRMED、broker 消费组积压 0、Stream 键为 0 |
| FULL · 本机进程 · 四库隔离档 | 34/34 | 数据按服务落在 4 个库、两张补偿表零残留 |
| FULL · 容器化 1 副本 | 34/34 | 同上 |
| FULL · 容器化 2 副本（seckill + coupon） | 34/34 | 同上 + 第三节的多副本三条证据 |

## 七、扩展点（占位 → 生产的升级路径）

| 占位 | 生产替换 |
|---|---|
| `AllowAllRiskCheckService` | 风控中心 RPC + 设备指纹 + 黑名单布隆过滤器（接口不变） |
| 网关演示 Token 鉴权 | OAuth2/JWT 网关鉴权 + 用户维度限流键 |
| `@Scheduled` 超时回补/消息补偿 | 已做多实例去重（Redis 周期租约，见 `RedisLeaseLock`）；按 user_id 分片仍是 XXL-Job / SchedulerX 的事 |
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
│   ├── docker-compose.data.yml    # 常驻数据层（mysql 单库 + redis AOF），三套形态共用
│   ├── mysql/init/01-schema.sql   # 4 库 DDL + 种子数据（FULL，自动执行）
│   ├── mysql/init-lite/           # 单库 DDL + 种子数据（数据层默认，自动执行）
│   ├── mysql/init/01-schema.sql   # 四库布局（可选隔离档，配合 MYSQL_DB_PER_SERVICE=1）
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
