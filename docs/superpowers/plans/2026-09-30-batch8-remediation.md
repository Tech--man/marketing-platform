# 第八批修复实施计划（2026-09-30 第二轮复审遗留收口）

> 范围：第二轮全量复审（7 路深读）发现、第七批（e23d0a5）未清的 P2/P3 项。
> 前七批已清：第一轮 H1–H11/A1–A4/B5-*、第二轮 P0×1 + P1×14 + P2 精选×10。
> 本批目标：把第二轮复审的确定性缺陷全部清零，外部决策项继续文档化。

## 目标与非目标

**目标**：约 30 项，按六个 Wave 推进，全程单批或按 Wave 分 commit。
**非目标（继续文档化，不属代码批）**：
- nacos 鉴权 / broker ACL（需凭证下发通道）
- admin_audit_log 保留政策（合规决策）
- 网关限流维度细分（pre-auth 无桶 → 需容量压测定阈值）
- CouponExpireJob 新索引执行计划（需线上 EXPLAIN 确认）
- 幂等租约"执行中续租心跳"（fencing token 落地后残余窗口为已知边界）

---

## Wave 1：DDL 地基（其余 Wave 的依赖）

### 1.1 幂等租约 fencing token（common F1，P2 最高）
**问题**：`IdempotentExecutor.markSuccess/markFailed` 的 WHERE 只判 `status='PROCESSING'`，不区分是哪一次执行。慢持有者（GC/挂起 >120s）被接管后回写：变体 a 双调用方拿到不同结果；变体 b 更糟——原持有者的 markFailed 命中接管者的 PROCESSING → 行变 FAILED → 第三次执行被放开，与接管者并发。
**方案**：`idempotent_record` 加 `claim_token VARCHAR(36) NULL` 列。
- claim 成功（INSERT / PROCESSING 租约 CAS / FAILED 抢占）时写入随机 UUID 并随 ClaimResult 携带；
- markSuccess / markFailed 的 WHERE 追加 `AND claim_token = ?`；
- 存量行 claim_token 为 NULL：首次接管时 CAS 同时补写 token（`WHERE claim_token IS NULL OR claim_token = ?` 不需要——直接在接管 UPDATE 里 SET 新 token，首写 INSERT 也带 token，NULL 只存在于历史 SUCCESS/FAILED 终态行，不会再被 mark）。
- 回放路径（REPLAY_SUCCESS / REPLAY_PROCESSING）不写 token，不受影响。
**涉及**：IdempotentExecutor（claim 返回 token、mark 带 token）、两份 init DDL、迁移 `2026-10-08-idempotent-claim-token.sql`（information_schema 守卫 + 幂等，migrate.sh ALL_DBS 台账跑）。
**验收**：H2 用例「租约过期被接管后，模拟原持有者的 markFailed（旧 token）不改变行状态」；变异检查（去掉 WHERE 的 token 条件必红）。

### 1.2 审计落库幂等键（admin F5）
**问题**：drain 是 at-least-once（insert 成功 → 进程死在 ACK 前 → reclaimStale 重投 → 重复落行），`admin_audit_log` 无来源消息标识，同一动作两行审计污染"次数"语义。
**方案**：加 `source_id VARCHAR(64) NULL` + `UNIQUE KEY uk_source (source_id)`（NULL 不聚合，存量行兼容）。drain 侧用 Stream 消息 ID（XADD 返回的 `id-seq`）作 source_id insert；`DuplicateKeyException` 视为已落库 → 正常 ACK/XDEL。
**涉及**：AuditOutboxDrainer、AuditService 建表 SQL/实体、admin 的 init DDL + lite DDL + 迁移 `2026-10-08-audit-source-id.sql`（admin 库）。
**验收**：H2 用例「同一 source_id 二次 insert 被吞并 ACK」；SchemaParityTest 过。

> Wave 1 完成标志：迁移脚本 2 份、两份 init DDL 同步、SchemaParityTest 绿。

---

## Wave 2：资金/库存一致性收口（A 档，7 项）

