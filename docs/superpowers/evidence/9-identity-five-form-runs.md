# 证据 · 链路 9（消费者身份语义）与五套形态的复跑

**跑次时间**：2026-09-25 22:13 → 23:29（本机，Asia/Shanghai）
**代码状态**：全部在未提交的工作区（`main` @ `85641a4` 之上）。含消费者账号体系（`marketing-account`
新模块 + 网关 `ConsumerAuthFilter` + common 的 `ConsumerTokenCodec`/`ConsumerRequestIdentity`）、
C 端 H5（`marketing-h5-ui` → `static/h5`），以及本回收口时补的 6 处形态件。
**门禁基线**：`mvn test` 见本文件末尾"门禁"一节；`smoke-test.sh` 断言基数 LITE/dev **113**、
FULL 三种形态 **114**（多的那条是跨进程重预热回执）。

## 1. 为什么必须有链路 9

账号体系上线后，冒烟只是**用**身份（登录/注册当取 token 的管道），没有一条断言**验**身份的语义。
最容易安静失效的恰好是三条"错了不会有人立刻发现"的：

| 语义 | 只断"被拒"为什么不够 |
|---|---|
| 旧 refresh 重放 = 整条会话作废 | 40100 只证明"这一次刷新被拒"，不证明"那条会话死了" |
| 登出 = 当场失效 | 不断第二次请求，就分不清"吊销"和"等 15 分钟自然过期" |
| 改密 = 全部会话作废 | 不断"新口令能登录"，就分不清"改了哈希"和"只是把人踢下线" |

三条都写成了**配对形状**（先验有效，再验作废，最后验新状态可用）。

## 2. 这一段当场抓到的一处真缺陷

`ConsumerAuthService.refresh()` 命中 `consumer:refresh:used:{hash}` 黑名单后，用
`findByRefreshHash(hash)` 找受害会话——但 `rotate()` 是**同一行就地换新摘要**（jti 不变），
所以轮换过一次之后旧摘要在表里已不存在 → `victim` 恒为 `null` → **只抛 40100，不吊销会话**。
"重放即吊销整条会话"从头到尾只是注释与 README 里的承诺。

原来的单测为什么没抓到：`refreshReuseRevokesSession` 把 `findByRefreshHash` 直接 stub 成
返回会话，而 `refreshRotates` 单独验 `rotate` 被调用——两条各自成立，合起来才暴露矛盾。
把它改成**先轮换、再重放**的合成用例（用状态化的行：`rotate` 就地换 `refreshHash`，
按旧摘要查必然查不到），并让实现按黑名单 value 里的 jti 走 `findByJti`。

**变异检查**：实现退回"按摘要找" → 新用例立即红（`Wanted but not invoked: revoke("jti-live",
"REFRESH_REUSED", ...)`），其余 13 条不受影响；`findByRefreshHash` 退为"黑名单里没带 jti"的兜底分支，
另配一条用例钉住，避免它变成死代码。

端到端侧：`重放把整条会话吊销：刚换到的新 access 当场失效（40102）` 这一条在旧实现下必红
（会拿到 `code:0`），五套形态全部实测为绿。

## 3. 五套形态的实跑结果

