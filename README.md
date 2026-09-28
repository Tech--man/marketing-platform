# 营销管理平台脚手架（marketing-platform）

面向 **日常 QPS 万级 / 大促 10 万+ / 秒杀 50 万+** 场景的可运行微服务脚手架（该量级是 **FULL 档的设计口径**，LITE 档当前实测容量见第三节末）。
架构原则：**分层限流、规则可编排、活动可灰度、全链路可降级、最终一致、ROI 可实时度量**。

- 券中心 / 优惠计算引擎 / 秒杀中心 **深度实现**
- 活动中心（状态机 + 预算 + 灰度）/ 风控 / 分销 / ROI **基础能力或扩展点占位**
- 标准拓扑：Gateway + Nacos + 4 业务服务；Seata / Flink / ClickHouse / ES / XXL-Job 留扩展点
- 形态：上面的标准拓扑是 **FULL 扩容档**（活跃期承接洪流）；非活跃期由 **LITE 服役档** 单 JVM 聚合进程承载（Redis Stream 代替 MQ，含管理后台 ≈1.15 GiB），两档共享同一份数据原地双向切换（见第三节）

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
| marketing-gateway | 8090 | 路由、两套凭证鉴权（C 端 `ConsumerAuthFilter` + 后台 `AdminAuthFilter`）、Redis+Lua 滑动窗口限流 |
| marketing-activity | 8081 | 活动状态机、预算 Redis 预扣 + 流水、灰度分流 |
| marketing-coupon | 8082 | 券模板/库存预热、领券、结果轮询、核销 |
| marketing-discount | 8083 | 规则 DSL、位图倒排索引、最优组合、分摊、超时降级 |
| marketing-seckill | 8084 | 分桶预热、Lua 抢购、MQ 异步下单、支付、超时回补 |
| marketing-account | 8087 | 消费者账号：注册/登录/刷新/登出/改密/会话与身份事件流水。LITE 与 dev 下**不新增进程**，聚进 standalone |
| marketing-admin | 8086 | 管理后台：后台账号/会话/审计/运维入口（登录、改密踢会话、强制下线、重预热）。LITE 与 dev 下**不新增进程**，聚进 standalone |
| marketing-standalone | 8085 | LITE 服役档 / dev 开发档的聚合进程：四业务模块 + 后台 + 账号 单 JVM + Redis Stream 消息（见第三节） |

## 三、形态与环境

这套脚手架的**核心目的**：同一份业务代码按流量在两个容量档之间原地切换——非活跃期由小机器
（LITE）常态承载服务，活跃期升档到与生产同构的 FULL 承接洪流。**切换只换应用侧进程形态与
消息通道，不动数据、不改代码**；允许双向（升档也降档）。

| 形态 | 定位 | 拓扑 | 消息通道 | 数据库 | 可靠性口径 |
|---|---|---|---|---|---|
| **LITE 服役档**<br>（preview） | 非活跃期 7×24 真跑流量，小机器常态承载 | 全栈容器化 4 容器：mysql / redis / standalone（四业务模块 + 后台 + 账号聚合）/ gateway | Redis Stream | 单库 `marketing` | Redis **AOF everysec + noeviction**、MySQL flush=1、restart 策略、日志轮转 |
| **FULL 扩容档**<br>（prod） | 活跃期承接洪流，与生产同构 | 两种交付形态二选一：**本机进程** 7 JVM（8081-8084、8086、8087 + 8090）／**容器化** 一容器一服务且可 `--scale` 多副本（只有网关发布端口，后台与账号服务不对外直连）；配 RocketMQ + Nacos + Prometheus | RocketMQ | 默认同一单库（可选每服务一库，六个库：四业务 + `marketing_admin` + `marketing_account`） | 中间件默认全持久化 |
| **dev 开发档** | 本机改代码，允许丢数据 | 中间件容器 + 本机 2 JVM（standalone 8085 / gateway 8090） | Redis Stream | 单库 `marketing` | 不持久化；**淘汰策略仍与 LITE 一致** |

LITE 的省内存**一律不靠牺牲可靠性换**，四条不可退让：淘汰策略必须 `noeviction`
（`allkeys-lru` 会静默丢掉库存/券预扣/幂等键，后果是超卖与重复领券）；状态要落 AOF 且挂卷；
容器要有 restart 与 json-file 日志轮转（默认 json-file 不限量，长期跑撑爆磁盘）；
GC 用 G1 而非 SerialGC（SerialGC 的 Full GC 停全部线程，表现为结算秒级尖峰）。

### 形态 × 入口 × profile：三个词别混

上面那张表里有三套名字在同时被用，它们是**多对一**的，不是正交的两轴——
"LITE 能不能跑 prod"这类问题问出来，通常是因为把这三层叠成了一层：

| 层 | 它是什么 | 取值 |
|---|---|---|
| **`DEPLOY_FORM`** | **形态的唯一定义处**。⑤ 用它决定在线配置读哪一行（`admin_config.form`），④ 用它决定"standalone 是不是不适用" | `DEV` / `LITE` / `FULL`（配置表里另有一档 `GLOBAL`=全形态共用） |
| **入口脚本 / compose 文件** | "从哪儿起"。历史上叫 dev / preview / prod 三套环境档位，**它们不是 Spring profile** | `start-dev.sh`、`deploy-preview.sh`、`start-all.sh`、`deploy-full.sh`；`docker-compose.{data,preview,prod,full-app}.yml` |
| **Spring profile** | 代码里真实的开关，只管一件事：要不要注册发现与配置中心 | **只有 `nacos`**（其余走默认文档，即 local 静态路由）。没有 `dev`/`preview`/`prod` 这三个 profile |

映射表（每条入口脚本自己写死一个 `DEPLOY_FORM`，没有第二处会改它）：

| 入口 | `DEPLOY_FORM` | 应用侧装配 | 消息通道 | 中间件 compose |
|---|---|---|---|---|
| `start-dev.sh` | `DEV` | 本机 2 JVM（standalone 8085 + gateway 8090），四业务模块与后台**聚进一个进程** | Redis Stream | data |
| `deploy-preview.sh` | `LITE` | 全栈容器，**聚合方式与 dev 相同**（compose 里 standalone 与 gateway 各写一份） | Redis Stream | preview |
| `start-all.sh` | `FULL` | 本机 6 个独立 JVM（默认无 profile=local 静态路由，`PROFILES=nacos` 才走注册发现） | RocketMQ | prod |
| `deploy-full.sh` | `FULL` | 6 个应用容器 + `lb://`（`full-app.yml` 里同时下发 `SPRING_PROFILES_ACTIVE=nacos`） | RocketMQ | prod + full-app |
| `MYSQL_DB_PER_SERVICE=1` | 仍是 `FULL` | 同上，只把数据层拆成 5 库 | RocketMQ | 同上 |

所以 `LITE × prod` 这种组合在本仓**不成立**，理由不是硬件不够：`prod` 那套中间件给的是
RocketMQ + Nacos，而"LITE"这个词的定义里就含"四模块聚进一个进程 + 消息走 Redis Stream"——
换过去它就不是 LITE 了。硬件只决定"这一档跑不跑得动"（第三节末的容量口径与
`load-probe.sh` 的读数），**不决定它是哪一档**。

