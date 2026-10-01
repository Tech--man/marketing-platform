# 部署与形态（形态 × 入口 × 数据层 × 容量）

> 本文拆自 README 的对应章节（2026-10-01 拆分）。文中"见第 X 节"的互引仍按原 README 编号：一/二节与三节速览在 README；三节详解在 docs/deployment.md；四节在 docs/core-flows.md；五节在 docs/api.md；六节在 docs/testing.md；七/八节在 docs/structure.md。

## 形态 × 入口 × profile：三个词别混

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
| 8091 | 网关 actuator（health/prometheus），容器形态仅绑回环 | 网关独立管理端口（2026-09-29 起） |
| 8085 | 聚合服务（调试直连；LITE 容器**仅绑回环**，见下） | 形态侧：dev 本机进程、LITE 容器 |
| 8086 | 管理后台（仅 FULL 本机进程形态直连需要；容器形态不发布端口） | 形态侧：FULL |
| 9876 / 10911 / 8848 / 9091 | RocketMQ / Nacos / Prometheus | 仅 FULL 形态 |
  （2026-09-29 第四批起，上表的 FULL 中间件宿主机发布全部改绑 127.0.0.1、四个容器补
  restart: unless-stopped——nacos 无鉴权全网卡开放等于任何人可接管 lb:// 路由；
  彻底方案 nacos 鉴权 + broker ACL 需要客户端凭证下发，另行跟进）

**后台相关的两个环境变量**（三套形态都要给，且 gateway 与签发方必须同值）：

| 变量 | 作用 | 缺配的后果 |
|---|---|---|
| `ADMIN_JWT_SECRET` | 后台 token 的 HS256 密钥 | 空值时 admin 侧启动即失败（宁可不签，也不签一枚谁都能伪造的 admin token）。LITE 由 `deploy-preview.sh` 随机生成并落在 `.admin-jwt-secret`（0600、已 gitignore）复用；FULL 由 `deploy-full.sh` 在入口显式拦。dev/start-all 用 `dev-only-secret-change-me` 占位并打 WARN |
| `DEPLOY_FORM` | 空（=只认 `GLOBAL` 覆盖） | 形态标识 `LITE`/`FULL`/`DEV`：在线配置按它选生效值（`form 行 > GLOBAL 行 > 出厂值`）。**不部署它就等于没有这套机制**；取值拼错也退回 GLOBAL 并告警——把 FULL 的阈值套到形态名拼错的进程上是本仓库最贵的一类错 |
| `CONFIG_POLL_SECONDS` | `5` | 各进程比对配置版本的节拍，即"改完阈值到全档生效"的延迟上限 |
| `GRAY_REFRESH_SECONDS` | `5` | 灰度规则回源 DB 的节拍（灰度真值在 `activity.gray_percent`，不依赖 Redis） |
| `RL_ADMIN` | 后台路由的限流阈值（默认 50/s） | 路由 id 不在限流 map 里＝完全不限流；后台登录口的 BCrypt 单次 50-100ms，几十 QPS 就能把与 C 端同进程的后台打满 |

> **第六批落地（2026-09-29，外部决策项按建议执行）**：
> - nacos 鉴权/broker ACL：**单机维持现状**（端口已绑回环是合理信任边界），触发条件
>   写进 prod.yml 头注释——FULL 迁多机时一并启用，凭证照抄 `.admin-jwt-secret` 模式；
> - PII：演示期明文维持，**第一个真实手机号接入前**必须切"SHA-256 索引 + AES-GCM 密文列"；
> - 灰度适用秒杀：**不复用营销活动灰度**（两域运营节奏不同步），确需则给 seckill_activity
>   加独立列；
> - admin_audit_log：**默认不自动删除**（写入是人工量级，增长慢），需要时归档冷表而非 DELETE。
> 同时落地的工程件：Boot **3.4.7**（Tomcat 10.1.42、Spring 6.2.8、micrometer 1.14
> ——registry 包名换 prometheusmetrics 已适配）、网关 **pre-auth 粗桶**（-150，
> 全局 /api/** 每 IP，出厂 2000/s 待 load-probe 校准）、**traceId**（网关生成透传 +
> MDC + 日志 pattern 印 `%X{traceId}`）、**迁移台账** `scripts/migrate.sh`（幂等重放
> 全部存量迁移）、PrometheusRule 告警初稿（`docker/prometheus/alerts.yml`）、
> 秒杀借桶**环形起点**、活动详情**公开 VO**、HTTP 401/403/429 映射、终态归档
> （CONFIRMED/SUCCESS 超 30 天删）、Redis **3s 命令超时**、规则值域防呆。

> **网关 Redis 降级（2026-09-29 第三批，根因 C 收口）**：限流计数不可用时 fail-open
> 放行并计 `marketing.gateway.rate.limit.degraded`（洪流保护自己的可用性不能比后端低）；
> C 端/后台吊销位与 bump 查询不可用时退化为"仅验签放行 + 告警"，代价与 Redis 被清空
> 相同（已声明边界：已吊销 token 可用到自然过期，上界 accessTtl）；`RL_*=0` 经
> env/yml 设入时钳制为 1 并告警（0 在 Lua 里恒真 = 整条入口全拒，在线路径本有
> min=1 校验，env 路径原先没有）。

> **上游超时（2026-09-29 第四批）**：网关 httpclient 补 connect-timeout 2s /
> response-timeout 10s（`GW_CONNECT_TIMEOUT`/`GW_RESPONSE_TIMEOUT` 可调）——SCG 默认
> response 超时是无限，任何上游 hang 住都会无限期占用客户端连接与事件循环。

> **XFF 治理（2026-09-29 起）**：网关是最外层入口，请求里出现的 `X-Forwarded-For` 只可能是
> 客户端自报——限流键因此只取 TCP 对端地址（自报 XFF 刷不出新桶），且网关对下游**覆写** XFF
> （`x-forwarded.for-append: false`）后才转发，下游 `ClientIp` 拿到的恒为网关写入的单值；
> `X-Real-IP` 从采信链移除（它没有合法写入方）。审计 ip 列与登录限速由此不可被一个请求头
> 投毒。将来前面真加一层可信 LB 时，在那层写 XFF 并按固定跳数取段，而不是恢复盲信首段。

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
