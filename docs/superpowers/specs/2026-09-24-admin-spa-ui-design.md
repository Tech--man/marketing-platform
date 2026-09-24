# ⑥ 独立 SPA 管理后台 · 段内 spec

日期：2026-09-24　前置：⑤ 在线配置、③ 业务管理面、④ 运维只读聚合均已并入 main（66eb1cc）
母版：`2026-09-23-admin-console-business-ops-ui-design.md` §8。这份记录**在写代码前把母版 §8
逐条对当前代码重验的结果**——④ 的经验是这种重验一次抓到八条过期前提，⑥ 抓到两条。

## 1. 母版 §8 的前提重验

| 母版的断言 | 重验结果 |
|---|---|
| "`AdminAuthFilter.java:70` 只处理 `/api/admin/` 前缀" | **模块归属与行为都过期**。这个 filter 在**网关**（`marketing-gateway/.../filter/AdminAuthFilter.java`，L48/L71 判前缀），而且它**验 JWT 签名**（L81 `codec.verify`）、查吊销位（L90）与整号作废位（L99），验过后剥 `Authorization`、注入 `X-Admin-*` 与 `X-Admin-Token`。admin 侧的 `AdminIdentityService` 优先信这套头、缺头才自己验签。**对本段的后果**：`/ui/**` 不经过它（前缀不匹配）→ 静态资源天然不需要后台凭证，这是对的；但也意味着**前端拿不到任何网关注入的身份头**，"我是谁/什么角色"必须调 `GET /api/admin/auth/me` 取，不能从 HTML 里猜 |
| "白名单当前只有 `/actuator/**`（gateway yml:55-56）" | 成立，行号漂到 **77-78**。`/ui/**` 不加进白名单会被 `AuthFilter` 按 C 端口径要 demo token → 打不开页面 |
| "`ui-route` 同时**要**进 rate-limit map，因为 route 不在 map 里=完全不限流" | 成立（`RateLimitFilter.java:71-73`）。补一条更准的：阈值现在还会被 ⑤ 的在线快照覆盖（L69-70 经 `RateRuleResolver`），所以 yml 里给了不等于生效值，但**没给且快照里也没有**才是真不限流 |
| "路由要 local 与 nacos **两套 profile 各加一条**" | 成立且必要：local 段 L27-68、nacos 段 L148-178，③ 的四条业务后台路由两边都写了。还有**第三份**不在 yml 里：LITE 由 compose 把 `ADMIN_HOST/ADMIN_PORT` 指到 `standalone:8085` → `ui-route` 复用同一对占位符就自动跟随，不需要为 LITE 单写一条 |
| "token 只能走 `Authorization: Bearer`（`AdminAuthFilter:162-168`）" | 成立（读头在 L172-177）。行号过期不影响结论 |
| "`AdminProperties` 900s TTL / 登录响应有 `expiresInSeconds`" | 都成立：`AdminProperties.java:19` `accessTtlSeconds=900`；`LoginView.java:7` 的 `expiresInSeconds` 由 `AdminAuthService.java:81` 填 |
| "仓库无任何 CI" | 成立（`.github`/`.gitlab-ci.yml`/`Jenkinsfile`/`.husky` 全不存在）。`.gitignore` 存在但**没有任何前端相关行**，只有 `target/ logs/ run/ .admin-jwt-secret *.log .idea/ *.iml .vscode/ .DS_Store` |
| （母版未提）**CORS** | 全仓零 `CorsConfiguration`/`@CrossOrigin`/gateway cors。→ SPA **必须同源**部署在 `/ui/` 下，`vite dev` 想直连 8090 得自己配 proxy；这一条决定了"开发期怎么跑" |
| （母版未提）**业务后台的 list 路径不统一** | `/api/admin/activities`（复数、无二级）· `/api/admin/coupon/templates` · `/api/admin/seckill/activities` · `/api/admin/discount/rules`。前端列表页**不能**按同一个模板拼 URL |
| （母版未提）**④ 的上限是 256KB 不是 64KB** | `ProxyMeterSource.java:29` `MAX_BYTES = 256*1024`。母版 §9 基线段写的"须限 64KB/target"按实现改写 |