同一层还有个容易读错的点：**dev 与 LITE 概念上不互斥**（同一轴的三个取值而已），
互斥的只是这台机器上抢宿主 `8090` 的那两组进程。OrbStack 在 macOS 上重复发布同一端口**不报错**，
于是"两套都在跑"看着成立、实际打到同一套（见第六节 已知噪音 ⑥，`deploy-full.sh` 有 pid 闸拒启）。

**一处已知的静默风险（还没补闸）**：`DEPLOY_FORM` 只是个字符串，**没有任何启动期校验**拿它和真实装配对拍。
取值不认识时 `ConfigSnapshotPoller` 只 WARN 一条"按 GLOBAL 解析"，未设置时在线配置只对
`form=GLOBAL` 的行生效；而标签写错的表现是安静的——⑤ 会去读另一档的真值，④ 的 `liveness`
会把该在的进程标成"不适用"或"没在跑"（`OpsSnapshotService.isAggregatedForm()` 的判据就是这个标签）。
修法很直白：启动时断言"聚合形态 ⇒ 本 JVM 里能看到四业务模块且 `marketing.mq.type=stream`、
分进程形态 ⇒ 只装一个模块且走 RocketMQ"，不匹配就启动失败点名，而不是留一本人能读错的面板。

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
                                      # prod.yml 只管 nacos/prometheus 与中间件，**不含应用容器**：
                                      # 应用容器在 full-app.yml（项目名 mkt-full），漏拆它 8090 一直被占，
                                      # 下一档（dev / LITE）的网关会因端口冲突起不来

# FULL 扩容档 · 容器化形态（一容器一服务，可 --scale 多副本）
export ADMIN_JWT_SECRET=$(openssl rand -base64 32)   # 容器形态必须显式导出（gateway 与 admin 同值），
                                      # 缺失时 deploy-full.sh 在入口直接退出，不会起一个后台不可用的栈
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
| 8086 | 管理后台（仅 FULL 本机进程形态直连需要；容器形态不发布端口） | 形态侧：FULL |
| 9876 / 10911 / 8848 / 9091 | RocketMQ / Nacos / Prometheus | 仅 FULL 形态 |

**后台相关的两个环境变量**（三套形态都要给，且 gateway 与签发方必须同值）：

| 变量 | 作用 | 缺配的后果 |
|---|---|---|
| `ADMIN_JWT_SECRET` | 后台 token 的 HS256 密钥 | 空值时 admin 侧启动即失败（宁可不签，也不签一枚谁都能伪造的 admin token）。LITE 由 `deploy-preview.sh` 随机生成并落在 `.admin-jwt-secret`（0600、已 gitignore）复用；FULL 由 `deploy-full.sh` 在入口显式拦。dev/start-all 用 `dev-only-secret-change-me` 占位并打 WARN |
| `DEPLOY_FORM` | 空（=只认 `GLOBAL` 覆盖） | 形态标识 `LITE`/`FULL`/`DEV`：在线配置按它选生效值（`form 行 > GLOBAL 行 > 出厂值`）。**不部署它就等于没有这套机制**；取值拼错也退回 GLOBAL 并告警——把 FULL 的阈值套到形态名拼错的进程上是本仓库最贵的一类错 |
| `CONFIG_POLL_SECONDS` | `5` | 各进程比对配置版本的节拍，即"改完阈值到全档生效"的延迟上限 |
| `GRAY_REFRESH_SECONDS` | `5` | 灰度规则回源 DB 的节拍（灰度真值在 `activity.gray_percent`，不依赖 Redis） |
| `RL_ADMIN` | 后台路由的限流阈值（默认 50/s） | 路由 id 不在限流 map 里＝完全不限流；后台登录口的 BCrypt 单次 50-100ms，几十 QPS 就能把与 C 端同进程的后台打满 |

**注册中心模式已实测**（`PROFILES=nacos ./scripts/start-all.sh`）：5 个服务全部注册进 Nacos；
再起第二个 discount 实例（`SERVER_PORT=8093`）后 Nacos 显示两个 host，经网关打 20 次同步请求，
`mkt_discount_calc_seconds_count` 计数 **8083 与 8093 各 10 次** —— lb:// 轮询负载均衡成立，
这是 FULL "加实例承接洪流" 的前提。

**FULL 的异步链路两种交付形态都已实测**（2026-09-22，见上节"快速开始"的两条命令）：
本机进程形态与容器化形态各跑满 34/34（后台上线后同一数据卷复跑为 52/52，见第六节），
`local_message` 全程无 PENDING 残留、Redis 里
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
同日按 52 条断言逐段复跑（数据卷自始至终未重建，账号表与审计表也跟着跨形态）：
LITE 52/52 → FULL 本机进程 52/52 → FULL 容器 52/52 → dev 52/52 → 回 LITE 52/52。
**每服务一库的隔离档也按 52 复跑过**（`MYSQL_DB_PER_SERVICE=1`）：`start-all.sh` 的库名映射
自动产出第 5 库，后台的会话与审计确实落在 `marketing_admin`（6 会话 / 15 审计行），
单库档留下的 46 条旧会话留在 `marketing` 里，两边互不串。

> **换库布局不是换形态**：上面"原地切换"成立的前提是两侧读同一个库。
> 单库 ↔ 每服务一库之间切换时，Redis 分桶键是**共用一套**的（键名只有活动号，不带库名），
> 而 `sold_stock` 各算各的 —— 实测不重置就冒烟会红在恒等式上（`余量+已售 != 总库存`，
> 差值是上一布局的消费记录）。这不是账目错，是两个布局在同一份 Redis 上互相不认识。
> 换布局后跑一次 `./scripts/reset-demo-data.sh`（删桶 + 重启持有预热的服务）即可对齐。

切换时**唯一需要留意的是消息通道**：升档瞬间 LITE 侧 Stream 里未被消费的消息不会自动转到
RocketMQ。正确性靠本地消息表兜住（未 confirm 的消息由 `LocalMessageRetryer` 按退避重投，
新进程装配的是新通道，因此会自动改投），代价是最多一个退避周期（≤ 300s）的处理延迟；
降档方向同理。因此切换应选低峰期，并在切换后确认
`SELECT status, COUNT(*) FROM local_message GROUP BY status` 无长期 PENDING/SENT 残留。

### 内存实测

Apple Silicon 开发机 + OrbStack；容器取 `docker stats`，本机进程取 `ps` RSS（口径一致可横向比）。

