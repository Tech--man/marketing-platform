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

## 6. 这一轮**仍然**没验的东西

- **admin 起两副本**时 `ui-route` 落到哪一个实例、界面写动作的审计归属。
  本轮只 scale 了 seckill + coupon，admin 是单副本，这一条**给不出答案**，
  README 那张矩阵里也照这么写的。它需要单独一轮（且这台机器当时的 swap 已经 19.5/20 GB）。
- 2 副本档没跑 `load-probe`：容量叙事是 LITE 服役档的，扩容档的并发口径不在这个问题里。
- 专建的两张压测模板 `CT-PERF-001` / `CT-PERF-002` 留在演示库里，没删。
