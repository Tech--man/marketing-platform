# Boot 3.4.7 升级回滚预案（2026-10-01 审计 P1-5）

## 为什么需要这份预案

`52863dc`（2026-09-29，feat(ops) 第六批）把 **Boot 3.2.12→3.4.7 + spring-cloud
2023.0.4→2024.0.1 的依赖大版本跳版**与 19 项无关变更（migrate.sh 台账、网关
PreAuthRateLimitFilter、traceId、告警 alerts.yml、HTTP 状态映射、`ActivityPublicView`
等）塞进同一个 46 文件提交。后果：**为撤销任何一处 Boot 回归而 revert 该提交，会同时
删掉全部正资产并复活一条裸 `DROP INDEX` 的旧迁移**——不可安全回滚。本预案回答
"真要退 Boot 时怎么办"，并把教训固化成纪律（AGENTS.md：依赖跳版必须独立提交）。

## 现状与已知风险面

| 项 | 现值 | 风险 |
|---|---|---|
| spring-boot | 3.4.7 | Tomcat 10.1.42 / Spring 6.2.8；micrometer registry 包名迁移已适配（prometheus→prometheusmetrics，3 处） |
| spring-cloud | 2024.0.1 | 与 Boot 3.4 官方配对 |
| spring-cloud-alibaba | **2023.0.3.2（钉旧版）** | 为 Boot 3.2/SC 2023 构建；兼容性依据只有 pom 注释"已实测"三字。**nacos profile 默认关闭，风险只在 FULL+nacos 真实故障转移时暴露——升级前必须做一次 `PROFILES=nacos` 的 FULL 启动+注册/摘除演练**（外部决策遗留项，见 README 第六批段落） |
| 已知的 3.4 行为差异 | — | ①advice 里 `response.setStatus` 会被渲染管道盖回 200（已改 ResponseEntity，`e23d0a5`）；②参数校验异常族换型（已补 handler，W3.2）；③这两条是**升级后才发现的**——还有没发现的，只能靠回归面兜住 |

## 回滚步骤（按最小爆炸半径排序）

**永远不要 `git revert 52863dc`。** 正确做法是"只退版本属性"：

1. 在独立 worktree 演练（不碰主工作树）：
   ```bash
   git worktree add /tmp/boot-rollback HEAD
   cd /tmp/boot-rollback
   ```
2. 只改根 `pom.xml` 的三个属性回 3.2 线：
   - `spring-boot.version` 3.4.7 → **3.2.12**
   - `spring-cloud.version` 2024.0.1 → **2023.0.4**
   - `spring-cloud-alibaba.version` 2023.0.3.2 → 保持（本来就是 3.2 线）
3. **必须同步回退的 3.4 适配点**（grep 逐一核对，它们在 3.2 下同样能编译，但行为归位）：
   - micrometer registry 包名 `prometheusmetrics` → `prometheus`（3 处，第六批适配）；
   - `GlobalExceptionHandler` 的 ResponseEntity 写法在 3.2 下兼容，**不用**回退
     （`setStatus` 那版在 3.4 才坏；保留 ResponseEntity 双代兼容）；
   - W3.2 的 4+1 异常 handler 保留（3.2 下多注册不伤）。
4. `mvn -B test` 全量 + 双前端 vitest + `./scripts/smoke-test.sh`（LITE 栈）——
   3.4→3.2 的行为差异只能靠这套回归面抓。
5. 通过后：独立提交 `chore(deps): Boot 3.4.7 → 3.2.12 回退`，**只含 pom.xml 与
   适配点文件**，不夹带任何业务变更。

## 未来跳版的门槛（纪律化）

- 独立提交、可单独 revert（AGENTS.md 已固化）；
- `PROFILES=nacos` 的 FULL 演练通过（注册/摘除/网关 lb 路由），否则 alibaba 钉版不许动；
- `scripts/assert-evidence.sh` 产出当轮证据（后端+双前端+新鲜度对拍）；
- 冒烟 `^401$` 状态断言绿。归属（复审 N-10 修正）：它只覆盖**网关侧**拒绝
  （无凭证打 `/api/admin/users` 在网关就被 401）；advice 的状态映射由
  `ActivityControllerTest.status().isUnauthorized()`（MockMvc 直打控制器）钉住——
  3.4 渲染管道盖回 200 的那类破坏，第一现场在后者，回滚验证两处都要绿。

## 关联

- 审计报告：`chat-1/audit-report-marketing-platform-f706bfc.md` P1-5（复审 N-10 修正了其中 `^401$` 的归属表述）
- 教训原始记录：`e23d0a5`（修复 52863dc 里两处 3.4 下的表面修复）、README 第六批段落
