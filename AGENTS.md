# AGENTS.md — ZCode 工作区指令（marketing-platform）

营销管理平台微服务脚手架：Java 17 · Spring Boot 3.2.5 · Spring Cloud 2023 · MyBatis-Plus · Redis(Lua) · RocketMQ 5，外加两个 Vue3/Vite 前端。
**根 README.md（约 94KB，中文）是权威文档**，改敏感区域前先读对应节：第三节（形态与环境/快速开始）、第五节（API 速查 + 写入口矩阵）、第六节（测试与验证/已知噪音）、第八节（目录结构）。

## 模块与端口

| 模块 | 端口 | 职责 |
|---|---|---|
| marketing-common | - | Result/异常、幂等、本地消息表、Lua 工具、两套 JWT（自动装配） |
| marketing-open-api | - | 风控/分销/ROI 接口占位 |
| marketing-gateway | 8090 | 路由、两套凭证鉴权、Redis+Lua 限流（唯一对外入口） |
| marketing-activity | 8081 | 活动状态机/预算预扣/灰度 |
| marketing-coupon | 8082 | 券模板/领券/核销 |
| marketing-discount | 8083 | 规则 DSL/位图索引/优惠计算 |
| marketing-seckill | 8084 | 分桶 Lua 抢购/MQ 异步下单 |
| marketing-standalone | 8085 | DEV/LITE 聚合进程（四业务 + admin + account 单 JVM，Redis Stream 代 MQ） |
| marketing-admin | 8086 | 后台账号/会话/审计/运维 + 两份静态产物 `/ui` `/h5` |
| marketing-account | 8087 | C 端消费者账号 |
| marketing-admin-ui / marketing-h5-ui | 5173/5174 (dev) | Vue3 前端源码，**不进 Maven reactor** |

## 常用命令

```bash
mvn test                              # 后端全量单测（H2 MODE=MySQL 跑真 SQL，不需要中间件）
mvn test -pl marketing-coupon         # 聚焦单模块
cd marketing-admin-ui && npm test     # 后台前端（vitest + jsdom）
cd marketing-h5-ui && npm test        # C 端 H5 前端
./scripts/build-ui.sh                 # 后台前端唯一重建入口（SKIP_INSTALL=1 跳过 npm install）
./scripts/build-h5.sh                 # H5 唯一重建入口
./scripts/check-ui-dist.sh            # jar 与入仓产物 sha256 对拍（前提：已 mvn package）
./scripts/smoke-test.sh               # 端到端验收，需服务已启动
./scripts/start-dev.sh                # DEV：中间件容器 + 本机 2 JVM
./scripts/deploy-preview.sh           # LITE：全栈容器（默认推荐）
./scripts/start-all.sh                # FULL：本机 7 JVM（PROFILES=nacos 开注册发现）
./scripts/deploy-full.sh              # FULL：容器化可 --scale
./scripts/reset-demo-data.sh          # 演示库存复位；load-probe.sh 吞吐测量
```

需要中间件环境的测试打 `@Tag("integration")`，surefire 默认排除——别在单测里起真 Redis/MySQL。

## 架构边界（改代码前必读）

- **形态三层别混**：`DEPLOY_FORM`（`DEV`/`LITE`/`FULL`）是形态唯一定义处，由各入口脚本写死；Spring profile **只有 `nacos`**，不存在 dev/preview/prod profile。同一份业务代码在聚合（standalone + Redis Stream）与分进程（+ RocketMQ）间原地切换，切形态不改代码。
- **写入口矩阵**（README 五节）：管理面写 `/api/admin/**`（admin token、进审计、行级乐观锁 `version`，冲突回 41008）与 C 端交易写（幂等键 + 削峰、不进审计）是结构分界。C 端旧写路径已删且不留转发别名——40400 是收口的唯一可测证据，别加回别名。
- **两套凭证不互通**：C 端 `ConsumerAuthFilter`（Bearer accessToken）与后台 `AdminAuthFilter`，密钥分别是根目录 `.consumer-jwt-secret` / `.admin-jwt-secret`（gitignore，缺失时对应整片拒）。
- **审计落表只在 admin 进程**：业务进程把 `AuditPayload` 投 Redis Stream `mkt:audit:pending`，admin 每 5s drain 落 `admin_audit_log`；业务模块不连这张表。
- **零 CORS**：前端同源经网关 `/ui/`、`/h5/` 路由与 vite dev proxy，仓库没有任何跨域配置——不要加。
- MyBatis-Plus **刻意不配逻辑删除**（表无 deleted 列），上下线语义一律走各自 status 字段。
- Nacos 默认关闭（local 静态路由）；`nacos` profile 才开注册发现与配置中心。

