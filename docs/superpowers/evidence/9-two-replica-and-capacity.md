# 证据 · 2 副本档按 114 复跑 + `load-probe.sh` 容量重新取数

**跑次时间**：2026-09-28 01:20 → 01:54（本机，Asia/Shanghai）
**代码状态**：仍全部在未提交的工作区（`main` @ `85641a4` 之上），与
`9-identity-five-form-runs.md` 是同一批改动。本文件只补那份文件第 6 节点名的两格空白。

## 1. 为什么这两格必须补

上一轮五套形态跑绿之后，README 里留了两处明写的空白，都不是"再跑一次就完事"的那种：

| 空白 | 只有这一格给得出答案的原因 |
|---|---|
| FULL 容器化 **2 副本** | 两个 coupon 副本共用同一个 `coupon:user:{模板}:{uid}` 限领键。单副本下"限领只生效一次"是平凡事实；跨副本才看得出它到底靠的是 Redis 还是某个实例的本地状态 |
| `load-probe.sh` **容量重新取数** | 登录口的 BCrypt（单次 50-100 ms）现在与四个业务服务同 JVM。LITE 那条"入口 ~90-100 msg/s"的叙事是账号体系**之前**量的，不重新取数就是拿旧数字盖新拓扑 |

## 2. 前置：LITE 先复跑，确认换上去的镜像带的是当前代码

`deploy-preview.sh` 重建 → `smoke-test.sh` **113/113**（01:26）。
这一步不是凑数：上一轮就吃过"旧镜像里 `/api/auth/login` 与 `/h5/` 都是 404"的亏。

然后 `docker compose -f docker-compose.preview.yml stop`（**stop 不是 down**，卷与容器都留），
再起 FULL：`./scripts/deploy-full.sh --scale marketing-seckill=2 --scale marketing-coupon=2`。

## 3. 一处值得记的部署期假象：网关 180s 未就绪

`deploy-full.sh` 报 `!! marketing-gateway 180s 内未就绪`，退出码 1。日志里
nacos gRPC 连续 27 次 `Fail to connect server`。

**根因不是网络配置**。当时 `vm.swapusage` 是 **used 19.5 GB / 20 GB**，
`mkt-nacos` 稳定吃 106% CPU —— 九个应用容器（含两个额外副本）+ 中间件把这台 16 GB
的机器压进重度换页，nacos 的 gRPC 握手在 3 s 超时线上反复掉。再等约 25 s，
`/actuator/health` 就 `UP`，`Fail to connect server` 归零，冒烟一次跑绿。

**所以：这一档的部署超时先按"资源不够"排除，再怀疑拓扑。**
`docker-compose.full-app.yml` 里 `mkt-mw` 是 `external: true, name: docker_default`，
也就是说中间件网络**本来就叫 `docker_default`**；`docker network ls` 查不到 `mkt-mw`
是预期结果，不是漂移。排查时若按"mkt-mw 没建出来"去修 compose，会去改一个没坏的东西。

## 4. 2 副本档实跑

`./scripts/smoke-test.sh` → **114/114**（01:51）。多的那一条仍是跨进程重预热回执。

### 4.1 跨副本限领：只生效一次（这一格的新信息）

新注册一个消费者（限领额度干净），对 `per_user_limit=1` 的 `CT2026001`
并发打 24 发领券，requestId 全部不同，经网关负载均衡到两个 coupon 副本：

```
受理(code:0) 条数: 1
41000 条数:      23
落库券数:         1
```

三条一起看才算数：**受理 1** 只证明同步判定收敛，**落库 1** 才证明异步消费侧没有第二张。
限领计数在 Redis（`coupon:user:{模板}:{uid}`），不在实例本地，所以副本数无关。

### 4.2 `jobs` 持锁者分布：每任务恰好一个 holder

`GET /api/admin/ops` 的 `liveness.jobs`：

```
coupon-expire          held=true  holder=75803ec0  ttlLeft=17
seckill-timeout        held=true  holder=a8531569  ttlLeft=2
local-message-retry    held=true  holder=a8531569  ttlLeft=7
不同 holder: ['75803ec0', 'a8531569']
```

两个不同实例各持有任务，且没有任何任务出现两个 holder —— 跨副本互斥在工作。

### 4.3 `liveness.processes` 又踩到 #82

