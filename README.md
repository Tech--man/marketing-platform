# 营销管理平台脚手架（marketing-platform）

面向 **日常 QPS 万级 / 大促 10 万+ / 秒杀 50 万+** 场景的可运行微服务脚手架（该量级是 **FULL 档的设计口径**，LITE 档当前实测容量见 [docs/deployment.md](docs/deployment.md)「容量现状」）。
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
| **LITE 服役档**<br>（preview） | 非活跃期 7×24 真跑流量，小机器常态承载 | 全栈容器化 5 容器：mysql / redis / standalone（四业务模块 + 后台 + 账号聚合）/ gateway / web（nginx 前端） | Redis Stream | 单库 `marketing` | Redis **AOF everysec + noeviction**、MySQL flush=1、restart 策略、日志轮转 |
| **FULL 扩容档**<br>（prod） | 活跃期承接洪流，与生产同构 | 两种交付形态二选一：**本机进程** 7 JVM + web 容器（8081-8084、8086、8087、8090 + nginx）／**容器化** 一容器一服务且可 `--scale` 多副本（网关 + web，后台与账号服务不对外直连）；配 RocketMQ + Nacos + Prometheus | RocketMQ | 默认同一单库（可选每服务一库，六个库：四业务 + `marketing_admin` + `marketing_account`） | 中间件默认全持久化 |
| **dev 开发档** | 本机改代码，允许丢数据 | 中间件容器 + 前端 web 容器（127.0.0.1:8088）+ 本机 2 JVM（standalone 8085 / gateway 8090） | Redis Stream | 单库 `marketing` | 不持久化；**淘汰策略仍与 LITE 一致** |

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

#### 发布顺序：先迁移，后起应用（四个入口已内建）

四个部署入口（`deploy-preview` / `deploy-full` / `start-dev` / `start-all`）都在数据层
`--wait` 之后、应用进程之前串了 `ALL_DBS=1 ./scripts/migrate.sh`——迁移失败即中止部署，
不会把新 jar 起在未迁移卷上。手动发布/替换 jar 时必须保持同一顺序：

1. 数据层就绪（`docker-compose.data.yml up -d --wait`）；
2. `ALL_DBS=1 ./scripts/migrate.sh`（幂等：台账按文件名跳过已应用项；四库隔离档自动
   覆盖 `marketing` 与全部 `marketing_*` 库）；
3. 起应用（容器或进程形态任一）。

跳过第 2 步的兜底是进程侧 `SchemaMigrationGuard`：声明了迁移列依赖的服务
（coupon 的 `idempotent_record.claim_token`、seckill 的 `seckill_order.active`、
admin 的 `admin_audit_log.source_id`、standalone 兼两者）在缺列卷上启动即失败，报错
直接给出上述命令——但那是"起不来"，不是"迁移完成"，别把兜底当流程。（2026-10-01
审计 P1-1；复审 N-2 之前 `start-all.sh` 是四个入口里唯一没串迁移的一个。）

**灰度键形状升级的滚动顺序（2026-10-01 复审 §5.3）**：灰度键值形状自第九批起为
`percent|w1,w2|version`（三段，尾部是版本号）。滚动发布同库混跑两种版本的窗口内，
顺序必须是**先升消费侧（coupon），再升发布侧（activity）**——旧 coupon 读侧按
"第一个 `|` 之后整串"解析白名单，读到新三段值会把最后一项（如 `70002|7`）当坏项
跳过，白名单尾号在那段时间静默失效；反向（新读旧写）无此问题，新旧读侧都兼容
两段旧形状。FULL 多副本形态下"先升完所有 coupon 副本、再动 activity"同理。

**速记**：MySQL `marketing / marketing123`（宿主机 **3307**，避开本地 mysqld 的 3306）；Redis 宿主机 6380；
C 端种子账号 `demo / demo123456`；后台账号见 `docs/testing.md` 冒烟段。构建必须 **JDK 17**
（`scripts/common.sh` 自动锁定；更高版本上 Lombok 注解处理必挂）。

> 三节的其余内容已拆至 **[docs/deployment.md](docs/deployment.md)**：形态 × 入口 × profile 三层辨析、
> 数据层与端口矩阵、环境变量（`ADMIN_JWT_SECRET` / `DEPLOY_FORM` / `RL_*`）、原地双向切换实测、
> 内存实测、容量现状与压测读数（别把 LITE 当洪峰档）、LITE ≠ FULL 差异表。

## 四、七条核心链路（概览）

