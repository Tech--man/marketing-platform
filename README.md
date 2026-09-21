# 营销管理平台脚手架（marketing-platform）

面向 **日常 QPS 万级 / 大促 10 万+ / 秒杀 50 万+** 场景的可运行微服务脚手架。
架构原则：**分层限流、规则可编排、活动可灰度、全链路可降级、最终一致、ROI 可实时度量**。

- 券中心 / 优惠计算引擎 / 秒杀中心 **深度实现**
- 活动中心（状态机 + 预算 + 灰度）/ 风控 / 分销 / ROI **基础能力或扩展点占位**
- 标准拓扑：Gateway + Nacos + 4 业务服务；Seata / Flink / ClickHouse / ES / XXL-Job 留扩展点

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

## 三、快速开始

```bash
# 1. 启动中间件（MySQL 自动执行 docker/mysql/init/01-schema.sql 建 4 库 + 种子数据）
cd docker && docker compose up -d && cd ..

# 2. 构建并启动 5 个服务（日志 logs/，pid run/）
./scripts/start-all.sh

# 3. 三条链路冒烟（领券 / 优惠计算 / 秒杀 + 60 用户并发防超卖）
./scripts/smoke-test.sh

# 4. 停止服务 / 中间件
./scripts/stop-all.sh
cd docker && docker compose down          # 加 -v 清空数据卷

# 监控：Prometheus http://localhost:9091（已配置抓取 5 个服务的 /actuator/prometheus）
# 注册中心（可选）：http://localhost:8848/nacos，服务加 --spring.profiles.active=nacos 开启注册与 lb 路由
```

数据库账号 `marketing / marketing123`（宿主机端口 **3307**，避开本地 mysqld 占用的 3306）；网关演示 Token `demo-token-123`（环境变量 `GATEWAY_TOKEN` 覆盖）。

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
| POST | /api/activity/{no}/budget/deduct · GET /budget/remain | 预算扣减（bizKey 幂等）/ 实时余额 |
| POST | /api/coupon/grant · /consume | 领券受理 · 核销 |
| GET | /api/coupon/grant/result/{requestId} · /usable?userId= · /stock/{templateNo} | 轮询 / 可用券 / 模板余量 |
| POST | /api/discount/calculate · /rules | 优惠计算 · 规则 upsert（触发快照刷新） |
| POST | /api/seckill/grab · /pay/{orderNo} | 抢购 · 模拟支付回调 |
| GET | /api/seckill/grab/result/{token} · /activities · /stock/{activityNo} | 轮询 / 活动列表 / 分桶余量 |

## 六、测试与验证

```bash
mvn test    # 幂等执行器(H2) / 状态机 / 分摊尾差与行级cap / 组合选择枚举 / 分桶规划 / 引擎基准
./scripts/smoke-test.sh   # 端到端三链路 + 并发防超卖断言（需中间件与服务已启动）
```

已覆盖单测：三层幂等语义、非法状态流转拒绝、比例分摊尾差归末项、末行占满顺延、
互斥组最优（priority desc → discount desc）、叠加超限精确枚举、1 万规则基准耗时。

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
│   ├── docker-compose.yml      # MySQL/Redis/RocketMQ/Nacos/Prometheus
│   ├── mysql/init/01-schema.sql# 4 库 DDL + 种子数据（自动执行）
│   └── prometheus/prometheus.yml
└── scripts/                    # start-all / stop-all / smoke-test
```

种子数据：活动 `ACT2026001`、券模板 `CT2026001`(5元无门槛)/`CT2026002`(满100减20)、
秒杀 `SK2026001`(200 件/16 桶)、规则 `PR2026001~003`(满减/折扣/阶梯)。