## 硬性纪律 / 已知坑

- **JDK 17**：`scripts/common.sh` 会锁 `JAVA_HOME`；更新 JDK（26）上 Lombok 注解处理必挂（满屏 `cannot find symbol: log`，增量编译还会假装成功）。
- **前端产物入仓**：`marketing-admin/.../static/ui/**` 与 `static/h5/**` 是**故意提交**的构建产物（clone 即可跑出带界面的 jar）。改了 `.vue` 不跑 `build-ui.sh`/`build-h5.sh` = jar 里还是旧界面；`check-*-dist.sh` 与 `UiDistIntegrityTest` 是两道闸。构建脚本还会注入 git 指纹并清 `target/classes/static` 里的旧产物。
- **Redis 必须 `noeviction`**：`allkeys-lru` 会静默丢库存/券预扣/幂等键 → 超卖与重复领券（LITE 四条不可退让之一，README 三节）。
- dev 默认 MySQL 宿主端口是 **3307**（不是 3306）；宿主常驻 redis/mysqld 会遮蔽容器发布端口，`common.sh` 的 `assert_port_not_shadowed` 启动期拦截。
- **测试纪律**：单测用 H2 跑真 SQL；关键断言要求变异检查（把实现改回旧值，断言必须红）——README 六节有整套口径，新增关键用例照此办。
- 网关启动固定打一条 `Unable to load ...MacOSDnsServerAddressStreamProvider` ERROR：macOS netty 可选库缺失，无害，**不要**为它加平台特定依赖。其余已知噪音见 README 六节（编号不连续，当前为 ①②③④⑥⑦⑧⑨⑩ 共 9 条）。
- 代码内注释承载大量"为什么这么做"的决策依据（取舍、地雷、机制实测），改动前先读周边注释，别把防护性代码当冗余删掉。
- **C 端桌面 IA 不用左侧栏**：左侧栏是后台/工具的形态（`marketing-admin-ui` 的 `AppLayout` 就是它）。C 端桌面 = 顶部横向文字导航 + 全宽内容 + 多列栅格 + 主从分栏。判据很硬：如果新导航和后台共用 `.shell`/`.side`/`--sidebar-w`/`is-active` 那套骨架，就是把控制台壳子扣到了商店上。`boot.test.js` 里有两条断言钉这件事（导航必须是 `HEADER` 而非 `aside`；`wallet` 必须有全局入口）。

## 文档与约定

- `docs/superpowers/specs/` 与 `docs/superpowers/plans/`：各工作流（③ 业务管理面 / ④ 运维读数 / ⑤ 在线配置 / ⑥ 后台 UI）的设计文档与实施计划，改对应子系统前先读；⑨ 消费者账号体系没有 spec，设计与实测结论在 `docs/superpowers/evidence/9-*.md`；`docs/PROJECT-STATUS-2026-09-24.md` 是状态快照。
- 文档、代码注释、commit message 均用中文；commit 前缀 `type(scope): ⑨/⑥/③/④/⑤ …`（工作流编号见 README），参考 `git log`。
- 种子数据：活动 `ACT2026001`、券模板 `CT2026001/02`、秒杀 `SK2026001`、规则 `PR2026001~003`。