| 形态 | 结果 | 这一档值得记的事 |
|---|---|---|
| LITE 服役档（容器） | **113/113**（22:25 首跑；23:29 修完全链路复跑仍 113） | 换栈前先 `deploy-preview.sh` 重建镜像：旧镜像里 `/api/auth/login` 与 `/h5/` 都是 404，`/ui/` 200 |
| dev 开发档（本机 2 JVM） | **113/113**（22:34，第二次跑） | **第一次跑在第一步就停**：`start-dev.sh` 没发 `ACCOUNT_HOST/ACCOUNT_PORT`，网关把 `/api/auth/login` 打到没人听的 `127.0.0.1:8087` → 500。与当年漏 `ADMIN_HOST` 同形 |
| FULL · 本机进程 | **114/114**（22:44，第二次跑） | 第七个 JVM `marketing-account:8087` 起来了——这本身就是 `port_of()` 补分支 + "起 JVM 前逐个验端口映射"那道快闸的证据（补之前这一档等满 150s 再假报未就绪）。第一次跑红 2 条：C 端直连探针取候选清单第一项（8081=activity）打 `/api/coupon/usable`，拿到 `40400` 也是带 `"code"` 的 JSON → 判据收紧为"只接受 `0`/`401xx`"，端口按 owning 服务写死 |
| FULL · 每服务一库隔离档 | **114/114**（23:04，第三次跑） | 第 6 个库 `marketing_account` 由 `docker/mysql/migrate/2026-09-25-account-db.sql` 在现存卷上补出来（`init/01-schema.sql` 此前只有 `USE` 没有 `CREATE DATABASE`）。第一次红 6 条 = 两套布局 `userId` 撞号 + 共用同一个 Redis（README 已知噪音 ⑨） |
| FULL · 容器化 1 副本 | **114/114**（23:22，第二次跑） | 第一次红 1 条恒等式（`remain=4991 sold=118 total=5000`）= 换布局未重预热库存桶（README 已知噪音 ⑩）；`reset-demo-data.sh` 之后复跑全绿。④ 大盘这一档实测 `mode=proxy`、**七个 target 全 OK**（`marketing-account` 是新增的一路），同时当场复现 #82：`liveness` 里 activity/coupon/admin 报 `false` 而 `docker ps` 说它们在跑 |

每档跑前都确认过只有一套栈在跑（`run/` 空 + `docker ps` 无并存应用栈），`deploy-full` 之后等 broker
起来才跑冒烟。**没有**用 `SKIP_BUILD=1`。

## 4. harness 侧的两处处置（不是产品代码）

1. `require_consumer` 现在按注册响应里的 `uid` 清掉 `coupon:user:*:{uid}` 与 `seckill:bought:*:{uid}`：
   两套库布局共用一个 Redis 时 `AUTO_INCREMENT` 会撞号，"全新的人"一出生就带着上一套布局某个同号
   账号的限领标记（实测 70002-70008 全是这种情况，红成 `41000 已超过单人限领`）。只删这个刚出生
   uid 自己的键，不扫别人的。
2. 请求体一律先收成变量再交给 curl：`expect ... "$(curl -d "{\"k\":\"$V\"}")"` 这种两层引号套在
   命令替换里的写法，服务侧收到的是残缺 JSON（40000"请求体不是可解析的 JSON"，且一发请求炸出两条
   解析错误），而顶层赋值 `R=$(curl -d "{…}")` 不受影响——这就是老链路一直对、新链路 9 一上来七条红
   里四条是这个成因的原因。

连跑多档还有一个硬窗口：注册口每 IP 10 次/小时、每轮注册 3 个。本轮复跑时按 IP 删过
`consumer:register:ip:*` 这几个计数键（测试机上的 harness 动作，不改产品配置、不改限额）。

## 5. 门禁

`mvn test` 全量 BUILD SUCCESS（含 `marketing-account` 25 个 Java 文件、419 个 `@Test` / 82 个类），
`marketing-admin-ui` 46 条 vitest、`marketing-h5-ui` 90 条 vitest，
`check-ui-dist.sh` 比对 **3 个文件**一致、`check-h5-dist.sh` 比对 **43 个文件**一致（都是 jar 与仓库 dist 逐项 sha256）。

## 6. 这一轮**没有**验的东西

> 2026-09-28 更新：下面前两条已在
> `9-two-replica-and-capacity.md` 里补掉（2 副本档 114/114 + 跨副本限领 + jobs 持锁分布；
> `load-probe` 净排空 106 msg/s）。第三条 #82 与第四条建库迁移不变。

- **2 副本档**未按 114 复跑：多副本下的 `liveness`/持锁者分布、`ui-route` 落到哪一个 admin、
  界面写动作的审计归属，仍是空白格。
- `load-probe.sh` 容量探针没跑：LITE 入口 ~90-100 msg/s 的口径本轮**未**在新代码上重新取数，
  而登录口的 BCrypt（单次 50-100ms）现在与四个业务服务同 JVM，LITE 的容量叙事要重新量一次才算数。
- #82（判活把"无可改参数"读成"没在跑"）没修，账号进程因此**故意不进** `PROCESSES` 判活清单：
  它没有 ⑤ 的 `ConfigDefinition`，加进去就会多一条假读数。
- `docker/mysql/init/01-schema.sql` 的建库修复只在**新卷**生效；现存卷靠本文件第 3 节那份迁移。