## 2. 本轮已拍板

1. **范围＝全量**：11 个页面一次做完（登录 + 运维大盘 + 在线配置 + 活动/券/规则/秒杀四个列表与其写动作 + 账号/会话/审计）。理由：③④⑤ 的后端端点已经全部就绪，剩的纯粹是前端工作量；分批会让"后台只能 curl"这件事多活一轮。
2. **组件库＝Element Plus 按需引入**（`unplugin-vue-components` + `unplugin-auto-import`）。体积实测后进 README，超过预期再谈换法。
3. 环境已核实：node v22.22.3 / npm 10.9.8 / `registry.npmjs.org` 可达（`npm view vue` → 3.5.43）。**构建只在本机或 CI 跑，不进 maven 生命周期**（母版 §10 明确不做 frontend-maven-plugin）。

## 3. 落点与构建链

- 新目录 `marketing-admin-ui/`（**不进 root pom**）：Vue3 + Vite + vue-router + pinia + Element Plus。`vite.config.js` 里 `base: '/ui/'`、`build.outDir` 直接指到 `../marketing-admin/src/main/resources/static/ui`。
- **产物入仓**（`static/ui/**` 是 git 跟踪的构建产物），源码目录 `node_modules/` 忽略。这样"clone 下来 `mvn package` 就能跑出一个带后台界面的 jar"，不需要装 node —— 这是这个仓库给 LITE 服役档留的确定性。
- `scripts/build-ui.sh` 是唯一构建入口：装依赖 → build → 往 `index.html` 注入一行构建指纹注释（commit + 时间），并打印 dist 的字节数与文件数。
- 开发期：`npm run dev` 走 vite proxy 把 `/api` 与 `/ui` 之外的请求转给 8090（同源约束由 proxy 满足，不靠放开 CORS）。

## 4. 网关三处改动（漏一处就是"只在升档后才红"）

1. `routes`：**local 与 nacos 各加一条** `ui-route` → `uri: http://${ADMIN_HOST:127.0.0.1}:${ADMIN_PORT:8086}`（nacos 段 `lb://marketing-admin`），`Path=/ui/**`。
2. `whitelist` 加 `- /ui/**`。
3. `rate-limit` 加 `ui-route: {limit: ${RL_UI:100}, window-seconds: 1}`。静态资源一次首屏是十几个请求，100/s 既掐不死正常打开，也挡住了"把 jar 当文件服务器刷"。

`AdminAuthFilter` 与 `AuthFilter` **代码零改动**：前者按 `/api/admin/` 前缀生效、后者按白名单放行。

## 5. admin 侧：静态资源、history fallback、缓存头、CSP

- 新增 `AdminWebMvcConfig`（admin 的 config 包，今天那里只有 `AdminSecurityConfig`）：给 `/ui/**` 注册 `ResourceHandler` 指向 `classpath:/static/ui/`，并用 `PathResourceResolver` 在**资源不存在时回退 `index.html`**——history 路由（`/ui/audits`）直接刷新要能开。不放网关 rewrite：网关不该懂前端路由。
- 缓存头两条：`/ui/index.html` → `no-store`（否则改了 jar 里的产物、浏览器还在用旧索引，而指纹文件名让人以为已经刷新了）；`/ui/assets/**` → `max-age=31536000, immutable`（Vite 的文件名带内容指纹）。
- CSP 由同一个 config 给 `/ui/**` 响应加 `script-src 'self'`（母版 §8 的残余 XSS 兜底）+ `object-src 'none'` + `base-uri 'self'`。
- 聚合形态无关性：LITE/dev 下这段配置随 admin 模块一起进 standalone，`ui-route` 的 `ADMIN_HOST:ADMIN_PORT` 指到 8085，因此**五套形态都不需要为 `/ui` 单独配任何东西**。

## 6. 页面清单