| 形态 | 应用侧 | 数据层与中间件 | 合计 | 测量口径 |
|---|---|---|---|---|
| **LITE 服役档** | standalone 517-599 + gateway 323-361 MiB | mysql 172-266 + redis 8-13 MiB | **≈ 1.09-1.24 GiB** | `docker stats`；standalone 内含管理后台模块。区间是同日多次采样的跨度 —— JVM 常驻集随负载与运行时长爬升，单点数字会骗人。**加后台没触到调 `mem_limit` 的阈值（640 MiB）**，最高一次 599 MiB；⑤ 复跑当天两次采样 517 / 526 MiB（区间下沿因此放宽）；④ 复跑跑完七条链路 551.6 MiB（gateway 362.8），点大盘的抓取成本没把它顶出去 |
| **dev 开发档** | 2 个本机 JVM ≈ 175-244 MiB | 数据层 184-256 MiB | **≈ 0.35-0.49 GiB** | JVM 部分是 `ps` RSS，**macOS 下会低估**（文件映射与压缩页不计），只宜横向比；区间是多次实测（④ 复跑 standalone 175 MiB），差值主要是 MySQL 缓冲池预热程度 |
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
C 端种子账号 `demo / demo123456`（`CONSUMER_JWT_SECRET` 签发的 JWT，见"消费者账号体系"一节）。

**构建要求**：Maven 必须跑在 **JDK 17**，`scripts/*.sh` 已通过 `scripts/common.sh` 自动锁定。
Homebrew 默认 JDK 已滚到 26，Lombok 1.18.x 在其上无法运行注解处理，表现为满屏
`cannot find symbol: log / setXxx`。

## 四、七条核心链路（"写入口收口"是横切边界，见第五节的写入口矩阵）

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
- `POST /api/admin/discount/rules` upsert 规则后 bump 版本号，全实例秒级生效
  （③ 之前这条在 C 前缀下，任何拿到共享 demo token 的人都能改规则 DSL —— 已收进后台前缀，
  GET 也一并收，因为规则列表本身就是可反推定价策略的资产）
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
- 预热口径收在 `SeckillWarmUpService`（启动 `SeckillWarmUpRunner` 与运维重预热共用一份）：
  SETNX 预热，重启/多实例不重置已售进度；**非 ONLINE 或已过结束时间的活动拒绝重预热**
  （否则 force 重预热等于把已下线活动的库存重新开闸）

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
- 已知边界：业务服务端口绑 `*:808x`（FULL 进程形态）、standalone 的 8085 也发布了（LITE），
  所以后台身份**不认裸 `X-Admin-*` 头** —— 网关验签后注入的是 `X-Admin-Token`，服务侧用同一枚
  HS256 密钥再验一次签名；绕过网关只带裸头直连必然 `40100`（链路 6 同时钉"带合法 token 时
  身份放行"，否则 `40100` 也可能只是"端口不通"的另一种写法）。四个 C 端**交易**路径本来就不
  校验 token（鉴权在网关），所以**任何形态下都不该把业务服务端口暴露到不可信网络**

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
`mkt-*`，端口只接受 8081-8086 与 8090，路径是常量 `/actuator/prometheus`），请求期不再接受
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

**dist 的三道闸**（仓库没有 CI，所以只有第三道会被自动跑到）：
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
真浏览器走了一遍登录 → 十页渲染 → 界面改秒杀库存并核对 Redis 分桶真的按 `total-sold` 重建
（证据 `docs/superpowers/evidence/6-browser-journey.md`）。

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
- `CalcInput.userTags` 仍是调用方声明的标签（会员等级/人群包的来源不在本项目内），本轮没收口。
- `/api/seckill/grab/result/{token}` 只做登录门，没做本人校验：token 是 UUID 且结果只含状态与单号。
  `/api/coupon/grant/result/{requestId}` 做了本人过滤（那里返回的是可兑付的券码）。
- `CONSUMER_JWT_SECRET` 必须与 `ADMIN_JWT_SECRET` 是**不同**的值：同值的话两套凭证的隔离只剩
  claim 形状的侥幸。deploy-preview/deploy-full 的入口会拒绝同值与留空。
- 上面这三条语义 + 登出与改密的"当场生效"，都由**冒烟链路 9** 端到端钉住（配对形状：先验有效、
  再验作废、最后验新状态可用）。五套形态的实跑与它抓到的一处真缺陷（重放时代码按已被轮换掉的
  `refresh_hash` 找受害会话，必然查不到 → 只拒不吊销）见 `docs/superpowers/evidence/9-identity-five-form-runs.md`。

## 五、API 速查（经网关 8090；C 端需登录换来的 `Authorization: Bearer <accessToken>`，后台需 admin token）

| Method | Path | 说明 |
|---|---|---|
| GET | /api/activity/{no}/participatable · /gray-hit?userId= | 可参与校验 · 灰度命中判断 |
| POST | /api/activity/{no}/budget/deduct · GET /budget/remain | 预算扣减 / 实时余额。幂等键作用域是 **(活动, bizKey)**，同一 bizKey 用在两个活动上是两次真扣；`data` 返回 `DEDUCTED`（本次扣了钱）或 `REPLAYED`（重复请求回放，没再扣） |
| POST | /api/coupon/grant · /consume | 领券受理 · 核销 |
| GET | /api/coupon/grant/result/{requestId} · /usable?userId= · /stock/{templateNo} | 轮询 / 可用券 / 模板余量 |
| POST | /api/discount/calculate | 优惠计算（购物车 → 命中规则 + 行级分摊）。规则读写自 ③ 起只在后台前缀 |
| POST | /api/seckill/grab · /pay/{orderNo} | 抢购 · 模拟支付回调 |
| GET | /api/seckill/grab/result/{token} · /activities · /stock/{activityNo} | 轮询 / 活动列表 / 分桶余量 |
| POST | /api/auth/register · /login · /refresh | 注册 / 登录 / 换一对新凭证。这三个是 `/api/**` 里**仅有的**免 access token 入口（靠网关例外清单，见第四·五节）；refresh 只认请求体里的 refreshToken，且旧值重放 = 整条会话作废 |
| GET·POST·PUT | /api/auth/me · /sessions · /logout · /password | 当前身份 / 我的会话列表 / 登出（当场失效，不等自然过期）/ 改密（成功后该账号全部会话作废）。都要带 access token |
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
  由 admin 每 5s drain 落表并 `XACK`+`XDEL`。为什么不用 ⑤ 候选的 `LPUSH+LTRIM+TTL`：
  TTL 淘汰等于**静默丢审计**。建组从 `0` 开始（默认的最新位置会让"先投递后建组"那批永远不被读）。
- **乐观锁无处不在**：所有改配置的行都带 `version`，撞了回 `41008 已被他人修改`（附带你看到的
  与当前的两个数），而不是后写覆盖先写 —— 后台是多人的，运营 A 看到的页面可能已经过时十分钟。
- **C 端旧写路径直接删**，不留 301/转发别名：留着就等于"收口"只是加了一层前缀，
  而共享的 demo token 依然能改预算。命中 `40400` 是这件事的唯一可测证据，链路 6 钉的就是它。

## 六、测试与验证