### 2.1 ActivityGatePublisher 发布竞态（activity F4）
**问题**：publishAll「全表 SELECT → 逐条 SET」两步之间状态变更会被 t1 旧快照覆盖 t2 已发布的 publishNow 新状态——OFFLINE 预案最长被回滚 5 秒；FULL 多副本叠加放大。
**方案（两步，彻底关死）**：
- (a) 状态键值升级 `ONLINE|<version>`（version 用 activity 表既有乐观锁列）。写入改 Lua CAS：仅当新 version ≥ 键内 version 才 SET（旧键无 version 段视为 version=0，天然兼容迁移期）；
- (b) publishAll 循环内逐条即时 SELECT 单行（窗口从全表快照级缩到毫秒级），publishNow 同款写入。
- coupon 侧 `ActivityGate` 解析 `indexOf('|')` 取状态段，无 `|` 的旧形状整串即状态（迁移期 fail-open 语义不变）。灰度键暂不版本化（灰度值变更 5s 收敛可接受，状态是预案开关必须即时正确）。
**涉及**：ActivityGatePublisher（值格式 + Lua）、ActivityGate（解析兼容）、新增 lua/gate_cas.lua。
**验收**：单测「publishAll 读到旧快照后 DB 已变更 → 写入被 CAS 拒绝/被新值覆盖不回滚」；Lua 语义用 RedisTemplate mock 的脚本参数断言 + 手册 Redis 实测记录。

### 2.2 refund 孤儿 DEDUCT 造钱（activity F10）
**问题**：deduct 进程级故障留下「流水在、Redis 未扣」的孤儿后，refund 只验流水存在就 INCRBY——把从未扣掉的钱加回，Redis 余额可超总预算继续被消费。
**方案**：refund 在 INCRBY 前比对 `Redis 当前值` 与 `公式值（computeRemainCents 含本笔 REFUND 预期）`：Redis ≥ 公式值（存在孤儿/漂移）→ 实退 `max(0, 公式值 - Redis 当前值)` 并 log.warn + `budget.refund.capped` 计数；Redis < 公式值（正常态）→ 全额退。正常恒等式下 Redis == 公式值 - 本笔退款额，差额恰好等于 amountCents ✓。
**涉及**：BudgetService.refund。
**验收**：BudgetServiceTest 用例「孤儿场景（Redis 高于公式）退 0 分且告警计数」「正常场景退全额」；变异检查。

### 2.3 rollback_stock 幽灵库存（coupon F5）
**问题**：Lua 对不存在的 stock 键无条件 INCRBY——键缺失（Redis 重启/overwrite 窗口）时凭空造出 `coupon:stock:{id}=qty` 幽灵键，模板可用库存凭空多出。
**方案**：脚本开头 `if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end`，键不存在时库存与用户计数两边都不动；Java 侧 -1 → `coupon.grant.rollback_nokey` 计数 + warn（残余由 mismatch 恒等式可见）。
**涉及**：rollback_stock.lua、CouponStockService.rollback、CouponGrantService.rollbackPreDeduct 传回值处理。
**验收**：行为级用例（mock Lua 返回 -1 时计数路径）；Lua 变更附手册 Redis 实测记录。

### 2.4 活动时间窗收口 + deduct 状态闸（activity F9 + F7）
**问题**：startTime/endTime 全链路无人引用——endTime 过了照扣预算照发券；create 不校验 start<end；C 端 budget/deduct 不查活动状态（OFFLINE/FINISHED 活动每用户每分钟可抽 100 元额度）。
**方案**：
- (a) create/update 校验 `startTime < endTime`（endTime 可空=不限）；
- (b) 新增 `ActivityExpirationJob`（RedisLeaseLock + 1 分钟一轮）：ONLINE/GRAY 且 endTime 已过 → 状态机 FINISH 迁移（数据本身对，gate 镜像/优惠规则联动自然收敛）；
- (c) `BudgetService.deduct` 入口查活动行：非 participatable 或过窗 → 41007/40000 拒绝（deduct 在 activity 进程内，直接读 DB）。
**涉及**：ActivityService（校验）、新 Job、BudgetService、ActivityConfigDefinitions（如需在线化扫描间隔则不必，硬编码 60s 即可）。
**验收**：状态机 Job 用例（过窗 ONLINE → FINISHED）；deduct 拒绝用例 ×2（OFFLINE/过窗）。