| 路由 | 页 | 用的端点 | 角色 | 写动作的失败码要在界面上说清 |
|---|---|---|---|---|
| `/login` | 登录 | `POST /api/admin/auth/login` | — | 40100（账号不存在≡口令错）、42900（LoginGuard 每 IP 10 次/分钟且**含成功尝试**，倒计时按 `Retry-After` 给） |
| `/ops` | 运维大盘（④） | `GET /api/admin/ops` | 任意后台角色 | 只读；`-1`/`applicable=false`/`ERROR` 三种"看不见"要**画成不同形状**，不许显示成 0 |
| `/config` | 在线配置（⑤） | `GET/PUT/DELETE /api/admin/config`、`POST /config/rebroadcast` | 读任意 / 写仅 admin | 40000（未声明键、越界值）、404（删不中）、41009（已落库未广播 → 界面上就该有"重新广播"这个按钮） |
| `/activities` | 活动 | `GET/POST /api/admin/activities`、`POST .../transition?event=`、`PUT .../budget`、`PUT .../gray` | 写仅 admin | 41008（乐观锁撞号：别人先存过了，界面给"重新加载并对比"）、41000/41001（状态机拒绝） |
| `/coupons` | 券模板 | `GET/POST /api/admin/coupon/templates`、`PUT /{no}/stock`、`PUT /{no}/status` | 写仅 admin | 40000（改小低于已发数） |
| `/rules` | 优惠规则 | `GET/POST /api/admin/discount/rules` | 写仅 admin | 启停与新建同一条 upsert，`status` 在 body 里 |
| `/seckill` | 秒杀活动 | `GET/POST /api/admin/seckill/activities`、`PUT /{no}/stock`、`PUT /{no}/status` | 写仅 admin | 非 ONLINE 改库存**不重建分桶**（界面上得写明，否则运营以为票放出来了） |
| `/cache` | 重预热 | `GET /api/admin/cache/types`、`POST /cache/reheat`、`GET /cache/reheat/ack` | **admin 或 operator** | 41010（本形态没人认领）；`DISPATCHED` 要显示成"已投递、等回执"并自动轮询到 `DONE`/`FAILED` |
| `/users` | 账号 | `GET /api/admin/users`、`GET /users/{id}`、`PUT /users/{id}/status` | 读任意 / 停号仅 admin | 停用同时作废其全部会话（文案要说） |
| `/sessions` | 会话 | `GET /api/admin/sessions?mine=`、`DELETE /sessions/{jti}` | 不带 `mine` 仅 admin；踢人仅 admin | 踢的是 jti，被踢的那一端下一发就 40102 |
| `/audits` | 审计 | `GET /api/admin/audits`（`actorId/action/resourceType/resourceId` 过滤） | 任意后台角色 | — |
| `/me` | 当前身份 | `GET /api/admin/auth/me`、`POST /auth/logout`、`POST /auth/password` | 本人 | 改口令会让**全部会话**作废，改完必须重新登录（界面上提前告知） |

分页契约全仓统一（`Result<PageResult<T>>`，含 `total/page/size`），所以列表组件抽一个就够；URL 不统一（§1 倒数第二条），因此每个列表页显式写自己的端点常量，**不做约定式拼装**。

## 7. token 与过期

- 存 **localStorage**：`Authorization: Bearer` 是网关唯一识别路径（§1），改 cookie 要么动 ③⑤ 的鉴权链要么加反代；不写 cookie 就没有 CSRF。残余 XSS 风险由 CSP `script-src 'self'` + 900s TTL + `admin:revoked:{jti}` 单会话吊销三层兜。
- 三个码分处：**40101**（过期）→ 跳登录并带上"回来原页面"；**40102**（已吊销/账号被停）→ 跳登录且**不自动重放**（这一发可能已经落库了，重放会二次写）；**40100**（无凭证/验签失败）→ 直接清 token。
- 登录响应的 `expiresInSeconds` 驱动一个倒计时与"一键续登"，**不做 refresh token**（母版 §10）。倒计时归零前 60s 提示一次，避免表单填了一半被静默过期。

## 8. dist 一致性门禁（没有 CI 的仓库怎么防"半提交"）

