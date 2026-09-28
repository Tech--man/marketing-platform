# 项目状态 · marketing-platform

**基准**：`main` @ `924e48b`（2026-09-24 18:42 +0800），工作区干净，117 个提交，全部落在单一 `main` 分支上（09-21 起，4 天）。
**本文的"已验证"**：第 4 节是本次会话（09-24 19:36 前后）实跑的命令与读数；第 5 节是转录自 README 覆盖矩阵的历史实测记录，本轮**没有**重跑冒烟。文末"证据分层"逐条标了来源。

> **时效：这是一份冻结在 09-24 的时点快照，不是现状。** 之后落地的是 commit 前缀记作 **⑨
> 的消费者账号体系**段：C 端身份从共享 demo token 换成真登录态、交易请求体不再接受自报
> `userId`、新增 `marketing-account` 模块与 `marketing-h5-ui` C 端界面。
> 因此本文这些数字已经过期：断言基数 95/96 → **113/114**；"八条链路" → 九条；
> 第 10 节与第 7 节第 3 条说的"两格未按新断言复跑"已补齐（FULL 本机进程、每服务一库、
> 容器 1 副本、容器 2 副本、admin 2 副本各 114）；第 54 行"本轮未做"的 `load-probe`
> 也已在 09-28 重新取数（LITE 净排空 106 msg/s）。
> 看现状请读 README 与 `docs/superpowers/evidence/9-*.md`，不要从这里取数。
> 注意别把 README 里的 ⑦⑧⑨ 当成工作流编号——那是**已知噪音**的条目号，与 commit 前缀撞了。

---

## 1. 一句话状态

管理后台的六段路线（①② 地基与账号 → ⑤ 在线配置 → ③ 业务管理面 → ④ 运维只读 → ⑥ SPA）**全部交付**，`/ui/` 后台界面第一次成为可点的东西；当下所有自动化门禁实跑全绿。剩下的事集中在三类：**一处读数语义缺陷**（④ 把"没有可改参数"显示成"没在跑"）、**一处容量天花板**（LITE 入口每单 4 次 fsync）、**两格未按新断言复跑的形态**（FULL 本机进程档、容器化 2 副本档）。

## 2. 项目定位（决定一切取舍的那条）

同一份业务代码、同一份数据，按流量在两个容量档之间**原地双向切换**，切换只换应用侧进程形态与消息通道：

| 形态 | 定位 | 拓扑 | 消息通道 |
|---|---|---|---|
| **LITE 服役档** | 非活跃期 7×24 真跑流量，小机器常态承载（**不是演示档**） | 4 容器：mysql / redis / standalone（四业务 + 后台单 JVM）/ gateway | Redis Stream |
| **FULL 扩容档** | 活跃期承接洪流，与生产同构 | 本机 6 JVM 或一容器一服务 + `--scale` | RocketMQ |
| **dev 开发档** | 本机改代码，允许丢数据 | 中间件容器 + 2 个本机 JVM | Redis Stream |

省内存一律不靠牺牲可靠性换：Redis 必须 `noeviction`、状态落 AOF 且挂卷、容器要有 restart 与日志轮转、GC 用 G1。任何"两形态行为不等价"的地方按缺陷对待，而不是写进文档说明。

## 3. 代码与测试规模

| 模块 | 端口 | 职责 | Java | 测试类 |
|---|---|---|---|---|
| marketing-common | – | 幂等/本地消息表/Lua/Result/异常 | 81 文件 5,173 行 | 23 |
| marketing-open-api | – | 风控/分销/ROI 接口 + 占位实现 | 5 文件 105 行 | 0 |
| marketing-gateway | 8090 | 路由、Bearer 鉴权、Redis+Lua 限流、`ui-route` | 18 文件 1,771 行 | 9 |
| marketing-activity | 8081 | 状态机/预算/灰度 + ③⑤ 的 owning 写端点 | 27 文件 1,904 行 | 9 |
| marketing-coupon | 8082 | 券模板/预扣 Lua/领券轮询 | 28 文件 1,603 行 | 3 |
| marketing-discount | 8083 | 规则 DSL/位图索引/最优组合/降级 | 34 文件 2,262 行 | 8 |
| marketing-seckill | 8084 | 分桶预热/Lua 抢购/MQ 异步下单/回补 | 31 文件 1,962 行 | 5 |
| marketing-admin | 8086 | ①②③④⑤⑥ 的后台：账号/会话/审计/运维读数/在线配置/静态 `/ui/` | 79 文件 5,979 行 | 18 |
| marketing-standalone | 8085 | LITE / dev 的聚合进程 | 3 文件 143 行 | 1 |

合计 **Java 20,902 行 / 358 个 `@Test` / 76 个测试类**。
前端 `marketing-admin-ui/`（**不进 root pom**）：20 个源文件 2,485 行，11 个 view（10 页 + 登录），46 条 vitest 用例 / 9 个文件；产物入仓到 `marketing-admin/src/main/resources/static/ui/`（3 个文件 / 252 KiB 未压缩，gzip 后约 78 KiB）。
运维脚本 13 份 / 1,557 行 shell，其中 `smoke-test.sh` 689 行、八条链路、95（LITE/dev）/ 96（FULL）条断言。