```bash
mvn test                 # 419 个单测 / 82 个类：见下
cd marketing-admin-ui && npm test    # 46 条前端用例 / 9 个文件（vitest + jsdom，见 ⑥）
cd marketing-h5-ui && npm test       # 90 条 C 端用例 / 7 个文件（响应形状与 40101/40102 分流）
./scripts/build-ui.sh    # ⑥ 唯一的前端重建入口（产物入仓，机器上没 node 也能跑 jar）
./scripts/check-ui-dist.sh  # jar 与仓库的 static/ui 逐项 sha256 比对（前提：已 package）
./scripts/build-h5.sh    # C 端 H5 的唯一重建入口（产物入 static/h5）
./scripts/check-h5-dist.sh  # 同一道产物闸的 H5 版（前提：已 package）
./scripts/smoke-test.sh  # 端到端 113 条断言 / 九条链路，需服务已启动
                         # （LITE 与 dev 各实测 113/113，2026-09-25；FULL 两种形态见第六节矩阵）
./scripts/reset-demo-data.sh [总库存]  # 演示容量复位（默认 5000；③ 起走后台端点，不再 restart 应用）
```

**单测（419 用例 / 82 类）**分十族：

- **业务语义**：三层幂等（首执/回放/PROCESSING 拒重入/FAILED 可重抢）、非法状态流转拒绝、
  比例分摊尾差归末项、末行占满顺延、互斥组最优（priority desc → discount desc）、
  叠加超限精确枚举、1 万规则基准耗时；
- **资金与消息正确性（管理后台地基 S1/S2 补的）**：预算重算按流水对账（H2 上跑真 SQL，
  不再按满额回涨）、扣款与退款流水同向、跨活动同 bizKey 各自真扣、回滚只删本活动行、
  本地消息表同键跨 topic 互不干扰、退避只动自己那行；
- **公共契约**：分页元信息与越界夹取、BizKey 长度有界且确定、JWT 验签/过期/时钟偏移/篡改、
  重预热注册表分发与重复注册；
- **后台鉴权与审计**：登录判定的四个边界（账号不存在≡口令错、停用/锁定先于口令、锁定到点
  自放行、失败阈值清零计数）、HS256 验签、两套凭证互不相通（C 端 token 打后台 401、
  只读角色写 403、缺密钥时后台整片拒而 C 端不受影响）、审计摘要脱敏（JSON 与 key=value
  两种形态、驼峰与下划线都盖）；这一族的变异检查是必须的——把"敏感键判定"改成恒 false，
  5 条里红 4 条；把网关的"放行登录口"改成恒真，10 条里红 6 条；
- **消费者账号（C 端身份）**：codec 的签/验往返（改一个字符就验不过、`exp` 之内容忍时钟偏移、
  **refresh 不能当 access 用**、换一把密钥签的一律无效、垃圾输入只回 MALFORMED 不抛异常）、
  登录判定的边界（账号不存在按口令错处理、停用与锁定都先于口令判定、锁定时刻已过不再算锁定、
  到阈值上锁 15 分钟并清零计数）、限速键"恰好等于限额仍放行"、以及 Redis 取不到时**按放行**
  （宁可少挡一次撞库，也不让身份链路因为一个计数器而整片不可用——与 `RedisLeaseLock` 同一条取舍）；
  还有一条是本轮补的**合成用例**："先轮换、再重放旧值"必须吊销那条**还活着**的会话。
  分开测时两边各自绿（`refreshRotates` 只验 rotate 被调、旧版重放用例把 `findByRefreshHash`
  stub 成查得到），而真值里 rotate 是同一行就地换摘要 → 旧摘要永远查不到 → 吊销从未发生。
  变异检查：把实现退回"按摘要找"，这条立即红（`Wanted but not invoked: revoke(...)`）。
- **在线配置下发（⑤）**：形态解析优先级（`form 行 > GLOBAL 行`，且**别的形态一行都读不到**）、
  逐条类型/边界校验与"未声明即忽略"、快照 JSON 坏了退化成空快照、轮询器版本比对与
  "Redis 抖动时保住现值"、`ConfigSyncer` 让位条件、后台写侧四道裁决（未声明/越界/非法 form 拒、
  删不中 404）、`INCR` 序号与"落库未广播 → 41009 + 重广播"、灰度 CSV 与 `[0,100]` 钳位、
  `PromoEngineOnlineLimitTest`（在线把叠加数压到 1，引擎真的只应用一条）。
  变异检查做了 20 处，全部咬人：例如把 `ConfigMerge` 改成"不区分别人的 form"，两条隔离断言立即红；
  把 `resolve` 的"路由不在 map 里=不限流"改成 `limit=0`，那条暗道断言就红。
- **业务管理面（③）**：四个 owning 进程各自的后台端点（列表/创建/改值/上下线 + 乐观锁），
  身份只认**验过签名的** `X-Admin-Token`（缺 40100、过期 40101、篡改 40100、角色不匹配 40300），
  审计载荷的编解码（读侧永不抛）、`AuditOutbox` 投递与 admin 侧 drain（ACK→XDEL、脏载荷跳过、
  Redis 抛异常不崩线程、建组 offset 必须是 `0`）、跨进程重预热的三分岔（本进程同步 `DONE` /
  投出去 `DISPATCHED` 且"标记先于 XADD" / 无人认领才 `41010`）与执行侧回执（失败也必须写
  `FAILED`，否则后台只能把没回音猜成排队中）。T1-T8 逐批做了 20 处变异检查，全部咬人 ——
  其中三处专门用来防止"看起来实现了其实没实现"：去掉 controller 的同步分支、把投递顺序反过来、
  把建组 offset 改回默认最新位置。