### 2.5 queryResult 返回 FAILED 终态（coupon F4 后半）
**问题**：`GrantResultVO.failed()` 是死代码——消息死信后用户端永远 PROCESSING，无负向终态信号。
**方案**：IdempotentExecutor 加 `Optional<IdempotentStatus> statusOf(bizKey)`；queryResult 无券时：FAILED → `GrantResultVO.failed(error_msg)`（H5 client 已按终态处理）、SUCCESS/无行 → processing。
**涉及**：IdempotentExecutor、CouponGrantService.queryResult。
**验收**：mock 幂等表 FAILED → failed 返回的用例。

### 2.6 秒杀 orderNo 撞键误判（seckill F12，小项）
**问题**：DuplicateKey catch 不区分撞 `uk_order_no` 还是 `uk_activity_user`——撞单号且无在途单时误判"在途重复"重投（自愈但放大重试）。
**方案**：catch 里按 `selectOne(order_no)` 回查：查到 → 纯撞号，换号重试一次（上限 1，仍撞外抛）；查不到 → 现有活跃单/取消单/在途逻辑。
**涉及**：SeckillOrderConsumer.persistOrder。
**验收**：mock 用例「撞 uk_order_no 换单重落成功」。

### 2.7 cancelTimeout 半事务化（seckill F6）
**问题**：状态 CAS / Redis refill / sold 回减三步各自 autocommit，中途进程死留永久账差。
**方案**：`@Transactional` 包住「状态 CAS + sold 回减」两步 DB 写（Redis refill 保持事务外前置——Redis 不可回滚，失败计数已有，恒等式兜底）。
**涉及**：SeckillOrderService.cancelTimeout。
**验收**：现有测试回归 + 注释说明取舍（refill 放事务前的理由）。

---

## Wave 3：可靠性/可观测（B 档，约 10 项）

| # | 项 | 方案要点 | 验收 |
|---|---|---|---|
| 3.1 | AdminIdentityService Redis 降级（admin F6） | 吊销位/bump 查询包 try/catch → degraded 计数 + 放行（与网关同口径，bounded by accessTtl） | Redis 抛错时放行且计数的用例 |
| 3.2 | Boot 3.4 校验异常族（common F8） | GlobalExceptionHandler 补 HandlerMethodValidationException / MethodArgumentTypeMismatchException / ConstraintViolationException / MissingServletRequestParameterException / HttpRequestMethodNotSupportedException → 40000（405 场景 HTTP 状态保持 METHOD_NOT_ALLOWED） | 5 类异常映射用例 |
| 3.3 | purgeTerminated 循环删空（common F5） | while(deleted==1000) 循环 + 单轮总量上限 50 万 | H2 造 2500 行删两轮的用例 |
| 3.4 | 幂等记录保留期独立配置（common F6） | `marketing.idempotent.retention-days`（默认 30）与消息域分离；Retryer 分别读 | 配置注入用例 |
| 3.5 | Retryer 锁 TTL 与 interval 同源（common F10） | INTERVAL 改构造注入 `${marketing.message.retry-interval-ms}` 同值 | 语义注释 + 编译级 |
| 3.6 | ReheatDispatcher PEL 回收（common F9） | consumerName 固化（`reheat-<topic>` 前缀）+ reclaimStale（照抄 StreamConsumerRegistrar，门槛 30s） | 回收逻辑用例 |
| 3.7 | 会话配额口径（account F8） | activeSessions/trimToQuota/GET /api/auth/sessions 改用 refreshableSessions 同款条件（refresh_expire_at 口径） | rotate 后配额仍生效的用例 |
| 3.8 | 登录枚举收口（admin F7） | admin 三态统一话术；C 端 DISABLED/LOCKED 错误码统一 40100（HTTP 401 同段） | 话术/错误码断言 |
| 3.9 | recordIfAbsent 长度校验（common F7） | payload/tag 入库前长度预算（payload>60000、biz_key/tag>列宽）→ BizException 而非 INSERT IGNORE 截断 | 超长 payload 拒绝用例 |
| 3.10 | Stream 消费卫生（F-14/F-15） | PEL 回收只让 index==0 的 worker 跑；deliver 校验消息 tag 与 handler 期望不符 → WARN 计数 | 单测 + 计数断言 |

**prometheus 联动**：补 `up{env=~"local|full-container"} == 0 for 3m` 告警；`MessagePurgeBacklog` 阈值随 3.3（循环删除）保持 5 万/1h 不动（修完产能后阈值变得可达）。