## 4. 本次会话实跑的验证（当下证据）

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量单测 | `source scripts/common.sh && mvn test` | **BUILD SUCCESS，exit 0**，reactor 里九个模块全 SUCCESS，总耗时 17.1s（判据是退出码与 reactor 汇总，逐条用例计数未展开） |
| 前端单测 | `cd marketing-admin-ui && npm test` | **46 passed / 9 files**，exit 0（2.0s） |
| ⑥ 产物门禁 | `bash scripts/check-ui-dist.sh` | **exit 0** —— jar 与仓库 `static/ui` 逐项 sha256 一致（3 个文件） |
| 用例数核对 | 源码统计 | 358 个 `@Test` / 76 个 `*Test.java`、46 条 vitest —— 与 README 声称一致 |
| `/ui/` 在线 | `GET :8090/ui/`（无凭证） | **200**，`Cache-Control: no-store`，`Content-Security-Policy: script-src 'self'; object-src 'none'; base-uri 'self'` |
| 后台鉴权仍在位 | `GET :8090/api/admin/ops`（无凭证） | `{"code":40100,"message":"凭证无效"}` —— 加了界面不等于加了口子 |
| C 端读路径 | `GET :8090/api/coupon/stock/CT2026001` | `code:0`，余量 92,792 |

**本轮未做**：重跑 `smoke-test.sh`（需 3-5 分钟/档，且踩在部署节奏窗口上才更有意义）、`load-probe.sh` 容量探针、FULL 五形态复跑。

## 5. 端到端覆盖矩阵（转录 README，各格跑时不同）

断言集一直在长（③→+9、④→+8、⑥→+6），所以**低于当前基数的格子只代表"当时那一版全绿"，不等于已在新断言下复跑过**。

| 形态 | 最近一次 | 状态 |
|---|---|---|
| LITE 服役档（容器） | **95/95**（09-24，八条链路全集） | ✅ 含 ⑥ 的真浏览器旅程（十页逐屏 + 一次真写：库存 5000→5010，直读 Redis 求和 4725==5010-285，分桶真重建） |
| dev 开发档 | **95/95**（09-24） | ✅ 验的是"同一份 dist 换一种装配" |
| FULL · 容器化 1 副本 | **96/96**（09-24） | ✅ 唯一能验 `ui-route` 的 `lb://` 那条的一格；重预热 `DISPATCHED → DONE` 有证据文件 |
| FULL · 本机进程形态 | **90/90**（09-24） | ⚠️ 旧基数，⑥ 未在此档复跑（靠容器档 + dev 档两头夹住同一份 jar） |
| FULL · 本机进程 · 每服务一库隔离档 | **90/90**（09-24） | ⚠️ 旧基数；跑冒烟必须带 `MYSQL_DB=marketing_activity` |
| FULL · 容器化 2 副本（seckill + coupon） | 34/34 | ⚠️ 未按 95 复跑，⑥ 与 ④ 面板均未在多副本下验过 |

"原地换形态"那条关键证据（A→B 之间不做任何 SQL 清理连续跑通）来自第一轮那趟，后续轮次未复现该条件。

## 6. 此刻机器上跑着什么

LITE 服役档在跑：`mkt-preview-standalone`（8085）+ `mkt-preview-gateway`（8090），中间件 `mkt-mysql`（127.0.0.1:3307）、`mkt-redis`（127.0.0.1:6380）、`mkt-rocketmq-namesrv`/`broker`、`mkt-nacos`、`mkt-prometheus`（:9091）常驻。`run/` 为空 —— **没有本机 JVM 与容器栈并跑**，这是复跑任何形态前必须先确认的那件事（两套栈同时跑会造出假红）。

预览镜像 09-24 18:32 构建，索引页戳记 `build-ui: rev=1acedf0-dirty at=09:53:08Z`，即它含 ⑥ 最后一版 dist（`-dirty` 是因为构建发生在 `5f149d9` 提交之前，产物内容与仓库当前 dist 逐项一致，已由 `check-ui-dist.sh` 证实）。`.admin-jwt-secret` 在本地且被 `.gitignore` 忽略 —— 它是六个进程共享的验签密钥，缺配后台整片拒启。

## 7. 未修与待办（按"会不会咬人"排序）