风险：`static/ui/**` 是入仓产物，改了 `.vue` 却忘了重构建，或者只拷了 `index.html` 没拷新的 `assets/*`——jar 里会打出一个白屏后台。仓库没有 CI，所以门禁只能长在**构建与测试**这两处：

1. `scripts/build-ui.sh` 是唯一入口，且把 `commit + 构建时间` 注进 `index.html` 注释；
2. `scripts/check-ui-dist.sh`：从 jar（或 `target/classes/static/ui`）里逐项解出 `BOOT-INF/classes/static/ui/**` 与仓库目录**逐个比 sha256**，不一致非零退出；
3. `UiDistIntegrityTest`（admin 模块，不连库、不装 node）：断言 classpath 上 `static/ui/index.html` 存在，且它引用的每个指纹资源文件都在——挡掉"半提交"，即使没人跑前两条。

这三条是递降关系：跑不了 shell 的人至少跑不掉单测。

## 9. 验收（双层证据）

- **后端面**：`UiDistIntegrityTest` 3 条（index 在 / 指纹齐全 / 缺一个就红）+ `AdminWebMvcConfigTest`（history fallback 命中 index、`/ui/assets/x` 不存在时**不**回退成 index 的 200 而应 404，防止把 404 变成"永远 200 的假页面"）+ 缓存头与 CSP 断言。
- **网关面**：`ui-route` 的 yml 断言（两套 profile 都在、且在 rate-limit map 里、且 `/ui/**` 在白名单）——这是 ③⑤ 的教训：这类"配置对不对"用 `ApplicationContextRunner` 读 yml 断言比真起服务便宜两个数量级。
- **smoke 链路 8（3 条）**：`/ui/` 200 且 `no-store`、`/ui/assets/<任一指纹文件>` 200 且 `immutable`、无 token 打 `/api/admin/users` 仍 `40100`（加了界面不等于加了口子）。
- **真浏览器旅程**（按记忆里那条"全流程验证要双层证据"）：LITE 起栈 → 浏览器登录 → 逐页截图 → 做一次真写（改一个活动的预算，回列表看到新值）→ 大盘看到 ④ 的读数 → 退出后回看 `/ui/users` 应被踢回登录。这一趟走 `browser-use`，不是可选项。
- **形态复跑**：LITE + dev（dist 同一份，因此这两档验的是"静态资源在两种装配下都在"）；FULL 两档只验 `ui-route` 在 nacos profile 下可达（同一份 jar，没必要五档全刷）。

## 10. 明确不做

refresh token / cookie 会话 / OAuth2 / SSO；把 SPA 构建挂进 maven 生命周期；放开 CORS；nginx 容器；`marketing-admin-ui` 进 root pom；给 ④ 的大盘加图表库（表格 + 三态标记够用，图表是"看得更久"那一批，与保留 job 一起推迟）；前端角色矩阵（后端已判，前端只按 `/auth/me` 的 role 灰化按钮，**不**当成安全边界）。

## 11. 风险

1. **入仓产物会漂**。三个门禁挡得住"忘了重构建"，挡不住"故意只改产物"——所以 `build-ui.sh` 必须在 README 与 spec 里都被写成唯一入口。
2. **jar 体积与 LITE 内存**。静态资源走 classpath 不占堆，但 jar 变大会让 `docker build` 多一层；实测后把 dist 字节数写进 README，超过 2 MiB 就回头改成"只留 gzip + `spring.web.resources.chain` 压缩变体"。
3. **`index.html` 的 no-store 是双刃**：不设就会"升级后台后用户还在跑旧索引 + 新指纹 404"；设了则每次进页面多一发请求。前者是故障，后者是成本，选前者。
4. **界面写动作把 41008 变成常态**。后台多人同时开着同一张活动页时，谁后保存谁撞号；界面上不把"重新加载并对比"做出来，用户会以为保存成功了。
5. **无 CI 意味着门禁靠人**。`check-ui-dist.sh` 不进任何自动流程，只有 `UiDistIntegrityTest` 会因为 `mvn test` 而被顺带跑到——这一点要在 README 明说，别让人以为产物一致性是自动保证的。