- **运维只读聚合（④）**：Prometheus 文本解析（`_total` 与 timer 的单位后缀、标签里的尾逗号、
  畸形行计数而不是抛异常、`application` 标签剥离）、target 白名单的**启动期**整表校验
  （SSRF 正面清单：host 正则 + 端口枚举 + 常量路径，任一条不合格整份清单失败）、
  `ProxyMeterSource` 用 JDK `HttpServer` 回环桩验超时/非 2xx/**超 256KB 判失败而不是给半份**、
  跨库积压发现（`information_schema` + `LOWER()` 两边都套）、逐库失败只让那一行变 `-1`、
  Stream 深度按"本形态走哪条通道"分岔（不适用 ≠ 0）、`CacheConsistencyRegistry` 的绑 gauge 与
  重复注册启动即失败、快照服务的三态判活与 `mode` 推导、以及网关 429 计数带 `route` 维度。
  三个 owning 模块的 `mismatchCount()` 各自被测（预算按流水对账、券按 `remainOf`、秒杀分桶求和
  + 缺桶计入不符），判定刻意与重预热同式。变异检查覆盖解析器静默少报、`-1` 被换成 0、
  白名单退化成"能解析就行"等最贵的那几类；
- **后台界面（⑥，vitest 那一套）**：api 客户端（凭证注入、业务码非 0 抛错并带整份响应、
  **三个 401 类码走三条路**、被吊销的会话绝不自动重放、42900 把 `Retry-After` 带出来）、
  会话 store（TTL 只取登录响应、角色→按钮、退出失败也清本地）、`TriState`
  （-1/"不适用"/真 0 三种画法，绝不归一化）、`UiResourceSupport`（回退判据 + 缓存头 + CSP，
  用 `MockHttpServletResponse` 测）、`GatewayUiRouteConfigTest`（按 `---` 拆 yml 文档，
  逐段断言 local 与 nacos 都有 `ui-route`、白名单、限流桶，并钉住"探针自己的分段假设"）、
  `UiDistIntegrityTest`（产物自洽与孤儿检测）、`routes.spec`（侧栏每个链接都必须
  resolve 到带组件的记录——大盘一度只在空路径上注册，点进去整页空白而单测全绿）。
  变异检查逐条做过，并且**先证明文件真的被改了再跑**：第一版有一条 perl 锚点写错，
  变异没落地却报"没抓到"——那比漏红更坏。
- **装配层回归**：聚合形态扫描边界 + common 条件装配矩阵（含重预热注册表），
  用 ApplicationContextRunner + H2 + `127.0.0.1:1` 永不连接的 Lettuce 满足类型条件，
  不依赖中间件。这族把"预览栈起不来"这类装配 bug 从 2-3 分钟的构建+部署排查压到秒级。

写这些用例时用的是 `MODE=MySQL` 的 H2 跑**真 SQL**（`INSERT IGNORE`、唯一索引作用域都能验），
并且对关键断言做过变异检查：把生产码改回旧值，断言必须红
（例：`expected: <40400> but was: <41000>`）—— 不然只是自证通过。

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
跑完冒烟的 ERROR 计数为 0（除这几条）。

**已知噪音 ③（新卷已修，老卷要手工跑迁移）**：`docker/mysql/init*/` 的种子中文在**已建好的卷**
里是乱码（`2026 秋季大促` 存成 `2026 ç§‹å­£å¤§ä¿ƒ`）。机制实测清楚：init 脚本由容器内 mysql
客户端执行，而 `character_set_client` 默认 **latin1**（哪怕服务端是 utf8mb4），中文先被按 latin1
解释再存 utf8mb4 就是双重编码。两份 init 已在开头补 `SET NAMES utf8mb4`，**新建卷不再复现**；
现存卷跑 `docker/mysql/migrate/2026-09-22-fix-seed-encoding.sql`（按"目标字面量"做守卫，
重复执行是 no-op，也不会二次损坏已正确的行）。应用自己写入的中文一直是对的
（JDBC 连接串带 utf8），所以只有种子受影响 —— 排查时看到"业务数据中文正常、演示数据乱码"
就是这一条。

**已知噪音 ④（已防护）**：FULL 容器形态 `deploy-full.sh` 跑完、网关自己健康了，
**五条路由却可能还没通**：Spring Cloud Gateway 对 `lb://` 是首次命中才去 Nacos 订阅该服务，
订阅与实例推送到位前回 503 空响应。表现为"冒烟 28 条 C 端全红"，而症状与"异步链路坏了"
一模一样（实测被它骗过一轮排查）。现在部署收尾会按路由各打一发真实请求、等到 `"code":0`
才宣布就绪，等不到就非零退出。**探针路径本身也会漂**：券路由那条原本打
`/api/coupon/templates`，该接口早已不存在 → 恒 40400，部署每次白等 120s 再非零退出，而冒烟却全绿
（两者判据不同，最容易骗人）。现在探针改成 `/api/coupon/stock/CT2026001`，并把末次响应打进失败文案——
"路径漂了"与"订阅没到位"是两种病，别再混着查。另一个同源现象：容器化部署时若同时重建多个服务，
Nacos 客户端有概率在 STARTING 阶段注册失败导致该 JVM 退出（`restart: unless-stopped` 会拉起），
遇到单个服务反复重启先看这个。

**同源现象⑤（实测）**：`deploy-full.sh` **每次**跑都会 `--force-recreate` broker（bind 挂载钉 inode，
只改 conf 不重建就不生效），所以部署完立刻跑冒烟，天然踩在"生产者/消费者重注册"的窗口上——实测会吃到
一次"消息投出去但 2 分钟没人消费"——链路 3 停在
`ACCEPTED`、40s 轮询超时，最后由 `local_message` 补偿扫描把它兜住（下单成功，只是慢）。
这不是②/⑤的回归，但它是**托底链路第一次被实测证明有效**：MQ 通道失联时消息不丢，代价是延迟从
秒级变成补偿周期级。要干净复跑，等 broker 起来一分钟后跑第二次；别把这一轮的红灯当成异步链路坏了。

**已知噪音 ⑥（已防护）**：容器化 FULL 与本机进程 FULL **可以同时"在跑"**——宿主 JVM 占着
8090/8081 时，`docker compose up` 发布同名端口**在 macOS 上不报错**，于是冒烟实际打到宿主机那套
JVM，而 `docker ps` 看着一切正常：五形态那张表里"容器档"那一格就此作废（复跑时靠 `lsof` 对端口
才发现）。现在 `deploy-full.sh` 见到 `run/*.pid` 里有活进程就拒绝启动，`start-all.sh` 那条
`mkt-preview-standalone` 的检查也一并生效。**换档必拆上一档**这件事，别再靠记忆。

**已知噪音 ⑦（跨库探测的坑）**：`information_schema` 的表名大小写两套引擎不一样——MySQL 存小写、
H2（`MODE=MySQL`）把未加引号的标识符折成**大写**。所以 ④ 的库发现那条 SQL 两边都套 `LOWER()`
（`WHERE LOWER(table_name) = LOWER(?)`），单测里第一版只写了一边，表现为"容器里查得到、单测里查不到"，
而失败模式是**静默少一个库**（积压少报），不是报错。

**已知噪音 ⑧（换了镜像才看得见）**：`SKIP_BUILD=1 ./scripts/deploy-full.sh` 复用**已有镜像**，
⑥ 之前构建的那批镜像里没有 `static/ui/`，于是 `/ui/` 回 404 而其余七条链路全绿——症状长得像
"网关 `ui-route` 没配对"，实际是镜像里根本没这个包。⑥ 的产物随 admin jar 一起打进镜像，
所以**只有源码变了又跳过构建**才会踩到。要验 ⑥ 那一格，别带 `SKIP_BUILD=1`；
分不清手上镜像新旧时，直接看索引页里的 `build-ui: rev=` 注释，它比 `docker ps` 的启动时间诚实。

**已知噪音 ⑨（两套库布局共用一个 Redis 时的 userId 撞号）**：`per_user_limit` 与"每人一场"的判定
在 **Redis**，键里带的是 `userId`（`coupon:user:{模板id}:{uid}`、`seckill:bought:{活动号}:{uid}`）。
而单库布局的账号表在 `marketing.consumer_user`、每服务一库布局在 `marketing_account.consumer_user`，
两边各自 `AUTO_INCREMENT` —— 同一份 MySQL 卷 + 同一个 Redis 上换过布局之后，**同一个号码会属于两个人**。
实测：每服务一库档新注册的冒烟账号从 70002 开始，而 70002-70008 在单库那套里已被上午的探针用过，
于是"全新的人"一出生 Redis 里就有它的限领标记，链路 1 与链路 6 六条断言一起红在 `41000 已超过单人限领`
上——红得极像"限领逻辑坏了"，实际是共用了一个号码。处置在冒烟侧：`require_consumer` 拿到注册响应里的
`uid` 后，只删这个刚出生 uid 自己的那两类键（不扫别人的）。生产上不会遇到，因为一套部署只有一种布局；
**但如果哪天让布局变成可热切的东西，这条就是必须处理的语义**（限领状态得跟着账号体系走，而不是跟着编号）。