| # | 链路 | 一句话 |
|---|---|---|
| 1 | 领券 | 削峰 + 最终一致：Lua 原子预扣（库存 × 单人限领）→ MQ 异步落库，三层幂等防重 |
| 2 | 优惠计算 | 规则 DSL + 位图倒排索引，P99 < 20ms；引擎超时降级按原价返回并计数 |
| 3 | 秒杀 | 16 桶分片 Lua 抢购 + MQ 异步下单；50 万 QPS 是 FULL 档设计口径 |
| 4 | 管理后台 | 两套凭证不互通、行级乐观锁（冲突 41008）、管理写全量审计、运维入口 |
| 5 | 在线配置 | 改阈值与灰度不重启：`form 行 > GLOBAL 行 > 出厂值`，5s 内全档收敛 |
| 6 | 运维读数 | 不接 Prometheus 的只读大盘（代理抓各服务 actuator，聚合一致性/账实恒等式） |
| 7 | 后台界面 | 同源挂在网关 `/ui/`（前后端分离后由 marketing-web nginx 容器承载） |
| · | 消费者账号体系 | C 端身份唯一来源：注册/登录/refresh 轮换/登出/改密全会话吊销 + 身份事件流水 |

**"写入口收口"是横切边界**——管理面写（`/api/admin/**`，admin token + 审计 + 乐观锁）与
C 端交易写（幂等键 + 削峰，不进审计）的结构分界见第五节写入口矩阵。
逐链路的机制、取舍与实测：**[docs/core-flows.md](docs/core-flows.md)**。

## 五、API 速查（要点）

网关 8090 是**唯一对外入口**：C 端走 `Authorization: Bearer <accessToken>`（`POST /api/auth/login`
换取，40101 静默刷新一次）；后台 `/api/admin/**` 走 admin token（角色 admin/operator，缺失整片拒）。
仓库**零 CORS**——前端同源经网关 `/ui/`、`/h5/` 路由。完整端点表、写入口矩阵
（谁在听这个写、要什么角色、留不留痕）与 HTTP 状态码契约（40100/40101/40102→401、
40300→403、42900→429，body.code 仍是对前端唯一契约）：**[docs/api.md](docs/api.md)**。

## 六、测试与验证

```bash
mvn test                 # 549 个单测 / 102 个类：见下
cd marketing-admin-ui && npm ci && npm test && npm run build   # 49 条前端用例 + 构建闸（CI 同款）
cd marketing-h5-ui && npm ci && npm test && npm run build      # 101 条 C 端用例 + 构建闸
./scripts/smoke-test.sh  # 端到端 119 条断言 / 九条链路，需服务已启动
                         # （LITE 与 dev 各实测 113/113，2026-09-25；FULL 两种形态见第六节矩阵）
./scripts/reset-demo-data.sh [总库存]  # 演示容量复位（默认 5000；③ 起走后台端点，不再 restart 应用）
```

**前后端分离（2026-10-01）**：前端产物不再入仓、不进后端 jar——界面由
**marketing-web**（nginx 容器）在镜像内构建承载，`deploy-preview.sh` 等入口
自动带起；构建可破坏性由 CI 的 `npm run build` 步骤与 web 镜像构建天然覆盖。

**单测（549 用例 / 102 类，2026-10-01 前后端分离后）**分十族：


单测十族构成（业务语义/资金与消息正确性/公共契约/后台鉴权与审计/…）、冒烟九链路 ×
五形态通过矩阵、已知噪音九条、变异检查纪律与发布前门禁清单：
**[docs/testing.md](docs/testing.md)**。

## 七、扩展点 · 八、目录结构

扩展点（风控/分销/ROI 占位 → 生产替换路径，`AllowAllRiskCheckService` → 风控中心 RPC、
本地消息表 → Seata AT 等）与完整目录树：**[docs/structure.md](docs/structure.md)**。

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/deployment.md](docs/deployment.md) | 形态 × 入口 × profile、端口矩阵、环境变量、双向切换、内存与容量实测 |
| [docs/core-flows.md](docs/core-flows.md) | 七条核心链路 + 消费者账号体系的机制与实测 |
| [docs/api.md](docs/api.md) | API 端点速查、写入口矩阵、HTTP 状态码契约 |
| [docs/testing.md](docs/testing.md) | 单测构成、冒烟矩阵、已知噪音、测试纪律 |
| [docs/structure.md](docs/structure.md) | 扩展点与目录结构 |
| [docs/landing/](docs/landing/) | 项目落地页（纯静态，可直接浏览器打开） |
| `marketing-web/` | 前端静态承载容器（前后端分离后的新模块，不进 Maven reactor） |