1. **④/⑤ 判活语义混用**（09-24 由 ⑥ 界面第一次暴露，记为 TODO #82）。`OpsSnapshotService.liveness()` 用"⑤ 的 schema 自述键是否存在"判进程存活，而 ⑤ 在"该进程无可声明参数"时故意不写键。结果：FULL 下 activity/coupon/admin 三个**在跑的**进程被面板显示成"没在跑"。修法二选一——换每进程无条件写的心跳键，或把"没自述"与"没在跑"分开渲染（复用 `-1`/`null` 纪律）。这不是 ⑥ 的越段改动，界面只是忠实渲染。
2. **LITE 吞吐天花板 = 提交次数 × InnoDB redo fsync**。同步入口每单仍打 4 个 autocommit，实测入口 ~90-100 msg/s。合并到 1-2 个约值 1.5 倍，但会让幂等的 in-flight `PROCESSING` 记录失去可见性（并发重复请求看不到彼此），**不是纯性能改动**，需连同幂等语义一起评估。
3. **两格形态未按新断言复跑**（第 5 节的 ⚠️）。FULL 本机进程档缺 ⑥ 那一格；2 副本档既没按 95 复跑，也没验过多副本下的 `liveness`/持锁者分布与界面写动作的审计归属。
4. **仓库没有任何 CI**（`.github` 不存在）。⑥ 的三道产物闸里两道靠人跑（`build-ui.sh`、`check-ui-dist.sh`），只有 `UiDistIntegrityTest` 会随 `mvn test` 自动咬人。
5. **C 端配置类写路径硬切、无兼容期**（09-23 决定）。仓库内只有脚本与冒烟依赖它们；真出现外部集成方需单独开一段做兼容期。
6. **网关 `PrematureCloseException` 只是预防性修复**（连接池 `max-idle-time=30s` + `eviction-interval=10s`）。竞争窗口在这台机器上没能确定性复现，所以只能说"窗口按配置消掉了"，不是"复现→修复→不再复现"闭环。
7. **已建好的老 MySQL 卷里种子中文是乱码**（`character_set_client=latin1` 的双重编码）。新卷已修；现存卷需手工跑 `docker/mysql/migrate/2026-09-22-fix-seed-encoding.sql`（有守卫，重复执行 no-op）。

## 8. 明确不做（防"顺手加回来"）

nacos 配置中心、refresh token / OAuth2 / SSO / LDAP、RBAC 角色表与权限点表（角色仍是 `admin_user.role` 一列 + 两层判定）、`marketing-open-api` 的在线策略、审计归档到对象存储、Redis SCAN 类窥视、admin→业务服务的新入站端点、把 `AuditSink` 提升到 common、把 `seckill.buckets` 做成在线参数、把 SPA 构建挂进 maven 生命周期。

## 9. 关键口径速查

| 口径 | 当前值 | 来源 |
|---|---|---|
| 冒烟断言基数 | LITE/dev 95、FULL 96（八条链路） | README §6 |
| 单测 | Java 358/76 类、前端 46/9 文件 | 本次实跑 |
| LITE 入口容量 | ~90-100 msg/s（阈值：领券/秒杀 120/s、活动 200/s、优惠 500/s） | `scripts/load-probe.sh` 夹具读数 |
| LITE 消费并行度 | 8 worker（1→4→8 实测 25→54→115 msg/s），与 FULL 对齐 | README §3 |
| standalone 常驻内存 | 517-599 MiB / 768MiB 限额 | 09-24 LITE 复跑 |
| HikariCP | 30（曾 10，是当时读数抖动的来源） | README §3 |
| 构建前置 | **必须 `source scripts/common.sh`**（锁 JDK 17；Homebrew JDK 26 下 Lombok 炸） | 全局约束 |

## 10. 建议的下一步

三件小的、可以立刻做：**① 修 #82 判活**（改法已明确，代价是一个心跳键或一处渲染分岔，且它是面板可信度的问题）；**② FULL 本机进程档按 96 复跑一趟**（补上唯一没被两头夹住的 ⑥ 格子，顺带在 A→B 零清理条件下重现那条证据）；**③ 把 `check-ui-dist.sh` 写进任何"交付前"清单**，或干脆补一份最简 CI 让三道闸都自动化。

两件需要你拍板的：**④ 要不要动 LITE 的入口事务合并**（收益 1.5 倍、代价是幂等可见性语义，属于设计取舍而非 bug）；**⑤ 2 副本档要不要按新断言补一趟**（只有这一格能给出多副本下 `liveness`/持锁分布与界面写动作审计归属的答案，跑一趟约 10 分钟含部署）。

## 11. 文档地图

- 主文档：[README.md](../README.md) —— 架构、形态切换、八条链路、写入口矩阵、测试与验证、八条已知噪音、目录结构（820 行）
- 母版设计：`docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md`（③④⑤⑥ 总设计 + §13 实施偏离回写）
- 上游：`docs/superpowers/specs/2026-09-22-admin-console-foundation-auth-design.md`（①② 地基 + 账号体系）
- 段内 spec/plan：`docs/superpowers/specs/` 与 `docs/superpowers/plans/` 各四份（⑤③④⑥），每份 plan 末尾都有"执行记录 + 落地时对计划的修正"
- 界面证据：`docs/superpowers/evidence/6-browser-journey.md`、`6-ops-browser-check.md`、`6-reheat-full-container.md`

## 附：本篇的证据分层

**实跑**（本次会话）：第 3 节的规模统计（`find`/`@Test` 计数）、第 4 节全部七行命令与读数、第 6 节的容器与端口清单（`docker ps`/`lsof`）、HEAD 与工作区状态（`git status`/`git log`）。
**转录**（未重跑）：第 5 节覆盖矩阵、第 7 节各条的实测数字、第 9 节容量口径 —— 来自 README 与段内 plan 的执行记录，那里记录了它们各自的跑时与夹具。
**推断**：第 10 节的排序与工时估计。