**已知噪音 ⑩（换布局不换 Redis：库存桶会带着上一套布局的账）**：与 ⑨ 同一个根，只是这次是**库存**。
分桶余量存在 Redis（`seckill:stock:<活动号>:<桶>`），而"按 `total-sold` 重建桶"读的是**当前布局那个库**的
`sold`。实测这一轮：每服务一库档跑完（那套库里 sold=9，桶被重建成 4991）→ 换回容器档（单库 `marketing`，
`sold` 是 118）→ 冒烟的恒等式立刻红成 `remain=4991 sold=118 total=5000`，差 109。判据没错、库存也没错，
错的是**桶是上一套布局的账**。处置：换布局后先 `./scripts/reset-demo-data.sh`（它走 ③ 的后台端点，
同事务里 `reheat(force=true)` 按当前库的 `total-sold` 重建桶），再跑冒烟 —— 这一档照做之后 114/114 全绿。
④ 大盘的"缓存与账不符"三条恒等式盯的就是这件事：它在面板上看得见，不是只能靠冒烟撞出来。

**冒烟（LITE/dev 113 条断言 / FULL 两种形态与每服务一库 114 条，九条链路）**：链路 0 活动中心（草稿→提审→灰度→上线→终态、非法流转 41001、
重复活动号 41000、超预算 41003、灰度命中、可参与位切换）+ **预算算术守卫**：
被拒扣减不留痕、重复扣减在 `data` 里标 `REPLAYED`、同 bizKey 换活动仍真扣 `DEDUCTED`；
链路 1 领券；链路 2 优惠计算；链路 3 秒杀 + 并发防超卖；
**链路 4 管理后台**（登录、账号不存在与口令错同码、无凭证 401、**C 端 token 打后台被拒**、
只读角色写 403 而读放行、分页带 total 且不超 size、**地雷 A 的回归锚**：改 DB 后缓存不动 →
`reheat` 后 C 端余额等于新口径；被拒的重预热也留审计、登出后同一 token 立即 40102、
业务侧的写动作跑完必须从 `mkt:audit:pending` 全部 drain 落表（`XLEN` 归 0 且本轮那条活动在表里查得到））。
链路 4 的重预热按形态分岔判据（读 `/cache/types` 而不是猜环境变量）：
LITE/dev 验"本进程同步刷成功"，FULL 分进程验"投给 owning 服务 → 轮询回执到 `DONE` →
C 端余额等于新口径"。**多的那一条断言就是这个回执轮询，所以两档的总数不同（95 / 96）**，
两边都是真断言，没有跳过。

**链路 5 在线配置下发**：本档形态自证（`ownForm=LITE`）、写 GLOBAL 阈值 3/s **不重启**就吃 429、
越界与未声明的键在写侧就 40000（并同时断言报错文案，否则"body 没解析成功"能冒充"校验通过"）、
operator 越权改阈值 40300、给另一档写 199999 **不污染**本档、删光 8 个快照键后退回本档 yml
出厂值且网关继续服务、"重新广播"把在线值找回来、删行=恢复出厂、灰度按 DB 列改 5% 后 ≤8s
生效且**删光 Redis 键也不会变成全量放行**、种子灰度仍在、收尾断言 `admin_config` 归零
（跑挂了也不给下一档留一行极端阈值）。

**链路 6 写入口收口（③）**：`POST /api/activity` 与 `PUT /api/activity/{no}/budget` 命中 `40400`
（旧路径真的删了，不是转发）；C 端交易路径**没被误伤**（领券仍受理，用户段每轮随机 —— 券模板
`per_user_limit=1` 是跨轮持久的状态，写死 userId 会让第二轮起恒吃 41000）；后台 token 打 C 端
交易路径被拒（两套凭证不互通是**双向**的）；**只带裸 `X-Admin-*` 头绕过网关直连业务端口 → 40100**，
且同一发请求换成正牌 `X-Admin-Token` 时身份放行（`version` 故意写错停在 41008，不改共享种子）——
少了配对的那条，`40100` 也可能只是"端口不通"的另一种写法。直连地址按形态自适应：LITE/FULL 进程形态
打宿主 `:8081`/`:8085`，FULL 容器形态不发布应用端口，就 `docker exec` 进容器打它自己的 8081。

**链路 7 运维只读聚合（④）**：面板可读（200）且**自报这次读的是谁**（`mode` + 逐 target 的
URL/`local|proxy`/样本数/`ERROR`）、④ 的未排空合计与脚本自己按同一口径直连 MySQL 求和**逐库对拍**
（断言"两处相等"而不是"等于 0"——断 0 会把"没查到"和"没积压"混成同一种绿）、通道差异按面板自己
说的判据分岔（聚合档 `MKT_STREAM_*` 有值 / 分进程档显式 `applicable=false` 且不带 0）、
恒等式闭环（见下）、以及链路 5 制造过的 429 必须带 `route` 维度从**网关那个独立进程**里读得回来
（母版事实"网关永远独立进程"的正面证据）。④ 没有新增任何写入口，所以写入口矩阵一行都不用改。

恒等式那三条是这段最有价值也最容易写假的，踩过的两个坑都留在这儿：
**坑一，必须改 ④ 真在读的那 5 条**——判定是 `activity ORDER BY id LIMIT 5` 的抽样，链路 0 每轮新建的
`ACT-SMOKE-*` id 最大、永远落在样本之外，第一版就是这样红的（面板没错，断言在验一个没人看的活动）；
现在用只读 SQL 取样本首行。**坑二，动手前先把自己要碰的那条预热成一致**——换库布局时抽样里本来就
带着上一套布局的缓存漂移（每服务一库档实测基线 budget=1），所以断言写成"与基线比涨跌"
（`BASE → BASE+1 → ≤BASE`）而不是"等于 0"：既不被环境噪声左右，也不可能靠"面板恒 0"蒙过去。
另外 FULL 档的修是**投给 owning 服务**的，回执要等一个消费轮询，脚本必须等 `status:DONE`
而不是 `sleep 2`——不然红点会指向"③ 修不动"，而其实只是没等。

**链路 8 后台界面（⑥）**：六条都是"错了也不会响"的那类。首页 200 且**不带任何 token**（证明
`ui-route` 与白名单成对配好了）；索引页 `no-store`（缓存它就会拿旧索引去要新指纹 → 白屏）；
深链 `/ui/audits` 回退到索引页（history 路由直接刷新才打得开）；指纹资源给一年 `immutable`；
**缺文件必须 404 而不是回退成一份 HTML**（否则 js 报的是"模块加载失败"，把人支去查构建）；
最后一条是这段的边界所在——`无凭证 GET /api/admin/users` 仍是 `40100`：**加了界面不等于加了口子**。