同一份响应里 `marketing-activity`、`marketing-coupon`、`marketing-admin` 报 `false`，
而 `docker ps` 说它们在跑。这是**已知 #82**（判活把"无可改参数"读成"没在跑"）复现，
不是本轮引入的缺陷，也没有为它改任何代码。`marketing-account` 因此依旧**故意不进**
判活清单（它没有 ⑤ 的 `ConfigDefinition`，加进去只会多一条假读数）。

## 5. 容量重新取数

`TPL=CT-PERF-002 ./scripts/load-probe.sh`（模板 `per_user_limit=2000`，为本轮专建）。

| 参数 | 入口速率 | 尾部排空 | 结束时积压 | 净排空 |
|---|---|---|---|---|
| 600 条 / 80 并发 | 59 req/s | 0.44 s | 0 | —（读不出） |
| 1500 条 / 220 并发 | 54 req/s | 3.90 s | 412 | **106 msg/s** |

**入口速率是压测客户端的上限，不是服务端的。** 判据：并发从 80 提到 220（+175%），
入口速率反而从 59 掉到 54 —— 服务端没落后时（第一行积压为 0）提高并发不会改变吞吐，
而 `grant()` 每条都要起一个新 curl 进程，瓶颈在进程开销。
要拿到服务端真实上限，得把积压做出来（第二行做到了 412），然后读净排空：**106 msg/s**。

这个数落在 gateway yml 里"LITE 单机实测异步排空约 110-140 msg/s"的口径下沿，
说明**登录口 BCrypt 与四个业务服务同 JVM 之后，LITE 的异步消费能力没有塌**。
它同时是 `RL_COUPON` 在 LITE 收到 ≈120/s 的那条理由仍然成立的证据。

第二行还有 100 条 `200 41000`：600（第一行）+ 1400 = 2000，正好吃满 `per_user_limit`。
这条顺带证明了本轮 harness 侧的一处修正在干活 —— `grant()` 以前只留 HTTP 状态码，
而平台对业务失败也回 HTTP 200，于是 41000 会被算进"受理"，
"等 `persisted` 追上 accepted"就会空转到 900 s 超时。现在按 `HTTP + 业务码` 双条件计数。

## 7. 追加：admin 也起两副本（同日 02:41 → 03:12）

`--scale marketing-admin=2`。这一格是 README 矩阵里最后一格空白，问的是两件 114 覆盖不到的事。

### 7.1 `ui-route` 与写动作的审计归属

- 两个 admin 都注册进 nacos（`192.168.107.7:8086` / `192.168.117.8:8086`，均 `healthy=true`）。
- `/ui/` 与它引用的 hash 资源**连打 8 次全 200** —— 静态资源在两副本间轮转不会撕裂
  （镜像同一份 `static/ui`，且索引 `no-store`、资源 `immutable` 的指纹一致）。
- **审计归属成立**：12 次后台写（`POST /api/admin/activities`，`AUDITM2-<ts>-<i>`）→
  `admin_audit_log` 恰好 **12 行、12 个不同 `resource_id`、零重复**。
- 关键在于这条不是"只有一个 admin 在搬"的假象。`AuditOutboxDrainer` 用自起 daemon 线程
  而**不是** `@Scheduled`，所以它**不经过** `mkt_job_dedup` 那套跨副本互斥；
  它靠的是消费组 + 每进程唯一消费者名（`admin-<uuid>`）。实测 `XINFO CONSUMERS mkt:audit:pending admin-drain`
  里**恰好两个消费者 idle≈1.4 s**（5 秒一轮的节奏），两者 pending 都是 0 —— 两个副本确实在同时搬，
  而分摊而不是重放。④ 的 `consistency` 连读三次同形（`budget=0 / coupon-stock=1 / seckill-stock=0`），
  两副本读数不分歧。

### 7.2 一处新发现的泄漏（未修）

`admin-drain` 组里累积了 **48 个消费者条目**。每个 admin/standalone 进程启动都 `XGROUP CREATECONSUMER`
一个带随机后缀的新名字，**退出时从不 `XGROUP DELCONSUMER`**。后果：投递正确性不受影响
（pending 一直是 0），但 Redis 里这个组的消费组元数据随重启次数无界增长，
且 `XINFO CONSUMERS` 的输出会长到没法人工读（本轮判"几个在活"就得先按 idle 排序）。
修法方向：消费者名改成"实例可预测"的稳定值（容器名/IP + 端口），或退出钩子里删消费者。
记在这里，不在本轮改。