---

## Wave 4：前端交互批（一个 build 批收尾）

| # | 项 | 方案要点 |
|---|---|---|
| 4.1 | 秒杀详情页 phase 响应式（F-01）+ 轮询超时（F-06） | 1s ticker ref 驱动 phase；pollResult 套用 client.js 的 poll() 超时终态 |
| 4.2 | PagedTable 请求竞态（F-04） | 组件内自增 seq，落盘前 `seq !== mySeq` 丢弃 |
| 4.3 | ConfigView 删行选档（F-05） | 确认抽屉加 form 选择（默认本档 ownForm），多行时不再默认删第一行 |
| 4.4 | 高危操作二次确认（F-07） | SeckillView/CouponsView toggle + ActivitiesView transition（FINISH 不可逆文案）套 confirmRow 抽屉 |
| 4.5 | h5 时间时区（F-11） | toDate 解析拼 `+08:00` 再转本地显示（一处收口） |
| 4.6 | admin 杂项（F-12/F-13） | LoginView next 过 safeRedirect（复制 h5 实现）；AppLayout 倒计时归零后 `router.replace(login)` |

**收尾**：`npm test` 双前端 → `build-ui.sh` + `build-h5.sh` 重建产物 → `check-ui-dist.sh` 对拍。boot.test.js 的 IA 断言必须保持绿。

## Wave 5：部署卫生批

| # | 项 | 方案要点 |
|---|---|---|
| 5.1 | MySQL 口令（F-08） | `${MYSQL_ROOT_PASSWORD:-root123}` 等 env 注入；healthcheck 去 `-proot123`（本地 socket ping） |
| 5.2 | 容器非 root（F-09） | 各 Dockerfile `RUN useradd -r app && chown` + `USER app`（无卷写需求） |
| 5.3 | full-app healthcheck/mem_limit（F-10） | app-defaults 锚点补 TCP healthcheck + mem_limit（对齐 preview 编排） |
| 5.4 | 脚本卫生（F-17/F-18） | smoke-test 收尾清理 `smoke-%` 账号及其券/单；load-probe 改 mktemp + flock |

**注意**：5.1–5.3 改动后要跑一次 `deploy-preview.sh` 冒烟验证编排仍能起（容器内非 root 对 JVM 无影响，healthcheck 命令需在镜像内可用）。

## Wave 6：全量验证与提交

1. `mvn test` 全 reactor（11 模块）+ 双前端 vitest；
2. 变异检查 5 条：fencing token（1.1）、refund 差额封顶（2.2）、rollback EXISTS 守卫（2.3）、gate CAS（2.1）、审计 source_id 幂等（1.2）——全部「改回旧实现必红」；
3. SchemaParityTest（两份 init DDL 对拍）随全量跑；
4. 迁移脚本对 mkt-mysql 常驻卷执行并核对自检段输出；
5. Mimosa 提交前扫（hook 要求完整结论，若再遇 scanner_enobufs 按兼容策略放行后**必须**提交后补扫）；
6. commit：`fix(common,activity,coupon,admin): 审查收口第八批——复审 P2/P3 清零`（或按 Wave 分 2–3 个 commit）；
7. 更新记忆台账（第八批段 + 剩余外部决策项）。

---

## 规模与风险

- **预估改动**：约 45 个文件（Java ~28、测试 ~15、Lua 2、compose/Dockerfile/prometheus ~6、前端 ~8），新增测试约 25 条，2 份迁移脚本。
- **最大单项风险**：1.1 fencing 触碰全部幂等路径（领券/秒杀/budget 共用 executor）——先落 DDL 再改代码，全量回归兜底；2.1 gate 值格式变更涉及跨服务契约，解析兼容旧形状是迁移期硬要求（activity 新代码 + coupon 旧代码并存的窗口）。
- **顺序依赖**：Wave 1 → 2.1/2.2 无依赖可并行；Wave 4 的产物重建必须在所有 .vue 改完后一次跑；Wave 5 改编排后需起栈冒烟。
- **明示不做**（写入 README 已知边界或记忆）：幂等执行中续租心跳、publishAll 与外部重预热的更深竞态、审计保留政策。