**链路 9 消费者身份语义**：这一段刻意放在最后（它会把主会话烧掉，前八条都在用那枚 token），
断的是三条"错了不会有人立刻发现"的语义：**轮换过的旧 refresh 再出现 = 整条会话作废**
（先验 `/me` 通、换新凭证、旧值重放吃 40100，然后再用**刚换到的那枚新 access** 打 `/me` 必须是
40102 —— 只断 40100 分不出"拒了这一次刷新"和"吊销了整条会话"，而这两件事在实现里恰好是分开坏的）；
**登出当场生效**（另开一条会话，登出后那枚 access 立刻 40102，而不是等 15 分钟自然过期）；
**改密作废全部会话**（落在链路 6 那个探针账号上，不能拿 demo 做——改种子口令会让下一轮登录直接红；
断旧 access 立刻 40102 + 新口令能登录 + 旧口令仍被拒且对外文案不变）。
这一段的三条红各教会了一件事，都写进了上面的"脚本四条不变式"。


**脚本四条不变式**（都是踩过才写下的）：① `poll` 的针必须与紧随其后的断言针一致——轮询超时照样把
最后一次响应打出来，只查 `"couponCode"` 这个键名会让 `PROCESSING`（`"couponCode":null`）蒙过断言，
于是红点落在下一行的"核销 40000 couponCode 必填"上，看着像核销坏了（FULL 进程形态首跑那 1 条红就是它）；
② `expect` 拒绝空针，因为 `grep -q ""` 恒真，"上一步取值失败"会伪装成"这条通过"；
③ **JSON 请求体先收成变量再交给 curl**：`expect "..." '针' "$(curl -d "{\"k\":\"$V\"}")"` 这种
两层引号套在命令替换里的写法，送出去的 body 是残缺的（服务侧回 40000"请求体不是可解析的 JSON"，
而且一发请求炸出两条解析错误），把 body 提成 `BODY='{"k":"…"}'` 就正常。顶层赋值
（`R=$(curl -d "{…}")`）不受影响——所以老链路一直是对的，链路 9 新写的四条红全是这个成因；
④ **直连探针必须自己挑一个真在听的进程，且接受条件要排除 404**：`/api/coupon/usable` 打到 8081
（activity）会回 `40400 资源不存在`，那也是一段带 `"code"` 的 JSON，于是"被拒"与"路由不在这"同形。
判据收紧成"只接受 `0` 或 `401xx`"，候选端口按服务写死（券是 8082/8085，容器形态退到 `docker exec`）。

并发段的库存基线**从接口读、不写死**，并断言恒等式 `分桶余量 + DB 已售 == 总库存`
（对超时取消抖动免疫，超卖/漏扣/回补异常都会破坏它）。因此可连续重复运行：已实测连跑 3 轮全绿。

**五套形态入口的端到端覆盖矩阵**（每格都是真跑 `smoke-test.sh` 的结果，不是推断）。
断言集在长（S2 加了 5 条预算/消息守卫，后台那批加了链路 4 的 13 条，⑤ 又加了链路 5 的 20 条，
③ 加了 drain 落表 2 条 + 链路 6 的 7 条，④ 加了链路 7 的 8 条，⑥ 加了链路 8 的 6 条，
**消费者账号加了链路 9 的 12 条 + 链路 6 的 C 端对偶 2 条**），
所以标了跑时的断言数——**低于当前基数的格子只代表"当时那一版全绿"，不等于已在新断言下复跑过**。
2026-09-25 这一轮把五套形态**全部按新基数各跑绿过一遍**（跑次、每档的红点与成因、
以及链路 9 当场抓到的那处真缺陷，见 `docs/superpowers/evidence/9-identity-five-form-runs.md`）；
2026-09-28 这一轮补掉它留的两格：**2 副本档按 114 复跑**（含跨副本限领与 jobs 持锁分布，
见 `docs/superpowers/evidence/9-two-replica-and-capacity.md`）与 **`load-probe.sh` 容量重新取数**
（净排空 106 msg/s，登录口 BCrypt 与业务同 JVM 之后 LITE 的异步口径没有塌）。