### 7.3 一条部署期竞态：首跑 111/1 不是回归

`deploy-full.sh --scale marketing-admin=2` 首跑时报两件事：
`后台路由没有返回登录成功，末次响应 503`，以及冒烟**链路 7** 红一条
（`面板读不出 budget 的不符条数`）。当时 admin 副本的日志显示它们**正在**注册：
`register finished` 时间戳 02:57:25 与 02:57:28，而脚本的探测打在 02:57:27 ——
撞在第二个实例注册完成前 1 秒，网关的 `lb://marketing-admin` 订阅还没刷到它。

栈热透之后**同一条命令复跑 114/114**，且 7.1 的读数全部成立。

**所以：新起 FULL 后若只红在链路 7 的 budget 比对那一条，先重跑一次再查代码。**
`deploy-full.sh` 的 `wait_route` 是按路由探通的，但它探的是"第一个可用实例"，
探不到"这个服务的所有副本都注册完了"—— 多副本下这天然是个窗口。

## 8. 测试产物清理（同日 11:26，经用户批准"全清"）

删前整表备份：`~/backups/marketing-test-artifacts-<ts>.sql.gz`（220 KB，含 6 张受影响表）。

| 类别 | 删前 | 判据 |
|---|---|---|
| `coupon_template CT-PERF-*` | 2 | 本轮为容量探针专建 |
| `user_coupon`（`XREP-*`/`PROBE*`/`SMOKE*`/`DEAL-*`） | 9309+ | 探针与压力测试发放 |
| `activity`（`ACT-SMOKE-*`/`AUDITM2-*`）+ 其 `budget_flow` | 139 | 冒烟每轮自造，从不回收 |
| `consumer_user`（`smoke*`/`p2*`/`demo1`/`xrep-*`）+ 其 `consumer_session` | 49 | 冒烟与探针账号 |
| `admin_audit_log`（`resource_id LIKE 'AUDITM2-*'`） | 12 | 审计归属实测那 12 发 |

**种子数据逐条核对未受伤**：`ACT2026001` 1、`CT2026001/02` 2、`SK2026001` 1、
`PR2026001~003` 3、`demo`(70001) 及其 3 张钱包券、`admin_user` 3。
清完**重建 LITE 复跑冒烟 113/113**，证明删的不是活数据。

### 8.1 一处**没有**删、需要人再判断的残留

`seckill_order` 共 7031 行，其中 **7030 行的 `user_id` 在 `consumer_user` 里根本不存在**
（范围 70009…8920479）。这不是本轮造的 —— 它是**账号体系之前**那套设计的必然产物：
旧冒烟与旧 load-probe 每个 worker 自报一个随机 `userId`，那些"人"从来没有账号，
却留下了订单。硬切之后这类 id 不可能再产生，所以这 7030 行是纯历史残留。

它不在批准时那张清单里，所以**没动**。删掉对功能无影响（库存恒等式读的是
`seckill_activity` 的 sold/total 与 Redis 分桶，不读订单行），
但它会让"秒杀历史"这类展示数据从 7031 掉到 1 —— 属于演示观感决策，交给人定。
同理 `user_coupon` 里还有 2 张挂在已消失的自报 id（702602 / 907116）上的孤儿券。

### 8.2 清理过程中撞到的一次环境故障

第一次"重建 LITE + 复跑冒烟"跑到链路 6 时注册返回空串，`require_consumer` 按设计
**立即中止**（而不是让后面 60+ 条断言全红）。查下来根因是 **OrbStack 的 VM 在内存压力下
被系统打掉**（当时 swap 19.5/20 GB），docker socket 直接消失。
`orb start` 重启后 swap 回到 7 GB 总量、37% 空闲，同一条命令复跑 **113/113**。

这条正好反过来印证 fail-fast 的价值：没有那句"注册失败就停"，
这次环境崩溃会表现成"C 端整片坏"，而排查方向会被指向刚做完的清理。

## 9. 这一轮**仍然**没验的东西

- 2 副本档没跑 `load-probe`：容量叙事是 LITE 服役档的，扩容档的并发口径不在这个问题里。
- admin **三副本以上**、以及 admin 副本滚动重启**期间**的审计连续性（本轮是稳态两副本）。