| 形态 | 最近一次 | 通道证据 | ④ 读数 / ⑥ 界面证据 |
|---|---|---|---|
| LITE 服役档（容器） | **113/113**（2026-09-25，九条链路全集；账号体系与 ⑥ dist 都在镜像里） | Redis Stream 键 + XDEL 生效（跑完 `mkt:audit:pending` 与业务 topic 的 XLEN 恒 0）；`local_message` 零在途、键形 `grant:<requestId>` 两侧一致；`ownForm=LITE` 且跑完 `admin_config` 归零；重预热走**本进程同步**分支；standalone 常驻 517-599 MiB/768MiB（本轮跑完 550.4） | `mode=local+proxy`：`self` 走 local、`marketing-gateway` 走 proxy（网关在这一档仍是独立进程）；Stream 六条（两个业务 topic + 审计 + 三个 reheat type）全 `applicable=true`；恒等式基线 0。**⑥ 的真浏览器旅程就在这档**（`evidence/6-browser-journey.md`）：十页逐屏渲染核对 + 一次真写（秒杀总库存改 5010 → 直读 Redis 求和 4725==5010-285，分桶真的重建了），并抓到"点大盘整页空白"那个只有真人点才看见的 bug |
| dev 开发档（本机 2 JVM） | **113/113**（2026-09-25，九条链路全集，⑥ dist 由 `start-dev.sh` 现场 `mvn package` 打进 standalone） | 同上；`ownForm=DEV`；**这一档第一次跑是红的**：`start-dev.sh` 没发 `ACCOUNT_HOST/ACCOUNT_PORT`，网关把 `/api/auth/login` 打到没人听的 127.0.0.1:8087 → 500，与当年漏 `ADMIN_HOST` 同形；补上两行后全绿。（`reset-demo-data.sh` 在这一档的实测是 **09-24 那趟**的记录，本轮没重跑） | 同上（`mode=local+proxy`、判活里 `marketing-standalone=true`、四个业务进程名 `null`=本档不适用）；standalone 本机 RSS 175 MiB 是**上一轮（89 条那趟）的读数，本轮复跑没重测**。**⑥ 在这一档验的是"同一份 dist 换一种装配"**：`/ui/` 由 standalone 里的 admin 模块发出，六条链路 8 断言全绿（含 `no-store`、深链回退、缺文件 404） |
| FULL · 本机进程形态 | **114/114**（2026-09-25，九条链路全集；这一档的第七个 JVM 就是 `marketing-account:8087`，`start-all.sh` 的 `port_of` 补上之前它根本起不来——健康检查拿空端口 curl，等满 150s 再假报"未就绪"） | `local_message` 全 CONFIRMED、broker 消费组积压 0、Stream 键为 0；`ownForm=FULL` 且跑完 `admin_config` 归零；重预热走**跨进程投递 + 回执**分支（这就是比 LITE 多的那一条断言）；裸头直连 `:8082` 被拒而带签名 token 放行（这一档端口真的绑 `*`，是最需要这条的形态）。链路 9 的三条身份语义在这一档同样全绿
| FULL · 本机进程 · 每服务一库隔离档 | **114/114**（2026-09-25，九条链路全集；`marketing_account` 是这一档的第 6 个库，`init/01-schema.sql` 补了建库与授权，现存卷走 `docker/mysql/migrate/2026-09-25-account-db.sql`） | 数据按服务落 6 个库；后台会话/审计写进 `marketing_admin`，消费者账号/会话/身份事件写进 `marketing_account`，与单库 `marketing` 里那套互不串。**这一档第一次跑红了 6 条**：不是账号体系坏了，是两套布局的 `userId` 撞号 + 共用同一个 Redis（详见第六节 已知噪音 ⑨），处置在冒烟侧。**这一档跑冒烟要 `MYSQL_DB=marketing_activity ./scripts/smoke-test.sh`**：链路 5 的灰度两条直连 DB 改列，不指过去就改到另一套布局的表上，服务读不到 → 那两条判据红（不会假绿）| 跨库发现唯一被真正用到的一档：`backlog.schemas` 一次列出 5 个库并逐库独立（缺表的库不进列表、某库查询失败只让那一行 `-1`）。**④ 在这里抓到了真漂移**：面板开局报 budget=1/coupon=1/seckill=1，那是上一套布局留在 Redis 里的缓存对不上本布局的 DB——`information_schema` 那段 SQL 存在的意义就是让这种偏差看得见 |
| FULL · 容器化 1 副本 | **114/114**（2026-09-25，九条链路全集，镜像含账号体系 + ⑥ + H5 产物。第一次跑红 1 条是 已知噪音 ⑩ 那件换布局未重预热的事，`reset-demo-data.sh` 之后复跑全绿） | 同上 + 后台走 `lb://marketing-admin` 服务发现；重预热回执由 owning 容器写回；链路 6 的直连探测走 `docker exec` 进容器这条路（C 端那条对偶断言在这一档正是这么打的）。**⑥ 在这一档只这一格能验**：`ui-route` 的 `lb://` 那条、以及重预热页的类型清单（admin 自己 `/cache/types` 实测返回 `[]`，靠 ④ 面板并集才有得选）。上一格（09-24，96/96）的读数与证据仍在 `docs/superpowers/evidence/6-reheat-full-container.md`，含消费组 `owning-exec` 2 consumers / pending 0 | `mode=proxy`，**target 从六个变成七个**：`marketing-account` 进了可抓清单且 `source=proxy status=OK`（这一档的清单由 `OPS_T_*` 覆写，8087 也补进了 `OpsTargets` 的端口正面清单）。**#82 在这一档当场复现**：`liveness` 里 activity/coupon/admin 报 `false` 而 `docker ps` 说它们在跑（判活读的是 ⑤ 的 schema 自述键，无可改参数的进程不写自述）；账号进程**故意不在**判活清单里——宁可这一格读数缺席，也不新增一条错读数 |
| FULL · 容器化 2 副本（seckill + coupon） | **114/114**（2026-09-28 01:51，按新基数复跑） | 第三节的多副本三条证据 | 跨副本限领**实测只生效一次**（新账号 24 发并发打两个 coupon 副本：受理 1、41000 23、落库 1）；`jobs` 出现**两个不同持锁者**，三个任务各只有一个 holder。`liveness` 里 activity/coupon/admin 报 `false` = 已知 #82 复现，非新缺陷。**仍未跑**：admin 也起两副本时 `ui-route` 落到哪一个实例、界面写动作的审计归属（本轮只 scale 了 seckill+coupon，admin 单副本，这一条给不出答案） |

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
├── pom.xml                     # 父 POM（版本矩阵统一管理；两份前端不进 reactor）
├── marketing-common/           # 幂等/本地消息/Lua/Result/异常 + 两套签名 token（自动装配）
├── marketing-open-api/         # 风控/分销/ROI 领域接口 + 占位实现
├── marketing-gateway/          # 8090 路由 / C 端与后台两套鉴权 / 限流
├── marketing-activity/         # 8081 状态机/预算/灰度
├── marketing-coupon/           # 8082 券中心
├── marketing-discount/         # 8083 优惠计算引擎
├── marketing-seckill/          # 8084 秒杀中心
├── marketing-account/          # 8087 消费者账号：注册/登录/刷新/登出/改密/会话/身份事件
├── marketing-admin/            # 8086 后台：账号/会话/审计/运维入口 + 两份静态产物 /ui /h5
├── marketing-standalone/       # 8085 LITE 与 dev 的聚合进程（四业务 + 后台 + 账号同 JVM）
├── marketing-admin-ui/         # ⑥ 后台 SPA 源码（Vue3 + vite，不进 maven 生命周期）
├── marketing-h5-ui/            # C 端 H5 源码（同一套产物纪律：dist 入仓到 static/h5）
├── docker/
│   ├── docker-compose.data.yml     # 常驻数据层（mysql + redis AOF），三套形态共用
│   ├── docker-compose.preview.yml  # LITE 服役档全栈（standalone + gateway 两容器）
│   ├── docker-compose.prod.yml     # FULL 中间件：RocketMQ/Nacos/Prometheus
│   ├── docker-compose.full-app.yml # FULL 应用侧：一容器一服务，可 --scale
│   ├── mysql/init-lite/            # 单库 DDL + 种子（数据层默认，自动执行）
│   ├── mysql/init/                 # 六库布局（配合 MYSQL_DB_PER_SERVICE=1）
│   ├── mysql/migrate/              # 现存卷的增量迁移（有守卫，重复执行 no-op）
│   ├── rocketmq/                   # 两套 broker conf：进程形态广播 127.0.0.1，容器形态广播 host.docker.internal
│   └── prometheus/prometheus.yml
└── scripts/
    ├── common.sh               # 公共前置：JDK 17 锁定 + 健康等待 + 端口归属自检
    ├── start-dev.sh / stop-dev.sh             # dev 开发档（2 个本机 JVM）
    ├── deploy-preview.sh / stop-preview.sh    # LITE 服役档（全栈容器）
    ├── start-all.sh / stop-all.sh             # FULL 扩容档·本机进程（7 个 JVM，含 account）
    ├── deploy-full.sh                         # FULL 扩容档·容器化（可 --scale 多副本）
    ├── smoke-test.sh                          # 九条链路端到端冒烟（三套形态通用）
    ├── load-probe.sh                          # 容量夹具：按业务落库数算端到端，不看队列长度
    ├── reset-demo-data.sh                     # 演示容量复位
    └── build-ui.sh / check-ui-dist.sh         # ⑥ 后台产物的唯一重建入口 + jar/仓库 sha256 对拍
        build-h5.sh / check-h5-dist.sh         # C 端 H5 同一条纪律的两个脚本
```

种子数据：活动 `ACT2026001`、券模板 `CT2026001`(5元无门槛)/`CT2026002`(满100减20)、
秒杀 `SK2026001`(200 件/16 桶)、规则 `PR2026001~003`(满减/折扣/阶梯)。
