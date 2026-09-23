# ③ 业务管理面与 C 端写入口收口 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"配置类写"从 C 端路径搬进 `/api/admin/**` 且搬进 **owning 业务服务**（不是 admin 进程），补齐今天只有 SQL 种子的创建能力（券模板、秒杀活动）与预算/灰度/库存编辑，并把 ⑤ 留下的两个跨进程缺口正面结掉：审计归属与 FULL 下的重预热回执。

**Architecture:** 四条新前缀由 owning 服务自己发布，网关只做转发与粗筛（①② 的 `AdminAuthFilter` 只加一个头）。业务侧不信任裸身份头，改**验签** `X-Admin-Token`（common 里已有的 `AdminTokenCodec`，无新密码学）。改预算/库存的 service 方法里，DB 写与 `reheat(force=true)` 必须同方法同事务——这是防地雷 A 复发的唯一硬约束，每条都用 Mockito `verify` 钉死。审计与重预热走 Redis Stream 两跳（`mkt:audit:pending` / `mkt:reheat:pending` + `mkt:reheat:ack`），沿用本仓库 LITE MQ 已有的 consumer group 手法，**不给 admin→业务加新入站端点**。

**Tech Stack:** Java 17、Spring Boot 3.2.5、Spring Cloud Gateway（WebFlux）、MyBatis-Plus 3.5.7（`@Version` + `OptimisticLockerInnerInterceptor` 五份配置里都已装配）、Lettuce `StringRedisTemplate`（Stream 用 `opsForStream`）、Micrometer、JUnit 5 + Mockito + H2 `MODE=MySQL`、`ApplicationContextRunner`。

**Spec:** `docs/superpowers/specs/2026-09-23-admin-business-console-design.md`（段内 spec，含对母版 §6.0/§6.3 与 ⑤ §4 的四处修改及理由）

## Global Constraints

- **JDK 必须 17**：任何 `mvn` 前先 `source scripts/common.sh`（Homebrew 默认 JDK 26 会让 Lombok 注解处理崩成满屏 `cannot find symbol`）。
- **单模块测试必须带 `-am`**：`mvn -q -pl marketing-activity -am test`。少了它会用本地仓库里的旧 `marketing-common`。
- **admin 绝不 import 业务模块；业务模块绝不 import admin**。本段要在 common 里放的只有：身份件、角色与 `AdminPrincipal` 两个 record、审计载荷与编解码、Stream 键名。
- **网关绝不引入 DataSource 或阻塞 Redis 客户端**（⑤ 立的边界）。本段网关只改两处：加路由、透传一个头。
- **写与重预热不拆开**：`reheat(key, true)` 必须和被它刷新的 DB 写在同一个 service 方法里。admin 侧不得"写完 DB 再另外调一次刷新"，也不得为此给业务服务加 HTTP 入站。
- **缺 `ADMIN_JWT_SECRET` 就拒绝启动**（沿用 admin 的口径）：本段把它从 gateway+admin 两进程扩到六个进程，业务侧的 `AdminRequestIdentity` 构造期对空密钥抛异常，**不做"没有密钥就免鉴权"的降级**。
- **基线**：现有 **72 条** smoke 断言在迁移后必须同时全绿；单测基线 **45 类 / 182 个 `@Test`**（⑤ 收尾时刷新过），本段收尾再刷新一次。
- **每处关键断言做变异检查**：把被测那一行注释掉/改坏，对应测试必须红。红了才算断言存在。
- **每个任务收尾时 72 条基线必须回绿**：所以"搬家"与"删掉 C 端旧路径"必须在同一个任务里做完
  （T3-T6 各自搬自己的端点并顺手改 `smoke-test.sh` 对应那几行），不能拆成"先加新的、最后统一删旧的" ——
  中间那几个任务会留下两个入口都能改预算的状态，那正是地雷 A 的成因，而且基线红着没法继续跑。
  T9 只剩"没有搬家负担"的部分：`reset-demo-data.sh` 改写、新链路 6、`load-probe.sh` 提示语。
- 提交信息用中文，前缀沿用本仓库历史（`feat(...)` / `fix(...)` / `refactor(...)` / `test(...)` / `docs(...)`）。
- 五形态复跑口径与 ⑤ 相同：LITE 容器 / FULL 进程 / FULL 容器 / dev / 每服务一库；每服务一库档跑冒烟要 `MYSQL_DB=marketing_activity ./scripts/smoke-test.sh`。

## 文件结构（先看落点，再看任务）

**marketing-common**（新增 5 个类 + 移入 2 个 record + imports 一行）

| 文件 | 责任 |
|---|---|
| `common/security/AdminPrincipal.java` | **从 admin 移入**（12 个引用点改 import）。uid/username/role/jti + `hasRole` |
| `common/security/AdminRoles.java` | **从 admin 移入**。`admin`/`operator`/`read-only` 的唯一出处，网关与 admin 与四个业务服务共用 |
| `common/security/AdminRequestIdentity.java` | 业务侧"这个请求是谁 + 够不够角色"：验 `X-Admin-Token` 签名与有效期，**头里的 role 一律不采信** |
| `common/audit/AuditPayload.java` | 一条审计的跨进程载荷，字段与 `admin_audit_log` 列一一对应（before/after 已在 `requestSummary` 里） |
| `common/audit/AuditPayloadCodec.java` | 载荷 JSON 编解码；读侧永不抛（与 ⑤ 的 `ConfigSnapshotCodec` 同一纪律） |
| `common/transport/StreamKeys.java` | `mkt:audit:pending` / `mkt:reheat:pending` / `mkt:reheat:ack` 与两个消费组名，唯一允许出现这些字面量的地方 |
| `common/audit/AuditOutbox.java` | 业务侧投递：`XADD mkt:audit:pending`，`MAXLEN ~100000`、**无 TTL**（TTL=静默丢审计）；XADD 失败只 warn + 计数，绝不把业务写回滚掉 |
| `common/config/AdminSecurityAutoConfiguration.java` | 装上面几件的 `@AutoConfiguration`，`@ConditionalOnWebApplication(SERVLET)`，**不挂 DataSource 条件** |

**marketing-gateway**（改 2 处）：`application.yml` 两套 profile 各加四条路由 + 四条限流项；`AdminAuthFilter` 的 remove 列表加 `X-Admin-Token` 并 set 刚验过的 token。

**marketing-admin**（改 4 处）：`AdminConfigController` 等 12 个文件的 import 换到 common；新增 `AuditOutboxDrainer`（XREADGROUP → `admin_audit_log` → XACK）；`AdminCacheController` 的 `41010` 分支改成 DISPATCHED + `GET /cache/reheat/ack`。

**四个业务模块**（各自新增 controller/service/dto，形状一致）：
`XxxAdminController`（`/api/admin/...` 前缀）+ 写 service 方法（同事务 reheat）+ 列表 VO + `XxxAdminDefinitions`（⑤ 的 SPI 不需要新增键，本段不声明任何键）。

**脚本**：`smoke-test.sh`（链路 0 换路径换 token + 新链路 6）、`reset-demo-data.sh`（改走后台端点，删三条形态分支）、`load-probe.sh`（提示语）、四套入口（`ADMIN_JWT_SECRET` 下发）、`README.md`。

---

### Task 1: common 的身份件、审计载荷与键名（纯逻辑，零业务接线）

**Files:**
- Create: `marketing-common/src/main/java/com/example/marketing/common/security/AdminPrincipal.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/security/AdminRoles.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/security/AdminRequestIdentity.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/audit/AuditPayload.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/audit/AuditPayloadCodec.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/transport/StreamKeys.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/config/AdminSecurityAutoConfiguration.java`
- Delete: `marketing-admin/src/main/java/com/example/marketing/admin/security/AdminPrincipal.java`
- Delete: `marketing-admin/src/main/java/com/example/marketing/admin/security/AdminRoles.java`
- Modify: 12 个引用 `AdminPrincipal` / 5 个引用 `AdminRoles` 的文件（只改 import）
- Modify: `marketing-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `marketing-common/src/test/java/com/example/marketing/common/security/AdminRequestIdentityTest.java`
- Test: `marketing-common/src/test/java/com/example/marketing/common/audit/AuditPayloadCodecTest.java`
- Test: `marketing-common/src/test/java/com/example/marketing/common/config/AdminSecurityAutoConfigurationTest.java`
- Create: `marketing-common/src/main/java/com/example/marketing/common/audit/AuditOutbox.java`
- Test: `marketing-common/src/test/java/com/example/marketing/common/audit/AuditOutboxTest.java`

> **顺序提醒**：`AuditOutbox` 必须在 T1 就落，因为 T3-T6 的每个写端点都要 `outbox.record(payload)`
> （母版 §6.2 的"所有写记 before/after"）。T7 只做 admin 侧的 drain，中间几天审计堆在 Stream 里
> 不丢 —— 这正是选 Stream + 无 TTL 的原因。

**Interfaces:**
- Consumes: `AdminTokenCodec.verify(String, long)` → `TokenVerifyResult`（`status()` + `claims()`，已有）；`BizException.of(ErrorCode, String)`（已有）。
- Produces: `AdminRequestIdentity.require(HttpServletRequest, String...)` → `AdminPrincipal`；`AuditPayload`（13 字段 record）与 `AuditPayloadCodec.write/read`；`StreamKeys.auditPending()` / `reheatPending()` / `reheatAck()` / `ADMIN_DRAIN_GROUP`。Task 7 与 Task 3-6 直接消费这三件。

- [x] **Step 1: 把两个 record/常量类移进 common**

`git mv` 保历史，包名从 `admin.security` 换到 `common.security`，类内容一字不改（`AdminPrincipal` 的类注释里"字段全部来自网关注入的 X-Admin-* 头"这句要改，见 Step 2 之后的事实）：

```bash
git mv marketing-admin/src/main/java/com/example/marketing/admin/security/AdminPrincipal.java \
       marketing-common/src/main/java/com/example/marketing/common/security/AdminPrincipal.java
git mv marketing-admin/src/main/java/com/example/marketing/admin/security/AdminRoles.java \
       marketing-common/src/main/java/com/example/marketing/common/security/AdminRoles.java
```

`AdminRoles` 的类注释加一句：本段起它是**网关 + admin + 四个业务服务共 6 处**的唯一出处，
值域改动要同时改网关 `application.yml` 的路由配置。

批量改 import（12 + 5 个文件，只动 import 行，别顺手改格式）：

```bash
grep -rl "com\.example\.marketing\.admin\.security\.\(AdminPrincipal\|AdminRoles\)" \
  --include="*.java" marketing-admin | while read -r f; do
  sed -i '' 's/com\.example\.marketing\.admin\.security\.\(AdminPrincipal\|AdminRoles\)/com.example.marketing.common.security.\1/' "$f"
done
```

同一条命令要跑第二遍、把 `src/test` 里的引用一起改掉（上面 grep 已覆盖 test，因为传的是模块目录不是 `src/main`）。

- [x] **Step 2: 写失败测试 —— 只有裸身份头时必须 40100**

`AdminRequestIdentityTest.java`。这个文件里最重要的一条就是它：**没有签名 token、只带 `X-Admin-*` 头的请求必须被拒**。它是本段与母版 §6.0 分歧的回归锚——将来谁觉得"读头更省事"而退回裸头，这条必须红。

```java
package com.example.marketing.common.security;

import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务侧后台身份。
 *
 * <p>与 admin 的 `AdminIdentityService` 的区别是本件**只认签名 token**：
 * 业务端口在 FULL 进程形态监听 `*:8084`、LITE 的 standalone 8085 按设计发布到宿主机，
 * 把裸 `X-Admin-Role` 当授权等于"局域网里谁都能改预算"。段内 spec §3.2。</p>
 */
class AdminRequestIdentityTest {

    private static final String SECRET = "unit-test-secret";
    private static final long NOW = 1_800_000_000L;

    private final AdminTokenCodec codec = new AdminTokenCodec(SECRET, Duration.ofSeconds(30));
    private final AdminRequestIdentity identity = new AdminRequestIdentity(SECRET, Duration.ofSeconds(30));

    private String token(long uid, String sub, String role, long iat) {
        // 字段顺序即构造顺序：uid, sub, role, pwdVersion, jti, iat, exp
        return codec.issue(new AdminClaims(uid, sub, role, 1, "jti-" + uid, iat, iat + 3600));
    }

    private MockHttpServletRequest with(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (token != null) {
            req.addHeader("X-Admin-Token", token);
        }
        return req;
    }

    @Test
    @DisplayName("裸身份头不构成授权：只有 X-Admin-* 而没有 X-Admin-Token 时 40100")
    void bareHeadersAreNotCredentials() {
        MockHttpServletRequest req = with(null);
        req.addHeader("X-Admin-Uid", "1");
        req.addHeader("X-Admin-User", "admin");
        req.addHeader("X-Admin-Role", "admin");
        req.addHeader("X-Admin-Jti", "jti-1");
        BizException e = assertThrows(BizException.class, () -> identity.require(req, AdminRoles.ADMIN));
        assertEquals(40100, e.getCode(), "缺凭证是 40100，不是 40300（身份都不成立，谈不上权限）");
    }

    @Test
    @DisplayName("验签通过则身份取自 claims，role 与头不一致时以签名为准")
    void roleComesFromClaimsNotHeaders() {
        MockHttpServletRequest req = with(token(7L, "operator", AdminRoles.OPERATOR, NOW));
        req.addHeader("X-Admin-Role", AdminRoles.ADMIN);   // 有人塞了个假 role 头
        AdminPrincipal p = identity.require(req);
        assertEquals(7L, p.uid());
        assertEquals(AdminRoles.OPERATOR, p.role(), "头里的 role 一律不采信");
    }

    @Test
    @DisplayName("角色不够是 40300：身份合法、权限不足（与 40100 分开，客户端动作不同）")
    void insufficientRoleIs40300() {
        MockHttpServletRequest req = with(token(8L, "viewer", AdminRoles.READ_ONLY, NOW));
        BizException e = assertThrows(BizException.class,
                () -> identity.require(req, AdminRoles.ADMIN, AdminRoles.OPERATOR));
        assertEquals(40300, e.getCode());
    }

    @Test
    @DisplayName("过期 40101 / 签错 40100：两种都要拒，但报的码不一样（客户端动作不同）")
    void expiredAndBadSignatureRejected() {
        long stale = NOW - 7200;
        BizException expired = assertThrows(BizException.class,
                () -> identity.require(with(token(9L, "admin", AdminRoles.ADMIN, stale))));
        assertEquals(40101, expired.getCode(), "过期要报 40101，让客户端静默重登而不是喊"无权限"");
        MockHttpServletRequest forged = with(token(9L, "admin", AdminRoles.ADMIN, NOW) + "x");
        BizException e = assertThrows(BizException.class, () -> identity.require(forged));
        assertEquals(40100, e.getCode());
    }

    @Test
    @DisplayName("密钥为空时构造即失败：宁可不服务，也不接受一枚谁都能伪造的身份")
    void blankSecretFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AdminRequestIdentity("  ", Duration.ofSeconds(30)));
        assertEquals(true, e.getMessage().contains("ADMIN_JWT_SECRET"));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=AdminRequestIdentityTest`
Expected: 编译失败（`AdminRequestIdentity` 不存在）。这是红。

- [x] **Step 3: 实现 `AdminRequestIdentity`**

```java
package com.example.marketing.common.security;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;

/**
 * 业务服务侧的后台调用者识别：**只认签名 token**。
 *
 * <p>为什么不读 `X-Admin-*` 头就够了（母版 §6.0 原本这么写）：那套头的可信度来自
 * "网关先删后写"，只对经网关的请求成立。而 ③ 把这些端口变成了改预算/改库存的写入口，
 * FULL 进程形态下 seckill 监听 `*:8084`、LITE 的 standalone 8085 按设计发布到宿主机 ——
 * 裸头等于"局域网里谁都能冒充 admin"。</p>
 *
 * <p>网关把它已经验过的那枚 token 透传成 `X-Admin-Token`（`Authorization` 照旧剥掉），
 * 本件用 admin 签 token 时同一个 `AdminTokenCodec` 无状态验签。吊销位与整号作废时刻
 * 仍只在网关与 admin 判：要在业务侧也判，就得给四个进程各装一套 Redis 回退，
 * 那是第五份等值实现。代价写进段内 spec §9.1 的已知边界。</p>
 */
public class AdminRequestIdentity {

    public static final String TOKEN_HEADER = "X-Admin-Token";

    private final AdminTokenCodec codec;

    public AdminRequestIdentity(String secret, Duration skew) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "ADMIN_JWT_SECRET 未配置：后台写端点宁可不服务，也不接受一枚谁都能伪造的身份头");
        }
        this.codec = new AdminTokenCodec(secret, skew);
    }

    /** 不传 allowedRoles 表示"任意后台角色"；传了则必须命中其一 */
    public AdminPrincipal require(HttpServletRequest request, String... allowedRoles) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.trim().isEmpty()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "缺少后台凭证");
        }
        TokenVerifyResult result = codec.verify(token.trim(), Instant.now().getEpochSecond());
        if (result.status() != TokenVerifyResult.Status.OK) {
            throw switch (result.status()) {
                case EXPIRED -> BizException.of(ErrorCode.TOKEN_EXPIRED);
                case BAD_SIGNATURE, MALFORMED -> BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效");
                case OK -> new IllegalStateException("unreachable");
            };
        }
        AdminClaims claims = result.claims();
        AdminPrincipal principal =
                new AdminPrincipal(claims.uid(), claims.sub(), claims.role(), claims.jti());
        if (allowedRoles.length > 0 && !principal.hasRole(allowedRoles)) {
            throw BizException.of(ErrorCode.FORBIDDEN,
                    "需要角色 " + String.join("/", allowedRoles) + "，当前 " + principal.role());
        }
        return principal;
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=AdminRequestIdentityTest`
Expected: PASS（5 个用例）。

变异检查三处，逐条做完改回来：
1. `require` 里把"缺 token 就 40100"换成"缺 token 时改读 `X-Admin-Role` 头" → `bareHeadersAreNotCredentials` 必须红；
2. `new AdminPrincipal(claims...)` 改成 `new AdminPrincipal(..., request.getHeader("X-Admin-Role"), ...)` → `roleComesFromClaimsNotHeaders` 必须红；
3. 空密钥守卫删掉 → `blankSecretFailsFast` 必须红。

- [x] **Step 5: 写失败测试 —— 审计载荷往返与"读侧永不抛"**

`AuditPayloadCodecTest.java`。载荷必须与 `admin_audit_log` 的列一一对应，否则 drain 落表时要猜字段。
`before`/`after` 不单独开字段：admin 现有的记法是把它们拼进 `requestSummary`
（`from=..., to=..., form=..., seq=...`，见 `AdminConfigService.java:184-186`），本件沿用，
免得同一张表出现两种"改了什么"的存法。

```java
package com.example.marketing.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPayloadCodecTest {

    private static final AuditPayload SAMPLE = new AuditPayload(
            1L, "admin", "admin", "activity.budget.set", "activity", "ACT2026001",
            "PUT", "/api/admin/activities/ACT2026001/budget",
            "from=70.00, to=80.00, version=3", 0, "", "127.0.0.1", 12L, 1_800_000_000L);

    @Test
    @DisplayName("写出读回逐字段相等（drain 侧要靠它落 admin_audit_log，字段错位=静默丢信息）")
    void roundTripsEveryField() {
        AuditPayload back = AuditPayloadCodec.read(AuditPayloadCodec.write(SAMPLE));
        assertEquals(SAMPLE, back);
    }

    @Test
    @DisplayName("坏 JSON / 空串读成 empty，不抛：审计投出去失败不能把业务写回滚掉")
    void readNeverThrows() {
        assertTrue(AuditPayloadCodec.read("{not json").isEmpty());
        assertTrue(AuditPayloadCodec.read("").isEmpty());
    }

    @Test
    @DisplayName("载荷里绝不出现原始 body：requestSummary 必须由调用方在写入前就脱敏")
    void summaryIsCarriedAsIs() {
        String json = AuditPayloadCodec.write(SAMPLE);
        assertTrue(json.contains("from=70.00"), json);
        assertTrue(!json.contains("\"body\""), "不许另开一个未脱敏的 body 字段: " + json);
    }
}
```

Run: `mvn -q -pl marketing-common -am test -Dtest=AuditPayloadCodecTest`
Expected: 编译失败。

- [x] **Step 6: 实现载荷与编解码**

`AuditPayload.java`：

```java
package com.example.marketing.common.audit;

/**
 * 一条审计的跨进程载荷。字段与 admin_audit_log 的列一一对应。
 *
 * <p>为什么业务侧要自己造载荷（段内 spec §4.1）：before/after 只有真正做完那次写的进程知道，
 * 而表的所有权在 marketing-admin。所以业务侧投 Redis Stream，admin 定时 drain 落表 ——
 * 表不下放，事实也不转移。</p>
 *
 * @param requestSummary 必须已过 `RequestSummary` 脱敏，禁止传原始 body
 * @param epochSecond    业务侧完成动作的时刻（不是 drain 落表的时刻）
 */
public record AuditPayload(
        Long actorId,
        String actorName,
        String role,
        String action,
        String resourceType,
        String resourceId,
        String method,
        String path,
        String requestSummary,
        int resultCode,
        String errorMsg,
        String ip,
        long costMs,
        long epochSecond) {
}
```

`AuditPayloadCodec.java`（读侧永不抛的纪律与 ⑤ 的 `ConfigSnapshotCodec` 一致，理由也一致：
一次脏数据不能让整条链路抛异常）：

```java
package com.example.marketing.common.audit;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Optional;

/** Redis Stream 字段名固定为 `payload`，值是一段 JSON。 */
public final class AuditPayloadCodec {

    public static final String FIELD = "payload";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    public static String write(AuditPayload p) {
        return JsonUtils.toJson(p);
    }

    /** 读侧永不抛：形状不对就当没有这条，由调用方计数并告警 */
    public static Optional<AuditPayload> read(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = JsonUtils.parse(json, MAP);
            return Optional.of(new AuditPayload(
                    longOrNull(m.get("actorId")), str(m.get("actorName")), str(m.get("role")),
                    str(m.get("action")), str(m.get("resourceType")), str(m.get("resourceId")),
                    str(m.get("method")), str(m.get("path")), str(m.get("requestSummary")),
                    intOrZero(m.get("resultCode")), str(m.get("errorMsg")), str(m.get("ip")),
                    longOrZero(m.get("costMs")), longOrZero(m.get("epochSecond"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Long longOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static long longOrZero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOrZero(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private AuditPayloadCodec() {
    }
}
```

`StreamKeys.java`（本段所有 Redis 键名的唯一出处）：

```java
package com.example.marketing.common.transport;

/**
 * ③ 的两条跨进程 Stream。
 *
 * <p>用 Stream 而不是 ⑤ §4 候选的 `LPUSH + LTRIM + TTL`：TTL 淘汰等于**静默丢审计**，
 * 而"静默不一致是本仓库最贵的一类 bug"正是⑤立下的口径。Stream 的 `XLEN` 与 PEL 能被 ④
 * 直接报成 pending 数，Redis 被清空也看得见。</p>
 */
public final class StreamKeys {

    /** 上限：admin 长时间不消费时丢最旧的，而不是把 Redis 撑爆 */
    public static final int MAX_LEN = 100_000;
    public static final String ADMIN_DRAIN_GROUP = "admin-drain";
    public static final String OWNING_CONSUMER_GROUP = "owning-exec";

    public static String auditPending() {
        return "mkt:audit:pending";
    }

    public static String reheatPending() {
        return "mkt:reheat:pending";
    }

    public static String reheatAck() {
        return "mkt:reheat:ack";
    }

    private StreamKeys() {
    }
}
```

- [x] **Step 7: 跑测试确认通过 + 变异检查**

Run: `mvn -q -pl marketing-common -am test -Dtest=AuditPayloadCodecTest`
Expected: PASS（3 个用例）。

变异检查：把 `read` 里某个字段错位（`actorName` 读成 `role`）→ `roundTripsEveryField` 必须红；
把 `read` 改成直接抛 → `readNeverThrows` 必须红。

- [x] **Step 8: 写失败测试 —— 装配矩阵（Servlet 装、密钥空不炸上下文）**

`AdminSecurityAutoConfigurationTest.java`，手法沿用 ⑤ 的 `ConfigCommonAutoConfigurationTest`
（`ApplicationContextRunner`，不连中间件）：

```java
package com.example.marketing.common.config;

import com.example.marketing.common.security.AdminRequestIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 装配档位：只条件于 Servlet。业务四模块与 standalone 有它，网关（WebFlux）不该有它，
 * 且它与 DataSource 无关——⑤ 的教训是"塞进带 @ConditionalOnBean(DataSource) 的那个类，
 * 净结果是在最需要它的进程里不生效"。
 */
class AdminSecurityAutoConfigurationTest {

    @Test
    @DisplayName("Servlet 环境 + 有密钥 → 装出 AdminRequestIdentity")
    void servletContextWithSecretRegistersBean() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
                .withPropertyValues("marketing.admin.token-secret=test-secret")
                .run(ctx -> assertNotNull(ctx.getBean(AdminRequestIdentity.class)));
    }

    @Test
    @DisplayName("缺密钥 → 该 Bean 构造期就失败，不做免鉴权降级")
    void missingSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
                .run(ctx -> {
                    if (ctx.getStartupFailure() != null) {
                        throw new IllegalStateException(ctx.getStartupFailure().getMessage());
                    }
                    ctx.getBean(AdminRequestIdentity.class);
                }));
    }

    @Test
    @DisplayName("非 Web 上下文（纯单测跑的 ApplicationContextRunner）不装它")
    void plainContextSkipsIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
                .withPropertyValues("marketing.admin.token-secret=test-secret")
                .run(ctx -> org.junit.jupiter.api.Assertions.assertFalse(
                        ctx.getBeanNamesForType(AdminRequestIdentity.class).length > 0));
    }
}
```

Run: `mvn -q -pl marketing-common -am test -Dtest=AdminSecurityAutoConfigurationTest`
Expected: 编译失败（`AdminSecurityAutoConfiguration` 不存在）。

- [x] **Step 9: 实现装配并登记**

```java
package com.example.marketing.common.config;

import com.example.marketing.common.security.AdminRequestIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 后台身份件（③）。
 *
 * <p>属性写"属性 &gt; 环境变量"的嵌套占位，与 ⑤ 的 `marketing.config.form` 同一手法：
 * 六个进程共享一份定义，不在各 application.yml 里复制 `marketing.admin.*` 那段
 * （本仓库曾专门消灭过这类等值副本）。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AdminRequestIdentity adminRequestIdentity(
            @Value("${marketing.admin.token-secret:${ADMIN_JWT_SECRET:}}") String secret,
            @Value("${marketing.admin.token-clock-skew-seconds:30}") long skewSeconds) {
        return new AdminRequestIdentity(secret, Duration.ofSeconds(skewSeconds));
    }
}
```

登记（一行）：

```
com.example.marketing.common.config.AdminSecurityAutoConfiguration
```

- [x] **Step 10: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common,marketing-admin -am test`
Expected: PASS。`marketing-admin` 必须一起跑 —— Step 1 挪走了它的两个类，编译能过才算改干净。

- [x] **Step 11: 提交**

```bash
git add marketing-common marketing-admin
git commit -m "$(cat <<'EOF'
refactor(common): 后台身份件进 common 并改成验签式，审计载荷与 Stream 键名立契约

AdminPrincipal/AdminRoles 从 admin 移入 common（网关+admin+四业务共 6 处唯一出处）；
新增 AdminRequestIdentity：业务侧只认 X-Admin-Token 的签名，不采信裸 X-Admin-* 头
（FULL 进程形态业务端口监听 *:808x、LITE 的 standalone 8085 发布到宿主机，裸头等于
局域网里谁能改预算）。缺密钥构造期即失败，不做免鉴权降级。
另立 AuditPayload/Codec（字段与 admin_audit_log 一一对应，读侧永不抛）与 StreamKeys。
EOF
)"
```

---

### Task 2: 网关 —— 四条新前缀的路由、限流与 token 透传

**Files:**
- Modify: `marketing-gateway/src/main/resources/application.yml`（local 段 `:26-79`、nacos 段 `:112-128`）
- Modify: `marketing-gateway/src/main/java/com/example/marketing/gateway/filter/AdminAuthFilter.java`（remove 列表 + set）
- Modify: `marketing-gateway/src/main/java/com/example/marketing/gateway/config/GatewayConfigDefinitions.java`
  （**九条自述**：⑤ 的防漂移测试会让 yml 与声明清单任一侧漂移都红，见修正 #6）
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/filter/AdminAuthFilterTest.java`（加 2 条）
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/config/GatewayAdminRoutesTest.java`（新建，读 yml 断言）

**Interfaces:**
- Consumes: `AdminAuthFilter` 已有的 `pass(...)`（`:127-137` 那串 headers 操作）。
- Produces: `/api/admin/activities/**`、`/api/admin/coupon/**`、`/api/admin/discount/**`、`/api/admin/seckill/**` 四条路由 + 同名四个限流 map 项；`X-Admin-Token` 头透传给下游。Task 3-6 的 controller 依赖这两件事都成立。

- [x] **Step 1: 写失败测试 —— yml 里四前缀都在两套 profile 出现，且都进了限流 map**

网关最容易漏的两件事：只改了 local 忘了 nacos（表现为"注册中心形态下 404"），以及新路由
不进 `rate-limit` map（表现为**完全不限流**，且不报错，见 README 事实 #3）。所以这条断言直接读
`application.yml` 原文 —— 会漏的恰好是 yml 那一侧，用 java 常量断言等于自证。

```java
package com.example.marketing.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ③ 新增的四条后台前缀：local 与 nacos 两套 profile 都要有路由，且每条都要有 rate-limit 项。
 */
class GatewayAdminRoutesTest {

    private static final List<String> IDS = List.of(
            "admin-activity-route", "admin-coupon-route", "admin-discount-route", "admin-seckill-route");
    private static final List<String> PATHS = List.of(
            "/api/admin/activities", "/api/admin/coupon", "/api/admin/discount", "/api/admin/seckill");

    private static final String YML = readYml();

    private static String readYml() {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("四条前缀在 local 与 nacos 两段都配了（每段各出现一次 = 共两次）")
    void everyPrefixRoutedInBothProfiles() {
        for (String path : PATHS) {
            assertTrue(count(YML, "Path=" + path + "/**") >= 2,
                    path + " 的路由少于两处（local 与 nacos 各一处），注册中心形态会 404");
        }
    }

    @Test
    @DisplayName("四个 route id 都进了 rate-limit map：不在 map 里=完全不限流且不报错")
    void everyNewRouteHasRateLimit() {
        for (String id : IDS) {
            assertTrue(count(YML, id + ":") >= 1, id + " 没进 marketing.gateway.rate-limit map");
        }
    }

    @Test
    @DisplayName("四条新路由都排在 admin-route 之前：网关按声明顺序取第一个命中")
    void newRoutesDeclaredBeforeCatchAllAdminRoute() {
        int admin = YML.indexOf("- id: admin-route");
        assertTrue(admin > 0, "现有的 admin-route（/api/admin/** 通配）必须还在");
        for (String id : IDS) {
            int at = YML.indexOf("- id: " + id);
            assertTrue(at > 0 && at < admin,
                    id + " 必须声明在 admin-route 之前，否则会被 /api/admin/** 整片吞掉");
        }
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=GatewayAdminRoutesTest`
Expected: 三条都 FAIL（前缀 0 次、id 0 次、`-1 < admin`）。

- [x] **Step 2: 加路由与限流（两段都要改）**

local 段（`spring.cloud.gateway.server.webflux.routes` 下，紧接现有 `admin-route` 之后）四条：

```yaml
        - id: admin-activity-route
          uri: http://${ACTIVITY_HOST:127.0.0.1}:${ACTIVITY_PORT:8081}
          predicates:
            - Path=/api/admin/activities
            - Path=/api/admin/activities/**
        - id: admin-coupon-route
          uri: http://${COUPON_HOST:127.0.0.1}:${COUPON_PORT:8082}
          predicates:
            - Path=/api/admin/coupon
            - Path=/api/admin/coupon/**
        - id: admin-discount-route
          uri: http://${DISCOUNT_HOST:127.0.0.1}:${DISCOUNT_PORT:8083}
          predicates:
            - Path=/api/admin/discount
            - Path=/api/admin/discount/**
        - id: admin-seckill-route
          uri: http://${SECKILL_HOST:127.0.0.1}:${SECKILL_PORT:8084}
          predicates:
            - Path=/api/admin/seckill
            - Path=/api/admin/seckill/**
```

nacos 段同样四条，只把 `uri` 换成 `lb://marketing-activity` 等。

**两条硬规矩**（照现有 `admin-route` 的写法抄，别自创）：
1. **不加 `StripPrefix`**：全文件现在 0 处 `StripPrefix`（已核实），业务侧 controller 直接以
   `/api/admin/...` 全路径映射。加了会把下游路径改掉，表现为"网关 200 但下游 404"。
2. 四条的**声明顺序必须早于** `activity-route`/`coupon-route` 等 C 端路由吗？不需要：
   C 端路由的 Path 是 `/api/activity/**` 等，与 `/api/admin/**` 不重叠。但
   `admin-route`（`/api/admin/**`）会吞掉这四条 —— 所以**四条必须排在 `admin-route` 之前**，
   并在 yml 里留一行注释说明这一点。这是本任务最容易踩的坑：Spring Cloud Gateway
   按声明顺序取第一个命中的路由。

限流 map（`marketing.gateway.rate-limit` 下）四项，初值与 `admin-route` 同为 50：

```yaml
      admin-activity-route: ${RL_ADMIN_ACTIVITY:50}
      admin-coupon-route: ${RL_ADMIN_COUPON:50}
      admin-discount-route: ${RL_ADMIN_DISCOUNT:50}
      admin-seckill-route: ${RL_ADMIN_SECKILL:50}
```

> 键名必须与路由 `id` 逐字一致：`RateRuleResolver` 是按 routeId 拼
> `gateway.ratelimit.<route>.limit` 与查 map 的（`GatewayConfigDefinitions.keyOf`），
> 拼错的表现是"这条路由完全不限流"，而不是报错。

- [x] **Step 3: 跑测试确认通过**

Run: `mvn -q -pl marketing-gateway -am test -Dtest=GatewayAdminRoutesTest`
Expected: PASS。

变异检查：把 nacos 段里 `admin-seckill-route` 那条删掉 → 断言必须红（"只在 1 个 profile 里配了"）。

- [x] **Step 4: 写失败测试 —— 网关透传 X-Admin-Token，且客户端自带的被删**

沿用 `AdminAuthFilterTest` 已有的夹具风格：该文件用 `ArgumentCaptor<ServerWebExchange> captured`
+ `verify(chain).filter(captured.capture())`，再从 `captured.getValue().getRequest().getHeaders()`
读下游收到的头（见 `:109-128`）。**不要另造一套 chain 捕获器。**

```java
    @Test
    @DisplayName("下游收到 X-Admin-Token：业务侧要靠它验签，裸身份头对直连端口不设防")
    void forwardsVerifiedTokenToDownstream() {
        String bearer = token(1L, "admin", "admin", "jti-1");
        filter.filter(adminGet("Bearer " + bearer), chain).block(Duration.ofSeconds(5));
        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertEquals(bearer, headers.getFirst("X-Admin-Token"));
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION), "原始 bearer 仍然要剥掉");
    }

    @Test
    @DisplayName("客户端自带的 X-Admin-Token 被删掉：只能有网关注入的那一枚")
    void stripsClientSuppliedTokenHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/activities/ACT2026001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(1L, "admin", "admin", "jti-1"))
                        .header("X-Admin-Token", "attacker-minted"));
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertEquals(1, headers.get("X-Admin-Token").size(), "不能把客户端注入的那一枚留在列表里");
        assertNotEquals("attacker-minted", headers.getFirst("X-Admin-Token"));
    }

    @Test
    @DisplayName("登录口（claims 为 null）不给 X-Admin-Token：未登录也不该带着一枚假凭证到下游")
    void loginRouteGetsNoTokenHeader() {
        filter.filter( MockServerWebExchange.from(MockServerHttpRequest.post("/api/admin/auth/login")), chain)
                .block(Duration.ofSeconds(5));
        verify(chain).filter(captured.capture());
        assertNull(captured.getValue().getRequest().getHeaders().getFirst("X-Admin-Token"));
    }
```

> 三条都假设该文件已有 `adminGet(...)`、`token(...)`、`chain`、`captured` 四个夹具（已核实存在于
> `:73-109`）与 `HttpHeaders`/`assertNull`/`assertNotEquals` 的 import；缺哪个补哪个 import 即可。
> 登录口那条要看清 `isPermitted(path)` 实际放行的路径名（`/api/admin/auth/login`），
> 与该文件的既有用例保持一致，别新造路径。

Run: `mvn -q -pl marketing-gateway -am test -Dtest=AdminAuthFilterTest`
Expected: 前两条 FAIL（下游没有 `X-Admin-Token`）。

- [x] **Step 5: 实现透传**

`pass(ServerWebExchange, AdminClaims)`（`:124-142`）**签名不用改**：它拿到的 `exchange` 还是
 mutation 之前那个，所以里面直接复用 `tokenOf(exchange)`（`:163-167` 那个已有的 Bearer 解析 helper，
**不要复制第二份拆解逻辑**）：

```java
    private ServerWebExchange pass(ServerWebExchange exchange, AdminClaims claims) {
        exchange.getAttributes().put(VERIFIED, Boolean.TRUE);
        String token = tokenOf(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove("X-Admin-User");
                    headers.remove("X-Admin-Role");
                    headers.remove("X-Admin-Jti");
                    headers.remove("X-Admin-Uid");
                    headers.remove("X-Admin-Token");
                    if (claims != null) {
                        headers.set("X-Admin-User", claims.sub());
                        headers.set("X-Admin-Role", claims.role());
                        headers.set("X-Admin-Jti", claims.jti());
                        headers.set("X-Admin-Uid", String.valueOf(claims.uid()));
                        // ③：业务服务只认这枚签名（裸身份头对直连 808x 的人不设防）。
                        // 网关已验签名/有效期/吊销与整号作废，下游用同一个 codec 再验一次即可。
                        headers.set("X-Admin-Token", token);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }
```

`pass` 的类注释（`:119-123`）补一句：token 仍在，但换成了 `X-Admin-Token`，
`Authorization` 照旧剥掉 —— 下游不需要区分 bearer 形状，C 端服务也看不到原始凭证。

- [x] **Step 6: 跑测试确认通过 + 变异检查**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test`
Expected: PASS（含 ⑤ 原有的 28 个用例不退步）。

变异检查：把 `headers.remove("X-Admin-Token")` 那行删掉 → `stripsClientSuppliedTokenHeader` 必须红。

- [x] **Step 7: 提交**

```bash
git add marketing-gateway
git commit -m "$(cat <<'EOF'
feat(gateway): ③ 四条后台前缀路由（两套 profile）+ 限流项，并透传已验签的 X-Admin-Token

四条新路由必须排在 admin-route 之前（网关按声明顺序取第一个命中，否则全被 /api/admin/** 吞掉），
且每条都要进 rate-limit map（不在 map 里=完全不限流，且不报错）。
token 透传是③身份模型的支点：业务侧只认签名，不认裸 X-Admin-* 头。
EOF
)"
```

---

### Task 3: activity —— 创建/流转搬家 + 预算与灰度编辑（地雷 A 的正面防守）

**Files:**
- Create: `marketing-activity/src/main/java/com/example/marketing/activity/controller/ActivityAdminController.java`
- Create: `marketing-activity/src/main/java/com/example/marketing/activity/dto/ActivityView.java`
- Create: `marketing-activity/src/main/java/com/example/marketing/activity/dto/BudgetUpdateRequest.java`
- Create: `marketing-activity/src/main/java/com/example/marketing/activity/dto/GrayUpdateRequest.java`
- Modify: `marketing-activity/src/main/java/com/example/marketing/activity/service/ActivityService.java`
- Modify: `marketing-activity/src/main/java/com/example/marketing/activity/controller/ActivityController.java`（删 `create`、`transition` 两个方法与不再用到的 import）
- Modify: `scripts/smoke-test.sh`（链路 0 的创建与流转两处换路径换 token）
- Test: `marketing-activity/src/test/java/com/example/marketing/activity/service/ActivityAdminWriteTest.java`
- Test: `marketing-activity/src/test/java/com/example/marketing/activity/controller/ActivityAdminControllerTest.java`

**Interfaces:**
- Consumes: T1 的 `AdminRequestIdentity.require(HttpServletRequest, String...)`、`AuditOutbox.record(AuditPayload)`、`AuditPayload`、`AdminRoles.ADMIN`；已有的 `BudgetService implements CacheReheater`（`reheat(String, boolean)` → `CacheReheater.Result(String type, String key, long before, long after, String formula)`）；`PageQuery.of(Integer, Integer)`（`@Data`，取页码用 `getPage()`/`getSize()`，不是 record 访问器）/
`PageResult.of(long, int, int, List)` / `PageResult.map(Function)` / `Result.ok(T)`。
- Produces: `ActivityService.updateBudget(String, BigDecimal, Integer)`、`updateGray(String, Integer, String, Integer)`、`list(PageQuery, String status)`、`transition` 的冲突码改成 `41008`。T7 的 drain 靠这些端点产生的 `AuditPayload` 才有内容可落。

**已核实的现状**（写代码前要知道的三件事）：
1. `ActivityEntity.version` 是 `Integer` 且带 `@Version`（`ActivityEntity.java:40-41`），
   `MybatisPlusConfig` 里 `OptimisticLockerInnerInterceptor` 与 `PaginationInnerInterceptor` 都在；
2. `ActivityService.transition` 现在的乐观锁失败抛的是 `ErrorCode.BIZ_ERROR`（41000，`:50-53`），
   本任务把它迁到 `41008`，与新的字段编辑同码 —— `41000` 继续只表示业务失败（⑤ T9 立的口径）；
3. 灰度**不需要** reheat：`GrayService` 走 `GrayRuleCache` 每 5s 回源 DB（⑤ 偏离 #3），
   所以 `updateGray` 只写 DB 就是完整语义。这条要在代码注释里写明，否则下一个人会以为漏了刷新。

- [x] **Step 1: 写失败测试 —— 改预算不刷缓存就是没改**

`ActivityAdminWriteTest.java`（H2 `MODE=MySQL` + Mockito，手法沿用 `ActivityServiceTest`）。
这个文件里最值钱的断言是 `verify(budgetService).reheat(no, true)` —— 把实现里那一行注释掉，
这条必须红。这是地雷 A 唯一的回归锚。

```java
package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台写路径的地雷 A 防守：改了 budget_amount 而不 force 重建预算键，
 * warmIfAbsent 是 SETNX，旧键永远还在（ActivityService.java:56 的既有语义）。
 */
class ActivityAdminWriteTest {

    private final ActivityMapper mapper = mock(ActivityMapper.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final ActivityService service = new ActivityService(mapper, budgetService);

    private ActivityEntity existing(String no, String budget, int version) {
        ActivityEntity e = new ActivityEntity();
        e.setActivityNo(no);
        e.setBudgetAmount(new BigDecimal(budget));
        e.setVersion(version);
        return e;
    }

    @Test
    @DisplayName("改预算必须 force 重预热预算键（SETNX 语义下不删键=改动永不生效）")
    void budgetUpdateForcesReheat() {
        when(mapper.selectOne(any())).thenReturn(existing("ACT9001", "100.00", 3));
        when(mapper.updateById(any())).thenReturn(1);
        when(budgetService.reheat(eq("ACT9001"), eq(true)))
                .thenReturn(new CacheReheater.Result("budget", "ACT9001", 10000L, 8000L, "对账口径"));

        service.updateBudget("ACT9001", new BigDecimal("80.00"), 3);

        verify(budgetService).reheat("ACT9001", true);
    }

    @Test
    @DisplayName("客户端 version 与库里不一致时 41008，且不写库、不刷缓存")
    void staleVersionRejectedBeforeWrite() {
        when(mapper.selectOne(any())).thenReturn(existing("ACT9001", "100.00", 5));
        BizException e = assertThrows(BizException.class,
                () -> service.updateBudget("ACT9001", new BigDecimal("80.00"), 3));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), e.getCode());
        verify(mapper, never()).updateById(any());
        verify(budgetService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("updateById 返回 0（真并发）同样是 41008，不再是 41000")
    void concurrentUpdateMapsTo41008() {
        when(mapper.selectOne(any())).thenReturn(existing("ACT9001", "100.00", 3));
        when(mapper.updateById(any())).thenReturn(0);
        BizException e = assertThrows(BizException.class,
                () -> service.updateBudget("ACT9001", new BigDecimal("80.00"), 3));
        assertEquals(ErrorCode.CONFIG_VERSION_CONFLICT.getCode(), e.getCode());
    }

    @Test
    @DisplayName("改灰度不刷预算键：灰度真值每 5s 回源 DB，刷新是 GrayRuleCache 的事")
    void grayUpdateDoesNotTouchBudgetCache() {
        when(mapper.selectOne(any())).thenReturn(existing("ACT9001", "100.00", 1));
        when(mapper.updateById(any())).thenReturn(1);
        service.updateGray("ACT9001", 5, "70001,70002", 1);
        verify(budgetService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("灰度 null 与越界：null=未配灰度=全量放行（GrayService 既有语义），越界直接拒")
    void grayPercentBounds() {
        when(mapper.selectOne(any())).thenReturn(existing("ACT9001", "100.00", 1));
        assertThrows(BizException.class, () -> service.updateGray("ACT9001", 130, null, 1));
        assertThrows(BizException.class, () -> service.updateGray("ACT9001", -1, null, 1));
    }
}
```

> `BizException.getCode()` 返回 int（⑤ 的 `GlobalExceptionHandler` 就这么用），
> 所以断言直接和 `ErrorCode.CONFIG_VERSION_CONFLICT.getCode()` 比，不要写字符串比较。

Run: `source scripts/common.sh && mvn -q -pl marketing-activity -am test -Dtest=ActivityAdminWriteTest`
Expected: 编译失败（`updateBudget`/`updateGray` 不存在）。

- [x] **Step 2: 实现两个写方法与冲突码迁移**

`ActivityService` 追加（放在 `transition` 之后、`getByNo` 之前）：

```java
    /**
     * 后台改总预算。<b>必须在同一事务里 force 重预热预算键</b>：
     * 上线预热走的是 SETNX（{@code warmIfAbsent}），键已存在时改 DB 不动键，
     * 于是"改了预算但 C 端余额还是旧的"（地雷 A，母版 §6.3）。
     *
     * @param expectedVersion 前端列表里带回去的 version；与库里不一致说明有人先改了，41008
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity updateBudget(String activityNo, BigDecimal budgetAmount, Integer expectedVersion) {
        ActivityEntity entity = getByNo(activityNo);
        requireVersion(entity, expectedVersion);
        BigDecimal before = entity.getBudgetAmount();
        entity.setBudgetAmount(budgetAmount);
        flushWithVersion(entity);
        budgetService.reheat(activityNo, true);
        log.info("[activity] 预算变更 {} {} -> {}", activityNo, before, budgetAmount);
        return entity;
    }

    /**
     * 后台改灰度。不需要 reheat：灰度真值就是这两列，owning 侧 `GrayRuleCache` 每 5s 回源 DB
     * 重建（⑤ 段内 spec §3 偏离 #3），所以写库即完整语义 —— 别在这里加一次"顺手刷缓存"，
     * 那会让人以为不刷就不生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity updateGray(String activityNo, Integer grayPercent, String grayWhitelist,
                                     Integer expectedVersion) {
        if (grayPercent != null && (grayPercent < 0 || grayPercent > 100)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "灰度百分比必须在 0-100，当前 " + grayPercent);
        }
        ActivityEntity entity = getByNo(activityNo);
        requireVersion(entity, expectedVersion);
        entity.setGrayPercent(grayPercent);
        entity.setGrayWhitelist(grayWhitelist);
        flushWithVersion(entity);
        log.info("[activity] 灰度变更 {} percent={} whitelist={}", activityNo, grayPercent, grayWhitelist);
        return entity;
    }

    /** 后台列表：只读，状态可选过滤 */
    public PageResult<ActivityView> list(PageQuery query, String status) {
        Page<ActivityEntity> page = activityMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                Wrappers.<ActivityEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), ActivityEntity::getStatus, status)
                        .orderByDesc(ActivityEntity::getId));
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream().map(ActivityView::from).toList());
    }

    private void requireVersion(ActivityEntity entity, Integer expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(entity.getVersion())) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "活动已被他人修改（你看到的是 version=" + expectedVersion
                            + "，当前 " + entity.getVersion() + "），请刷新后重试");
        }
    }

    private void flushWithVersion(ActivityEntity entity) {
        if (activityMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "数据已被他人修改，请刷新后重试");
        }
    }
```

同时把 `transition` 里那句 `new BizException(ErrorCode.BIZ_ERROR, "并发更新冲突，请重试")`
改成 `throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "状态已被他人变更，请刷新后重试")`
—— 同一件事在同一个类里不能有两个码（`41000` 继续只表示业务失败）。

新增 import：`com.example.marketing.common.api.PageQuery`、`PageResult`、
`com.example.marketing.activity.dto.ActivityView`、`com.baomidou.mybatisplus.extension.plugins.pagination.Page`。

`ActivityView` / 两个请求体：

```java
package com.example.marketing.activity.dto;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 后台列表用的活动视图。
 *
 * <p>VO 只在 admin 侧强制（母版 §6.2）：C 端 `GET /api/activity/{no}` 仍返回实体，
 * 免得把基线断言卷进无关改动。`version` 必须带出去 —— 前端下一次编辑要拿它做乐观锁的期望值。</p>
 */
public record ActivityView(
        String activityNo, String name, String status, BigDecimal budgetAmount, BigDecimal usedAmount,
        Integer grayPercent, String grayWhitelist, Integer version,
        LocalDateTime startTime, LocalDateTime endTime) {

    public static ActivityView from(ActivityEntity e) {
        return new ActivityView(e.getActivityNo(), e.getName(), e.getStatus(), e.getBudgetAmount(),
                e.getUsedAmount(), e.getGrayPercent(), e.getGrayWhitelist(), e.getVersion(),
                e.getStartTime(), e.getEndTime());
    }
}
```

```java
package com.example.marketing.activity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** @param version 列表里带回来的乐观锁版本，不传等于"我不在乎被别人改过" —— 所以必填 */
public record BudgetUpdateRequest(
        @NotNull @DecimalMin(value = "0.00", message = "预算不能为负") BigDecimal budgetAmount,
        @NotNull(message = "version 必填（乐观锁）") Integer version,
        String remark) {
}
```

```java
package com.example.marketing.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param grayPercent null = 清除灰度（回到"未配灰度 = 全量放行"）；0-100
 * @param version     乐观锁期望版本，必填
 */
public record GrayUpdateRequest(
        @Min(value = 0, message = "灰度百分比不能为负") @Max(value = 100, message = "灰度百分比不能超过 100")
        Integer grayPercent,
        String grayWhitelist,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
```

- [x] **Step 3: 跑测试确认通过 + 变异检查**

Run: `mvn -q -pl marketing-activity -am test -Dtest=ActivityAdminWriteTest`
Expected: PASS（5 个用例）。

变异检查四处，逐条做完改回：
1. 注释掉 `budgetService.reheat(activityNo, true)` → `budgetUpdateForcesReheat` 必须红（地雷 A 的锚）；
2. `requireVersion` 改成空方法 → `staleVersionRejectedBeforeWrite` 必须红；
3. `flushWithVersion` 的 `== 0` 改成不判断 → `concurrentUpdateMapsTo41008` 必须红；
4. 把 `updateGray` 里加一句 `budgetService.reheat(activityNo, true)` → `grayUpdateDoesNotTouchBudgetCache` 必须红
   （这条防的是"到处刷缓存"这种看似安全的习惯）。

- [x] **Step 4: 写失败测试 —— controller 层的身份与审计**

`ActivityAdminControllerTest.java`：不引 MockMvc，直接 new controller + mock service（本仓库既有做法），
钉三件事：裸身份头 40100、read-only 写 40300、写成功要投一条审计且 `requestSummary` 里带 before/after。

```java
    @Test
    @DisplayName("只有裸 X-Admin-* 头时 40100：身份必须来自签名")
    void bareHeadersRejected() { /* identity mock 抛 BizException(UNAUTHORIZED)，断言 code=40100 */ }

    @Test
    @DisplayName("read-only 改预算 40300")
    void readOnlyCannotWrite() { /* 与 ⑤ 的 AdminConfigControllerTest 同法：identity.require 抛 FORBIDDEN */ }

    @Test
    @DisplayName("写成功要投审计，summary 里能看到 from/to")
    void writesAuditPayload() { /* verify(outbox).record(argThat(p -> p.requestSummary().contains("from=100.00"))) */ }
```

（三条的完整体在实现时照 `AdminConfigControllerTest` 的写法补全 —— 那个文件已经立好了
"identity 抛 → 端点原样抛、审计仍落"的形状。**不要**在本任务里发明第二套测试夹具。）

- [x] **Step 5: 实现 controller**

```java
package com.example.marketing.activity.controller;

import com.example.marketing.activity.dto.ActivityView;
import com.example.marketing.activity.dto.BudgetUpdateRequest;
import com.example.marketing.activity.dto.CreateActivityRequest;
import com.example.marketing.activity.dto.GrayUpdateRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动管理面（③）。路径在 `/api/admin/**` 下但**由 activity 进程自己发布**：
 * 预算写与它必须的 force 重预热不能跨进程拆开（母版 §6.0/§6.3）。
 *
 * <p>身份只认签名 token（`X-Admin-Token`），不认裸 `X-Admin-Role`：本服务在 FULL 进程形态
 * 监听 `*:8081`，把裸头当授权等于局域网里谁都能改预算。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/activities")
@RequiredArgsConstructor
public class ActivityAdminController {

    private final ActivityService activityService;
    private final AdminRequestIdentity identity;
    private final AuditOutbox outbox;

    @GetMapping
    public Result<PageResult<ActivityView>> list(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String status,
                                                 HttpServletRequest request) {
        identity.require(request, AdminRoles.ADMIN, AdminRoles.OPERATOR, AdminRoles.READ_ONLY);
        return Result.ok(activityService.list(PageQuery.of(page, size), status));
    }

    @PostMapping
    public Result<ActivityView> create(@Valid @RequestBody CreateActivityRequest body,
                                       HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity e = activityService.create(body);
        audit(actor, "activity.create", e.getActivityNo(), "POST /api/admin/activities",
                "from=, to=budget=" + e.getBudgetAmount() + ", name=" + e.getName(), request);
        return Result.ok(ActivityView.from(e));
    }

    @PostMapping("/{activityNo}/transition")
    public Result<ActivityView> transition(@PathVariable String activityNo,
                                           @RequestParam String event,
                                           HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity before = activityService.getByNo(activityNo);
        String from = before.getStatus();
        ActivityEntity e = activityService.transition(activityNo,
                com.example.marketing.activity.domain.ActivityEvent.valueOf(event));
        audit(actor, "activity.transition", activityNo, "POST /api/admin/activities/" + activityNo + "/transition",
                "from=" + from + ", to=" + e.getStatus() + ", event=" + event, request);
        return Result.ok(ActivityView.from(e));
    }

    @PutMapping("/{activityNo}/budget")
    public Result<ActivityView> updateBudget(@PathVariable String activityNo,
                                             @Valid @RequestBody BudgetUpdateRequest body,
                                             HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity before = activityService.getByNo(activityNo);
        ActivityEntity e = activityService.updateBudget(activityNo, body.budgetAmount(), body.version());
        audit(actor, "activity.budget.set", activityNo, "PUT /api/admin/activities/" + activityNo + "/budget",
                "from=" + before.getBudgetAmount() + ", to=" + e.getBudgetAmount()
                        + ", version=" + e.getVersion(), request);
        return Result.ok(ActivityView.from(e));
    }

    @PutMapping("/{activityNo}/gray")
    public Result<ActivityView> updateGray(@PathVariable String activityNo,
                                           @Valid @RequestBody GrayUpdateRequest body,
                                           HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity before = activityService.getByNo(activityNo);
        ActivityEntity e = activityService.updateGray(activityNo, body.grayPercent(),
                body.grayWhitelist(), body.version());
        audit(actor, "activity.gray.set", activityNo, "PUT /api/admin/activities/" + activityNo + "/gray",
                "from=" + before.getGrayPercent() + ", to=" + e.getGrayPercent()
                        + ", whitelist=" + (e.getGrayWhitelist() == null ? "" : e.getGrayWhitelist())
                        + ", version=" + e.getVersion(), request);
        return Result.ok(ActivityView.from(e));
    }

    private void audit(AdminPrincipal actor, String action, String resourceId, String path,
                       String summary, HttpServletRequest request) {
        outbox.record(new AuditPayload(actor.uid(), actor.username(), actor.role(), action,
                "activity", resourceId, request.getMethod(), path, summary, 0, "",
                ClientIp.of(request), 0L, System.currentTimeMillis() / 1000));
    }
}
```

> `ClientIp` 现在是 admin 模块里的包私有类（`admin/controller/ClientIp.java`）。本任务要把它
> **提到 `common/web/ClientIp.java`**（网关之外六个 Servlet 进程都用得上，且审计的 ip 列
> 在 LITE 与 FULL 都得是真的），改 admin 那 3 处 import。它的类注释里那段"容器形态下 XFF 是
> 宿主机地址"的粒度说明要一起搬过去，别丢。

- [x] **Step 6: 删掉 C 端的两个写方法，并把冒烟的两行换掉**

`ActivityController`：删 `create`（`:38-42`）与 `transition`（`:50-55`）及其 import。
其余读与交易写不动。

`scripts/smoke-test.sh` 链路 0：

```bash
# ③：创建与流转换到后台路径、换后台 token（脚本已在链路 4 头部登录过一次）
R=$(curl -s -m 10 -X POST -H "$AAUTH" -H "$JSON" "$GW/api/admin/activities" -d '{...}')
R=$(curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=SUBMIT")
```

登录必须提前到脚本头部（现在在链路 4），否则链路 0 用不上 `$AAUTH`：
把 `head2 "链路 4：..."` 里那段 login 与 `ADM`/`ADMIN_TOKEN` 的构造整体上移到 `链路 0` 之前，
并在最后保留登出（⑤ T10 已经把登出挪到全脚本最后，那部分不动）。
`ACT_NO` 的构造与后面所有引用不变。

- [x] **Step 7: 跑本模块测试 + LITE 冒烟**

```bash
source scripts/common.sh && mvn -q -pl marketing-activity,marketing-admin -am test
./scripts/deploy-preview.sh && ./scripts/smoke-test.sh
```
Expected: 单测 PASS；冒烟 **72/72**（本任务不该改变断言条数，只换链路 0 的两条路径）。
若链路 0 红在 `40100`：登录上移没生效；红在 `41000`：`transition` 的冲突码迁移漏了。

- [x] **Step 8: 提交**

```bash
git add marketing-activity marketing-admin scripts/smoke-test.sh marketing-common
git commit -m "$(cat <<'EOF'
feat(activity): ③ 活动管理端点长在 owning 服务，预算改动同事务 force 重预热

/api/admin/activities 五条（列表/创建/流转/预算/灰度），身份只认签名 token；
预算写与 reheat(force=true) 同方法同事务（SETNX 预热下不删键=改动永不生效，地雷 A），
灰度刻意不刷缓存（真值每 5s 回源 DB）。乐观锁冲突从 41000 迁到 41008，
与新的字段编辑同码；C 端 create/transition 一并删除，冒烟链路 0 换路径。
ClientIp 提到 common（六个 Servlet 进程都要真 ip）。
EOF
)"
```

---

### Task 4: discount —— 规则读写搬家（`bumpVersion` 从 controller 挪进事务内的 service）

**形状与 Task 3 完全一致**（controller 只认签名 token、写方法带 `expectedVersion`、冲突 `41008`、
写成功投 `AuditPayload`、收尾把 C 端旧路径删掉并同步改冒烟）。这里只写差异。

**Files:**
- Create: `marketing-discount/.../controller/DiscountAdminController.java`、`.../dto/RuleView.java`
- Create: `marketing-discount/.../service/RuleAdminService.java`
- Modify: `marketing-discount/.../controller/DiscountController.java`（删 `saveRule` 与 `listRules`，把 `toDsl`/`fill` 两个私有辅助搬去 service）
- Modify: `scripts/smoke-test.sh`（链路 2 里若用到规则管理则换路径；**先 grep 确认**：`grep -n "api/discount/rules" scripts/*.sh`）
- Test: `marketing-discount/src/test/java/.../service/RuleAdminServiceTest.java`

**已核实的现状（三条，都会影响写法）**：
1. `DiscountController.saveRule`（`:48-65`）现在**在 controller 里**做 upsert 并调
   `ruleCacheManager.bumpVersion()` —— 没有 `@Transactional`，也没有乐观锁期望值：
   两个 operator 同时改一条规则，后写的静默覆盖前者。这是 ③ 要修的既有缺陷，不是新能力。
2. `RuleCacheManager` 的类注释自己写着"写路径（规则管理接口）调用 `bumpVersion()`，多实例间秒级生效"
   —— 所以 discount 的缓存失效**不是** `CacheReheater`（它没有实现，也不该为 ③ 新增实现），
   而是版本号；`bumpVersion()` 同时把 `localCheckedAt=0` 让本实例立即失效。
3. `listRules` 返回 `List<PromoRuleEntity>`（把整条 DSL JSON 原样吐给共享 demo token）。
   搬到 admin 侧后换 `PageResult<RuleView>`，`RuleView` 里带 `version` 供乐观锁回传。

- [x] **Step 1: 写失败测试**

`RuleAdminServiceTest.java` 三条（mock `PromoRuleMapper` + mock `RuleCacheManager`）：

```java
    @Test
    @DisplayName("规则写必须 bumpVersion：不推版本号，其他实例还在用旧快照")
    void saveBumpsCacheVersion() { /* verify(cacheManager).bumpVersion() */ }

    @Test
    @DisplayName("expectedVersion 与库里不一致 → 41008，且不写库、不推版本")
    void staleVersionRejected() { /* never(insert/updateById/bumpVersion) */ }

    @Test
    @DisplayName("bumpVersion 前抛异常时不留版本：写失败不推版本号")
    void versionNotBumpedWhenWriteFails() { /* updateById 抛 → assertThrows + verify(never()).bumpVersion() */ }
```

第三条是这条链路上唯一"半应用"的窗口：版本推了但 DB 没写，全实例会去重建一份**旧**规则并以为新
（回源 DB 是权威，所以后果是自愈的）—— 反过来（DB 写了没推）则是改动秒级不可见。
两者都要防，所以顺序固定为 **写库 → 推版本**，且在同一事务方法内。

- [x] **Step 2: 实现 `RuleAdminService.save`**

```java
    /**
     * 规则 upsert + 版本号推进，同一事务。顺序不能反：先推版本再写库的话，
     * 全实例会立刻去重建一份还没改好的规则（虽然回源 DB 会自愈，但窗口里算错价）。
     *
     * <p>缓存失效走版本号而不是 {@code CacheReheater}：{@code RuleCacheManager} 的读路径就是
     * 比对 Redis 版本号，给它再加一个 reheat 入口等于两套真相。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RuleView save(RuleSaveRequest request, Integer expectedVersion) {
        PromoRuleEntity existing = byRuleNo(request.getRuleNo());
        if (existing == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "规则不存在，不能带 version 新建");
            }
            PromoRuleEntity entity = new PromoRuleEntity();
            fill(entity, request, JsonUtils.toJson(toDsl(request)));
            entity.setVersion(0);
            promoRuleMapper.insert(entity);
            ruleCacheManager.bumpVersion();
            return RuleView.from(entity);
        }
        requireVersion(existing, expectedVersion);
        fill(existing, request, JsonUtils.toJson(toDsl(request)));
        if (promoRuleMapper.updateById(existing) == 0) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "规则已被他人修改，请刷新后重试");
        }
        ruleCacheManager.bumpVersion();
        return RuleView.from(existing);
    }
```

（`byRuleNo` / `requireVersion` / `fill` / `toDsl` 与 Task 3 同名同语义；`fill`/`toDsl` 从
`DiscountController` 原样搬过来，别改逻辑 —— 它们是既有的 DSL 组装口径。）

新建还要求 `expectedVersion` 缺省或 0：**没有"不存在就顺手建一条"**，否则误写 ruleNo 会静默造规则。

- [x] **Step 3-5: 列表、controller、审计**

照 Task 3 Step 4-6 的形状做，两处不同：
1. 列表要"一次读全表"换成 `PageResult`（`promoRuleMapper.selectPage`，按 `priority desc, id asc`，
   与引擎的互斥组最优口径同一排序，见 `RuleSnapshot`）；
2. 审计的 `summary` 只放**规则的关键字段**（`type`/`threshold`/`discountValue`/`priority`/`status`），
   **不要**把整条 DSL JSON 塞进去：`admin_audit_log.request_summary` 是定长列，
   而 DSL 里可能带很长的 CSV（`requiredTags`）。脱敏口径沿用 `RequestSummary`。

- [x] **Step 6: 删 C 端两条、跑测试、跑冒烟、提交**

```bash
source scripts/common.sh && mvn -q -pl marketing-discount,marketing-common -am test
./scripts/smoke-test.sh   # 栈在跑的话；期望 72/72（链路 2 不断言规则管理，通常不受影响）
git add marketing-discount scripts/ && git commit -m "feat(discount): ③ 规则管理搬到 owning 服务，bumpVersion 进事务，补乐观锁"
```

---

### Task 5: coupon —— 券模板创建/编辑（今天完全没有接口）

**Files:**
- Create: `marketing-coupon/.../controller/CouponAdminController.java`、`.../dto/TemplateCreateRequest.java`、`TemplateView.java`
- Modify: `marketing-coupon/.../service/CouponTemplateService.java`（新增 `create` / `updateStock`）
- Test: `marketing-coupon/src/test/java/.../service/CouponTemplateAdminWriteTest.java`

**已核实的现状**：`CouponTemplateService` 已是 `CacheReheater`（`type()="coupon-stock"`，
`reheat(templateNo, force)` 在 `:90`），且 `warmStock(template)`（`:54`）是 SETNX 语义 ——
**所以新建模板必须显式 warm，改库存必须 `reheat(force=true)`**，与预算那条同族。
券模板今天只能靠 `docker/mysql/init*/01-schema.sql` 种子写入，本任务是净新增能力。

- [x] **Step 1: 写失败测试（四条）**

先确认这三处事实再动笔：`CouponTemplateService.reheat` 的公式（`:90-101`）、
`warmStock` 的键名与 SETNX（`:54-60`）、`CouponTemplateEntity.version`（`Integer`，`:42` 带 `@Version`）。
夹具与 `RuleAdminServiceTest` 同构（mock `CouponTemplateMapper` 与 `StringRedisTemplate`/
`ValueOperations`，沿用本模块既有 `CouponTemplateServiceTest` 里那套 Redis 桩），四条：

```java
    @Test
    @DisplayName("新建模板必须显式预热库存键：不 warm 的话第一笔领券吃 NOT_WARMED")
    void createWarmsStock() {
        when(templateMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        service.create(request("CT9009"));
        // 断言外部可见事实而不是"自己调了自己"：同类的自调用是 Mockito 的盲点
        verify(ops).setIfAbsent(org.mockito.ArgumentMatchers.startsWith("coupon:stock:"), anyString());
    }

    @Test
    @DisplayName("改总库存必须 force 重预热：键里存的是剩余量，不 DEL 就永远旧")
    void stockUpdateForcesReheat() {
        stubTemplate("CT9009", 1000, 2);
        when(templateMapper.updateById(any())).thenReturn(1);
        service.updateTotalStock("CT9009", 500, 2);
        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.argThat(
                k -> ((String) k).startsWith("coupon:stock:")));
    }

    @Test
    @DisplayName("expectedVersion 过期 → 41008，既不写库也不删键")
    void staleVersionRejected() {
        stubTemplate("CT9009", 1000, 5);
        BizException e = assertThrows(BizException.class,
                () -> service.updateTotalStock("CT9009", 500, 2));
        assertEquals(41008, e.getCode());
        verify(templateMapper, never()).updateById(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("templateNo 重复是 41000 业务错误，不是 41008（41008 只表示并发覆盖）")
    void duplicateTemplateNoIsBizError() {
        when(templateMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);
        BizException e = assertThrows(BizException.class, () -> service.create(request("CT9009")));
        assertEquals(41000, e.getCode());
    }
```

> 第一条为什么不断言 `verify(service).warmStock(...)`：`create` 与 `warmStock` 落在同一个类里，
> 自调用绕过代理也绕不过 mock 记账的直觉都不成立 —— Mockito 不会记录"自己调自己"。
> 所以断言打在**外部可见事实**（Redis 里出现了库存键）上。不要为了好 verify 把方法拆到别的类。
> `coupon:stock:` 这个前缀以 `CouponTemplateService` 里的真实键名为准，落地前先 grep 一次，
> 别把键名猜成 `coupon:stock` 结果断言永远为真（`startsWith("")` 就是这种假绿的极端）。

第四条是码表纪律：`41008` 只表示"有人比你先改"，唯一键冲突是 `41000` —— 混用的话
④ 与后台前端会给出错误的动作建议（一个让你刷新，一个让你改编号）。

- [x] **Step 2: 实现**

```java
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateEntity create(TemplateCreateRequest request) {
        if (templateMapper.exists(Wrappers.<CouponTemplateEntity>lambdaQuery()
                .eq(CouponTemplateEntity::getTemplateNo, request.templateNo()))) {
            throw new BizException(ErrorCode.BIZ_ERROR, "券模板编号已存在: " + request.templateNo());
        }
        CouponTemplateEntity entity = new CouponTemplateEntity();
        // 字段逐条 set（含 activityNo/couponType/faceValue/thresholdAmount/validDays/
        // totalCount/perUserLimit/status），口径照 init.sql 的种子列
        entity.setVersion(0);
        templateMapper.insert(entity);
        warmStock(entity);          // 新建必须显式预热：SETNX 语义下不 warm 就是 NOT_WARMED 一次
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateEntity updateTotalStock(String templateNo, int totalCount, Integer expectedVersion) {
        CouponTemplateEntity entity = getRequiring(templateNo);
        requireVersion(entity, expectedVersion);
        int before = entity.getTotalCount();
        if (totalCount < before - remainingOf(templateNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "总库存不能小于已发出数（已发 " + (before - remainingOf(templateNo)) + "，试图设为 " + totalCount + "）");
        }
        entity.setTotalCount(totalCount);
        if (templateMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "券模板已被他人修改，请刷新后重试");
        }
        // 键里存的是"剩余"，改的是"总量"：DEL 后按对账口径重建才自洽（force=true 的那条路）
        reheat(templateNo, true);
        return entity;
    }
```

> `remainingOf` 与 `reheat` 的"已发数"口径必须与 `CouponTemplateService.reheat` 里那段对账公式
> 一致（`init.sql` 的 `total_count - 已发` 与 Redis 键的语义）。实现时先读那个方法，
> 把它的公式抽成一个包内可见的 `computeRemain(...)` 复用，**不要复制第二份**——
> 地雷 E 的起因就是两处各写一遍同一公式。

- [x] **Step 3: controller + 审计 + 跑测试 + 提交**

照 Task 3 Step 4-8 做（列表 `GET /api/admin/coupon/templates` 走 `PageResult<TemplateView>`；
`POST` 新建；`PUT /{no}/stock`；`PUT /{no}/status` 上下线）。审计 `resourceType="coupon-template"`。
本模块收尾跑：

```bash
source scripts/common.sh && mvn -q -pl marketing-coupon -am test && ./scripts/smoke-test.sh
```

---

### Task 6: seckill —— 活动创建/上下线 + 库存编辑，并把 `stock` 的 404 归一

**Files:**
- Create: `marketing-seckill/.../controller/SeckillAdminController.java`、`.../dto/SeckillActivityCreateRequest.java`、`SeckillActivityView.java`
- Modify: `marketing-seckill/.../service/SeckillWarmUpService.java`、`.../controller/SeckillController.java`（`:60-70` 的 stock）
- Test: `marketing-seckill/src/test/java/.../service/SeckillAdminWriteTest.java`

**已核实的现状**：`SeckillWarmUpService` 是 `CacheReheater`（`type()="seckill-stock"`），
`SeckillStockService.allocateBuckets(total, buckets)`（`:49`）与 `warmUp(activity, bucketStocks)`（`:64`）
已经分得很干净 —— 改库存的正确动作就是"按新 total 重新算桶 + 覆写桶键"，**这段逻辑只能留在 owning 服务**。
桶数 `seckill.buckets` 是**刻意不做成在线参数**的（母版 §10），所以库存编辑要读 properties 的桶数。

- [x] **Step 1: 写失败测试（四条）**

```java
    @Test
    @DisplayName("改库存必须重算并覆写全部桶：只改 DB 的话分桶余量还是旧总数")
    void stockEditRewarmsBuckets() {
        stubActivity("SK9009", 1000, 3);
        when(mapper.updateById(any())).thenReturn(1);
        service.updateStock("SK9009", 600, 3);
        verify(stockService).warmUp(any(), eq(List.of(200, 200, 200)));
    }

    @Test
    @DisplayName("expectedVersion 过期 → 41008：不写库也不动桶")
    void staleVersionRejected() {
        stubActivity("SK9009", 1000, 5);
        BizException e = assertThrows(BizException.class, () -> service.updateStock("SK9009", 600, 3));
        assertEquals(41008, e.getCode());
        verify(mapper, never()).updateById(any());
        verify(stockService, never()).warmUp(any(), any());
    }

    @Test
    @DisplayName("新建活动：余数摊给前几个桶，且总和等于新总数（与 allocateBuckets 的既有口径一致）")
    void createAllocatesBucketsWithRemainder() {
        when(mapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        service.create(request("SK9010", 10, 4));      // 10 件 4 桶
        verify(stockService).warmUp(any(), eq(List.of(3, 3, 2, 2)));
    }

    @Test
    @DisplayName("GET /api/seckill/stock/{不存在活动} 返回 40400，不再返回空数组（①② spec §10 漏网）")
    void missingActivityStockIs40400() {
        when(stockService.readBuckets("SK_NOPE")).thenReturn(List.of());   // 既有方法名以真实签名为准
        BizException e = assertThrows(BizException.class,
                () -> controller.stock("SK_NOPE", new MockHttpServletRequest()));
        assertEquals(40400, e.getCode());
    }
```

> 最后一条要先看 `SeckillController.stock`（`:60-70`）实际调的是哪个方法名，
> 按真实签名写断言；`List.of()` 与"活动不存在"在现状里是**混在一起**的，
> 所以本步骤要求 service 层给出可区分的判据（`exists(activityNo)`），
> 而不是靠"桶列表为空"猜 —— 空桶也可能是真的售罄，那必须仍是 200 + 余量 0。

第四条会改 `GET /api/seckill/stock/{no}` 的行为 —— 先确认没有断言依赖空数组：
`grep -n "seckill/stock" scripts/*.sh` 与 `grep -rn "stock(" marketing-seckill/src/test` 都要看。

- [x] **Step 2-4: service / controller / 冒烟**

`updateStock(activityNo, totalStock, expectedVersion)`：校验 → 写库 →
`warmUp(activity, allocateBuckets(total, properties.getBuckets()))` 同事务。
Controller 形状照 Task 3。

冒烟链路 3 的 `库存基线从接口读` 那段（`smoke-test.sh:200+`）继续可读，只是不存在活动现在报
`40400`；`reset-demo-data.sh` 在 T9 之前仍走直连，所以本任务不碰它。

收尾：`mvn -q -pl marketing-seckill -am test && ./scripts/smoke-test.sh`（期望 72/72），
提交 `feat(seckill): ③ 活动与库存管理端点，改库存同事务重建分桶；stock 不存在改 40400`。

---

### Task 7: 审计跨进程 —— 业务侧投递，admin 侧 drain

**Files:**
- Create: `marketing-common/.../audit/AuditOutbox.java`（T1 文件表里已列，本任务补实现与测试）
- Create: `marketing-admin/.../audit/AuditOutboxDrainer.java`
- Modify: `marketing-admin/.../audit/AuditService.java`（抽出一个 `recordEntity(AdminAuditLogEntity)` 入口给 drainer 复用）
- Test: `marketing-common/.../audit/AuditOutboxTest.java`、`marketing-admin/.../audit/AuditOutboxDrainerTest.java`

**已核实的现状**：`AuditService implements AuditSink`，`record(...)` 里 `try/catch RuntimeException`
后只 warn（"落库失败不影响动作结果"）；`toEntity` 逐字段 `cut(...)` 截断到列宽；
`createTime` 由 DB 默认值给。drain 的记录**必须带业务侧时刻**，否则"改预算的时间"会变成"被搬运的时间"。

- [x] **Step 1: 写失败测试 —— 投递侧**

`AuditOutboxTest.java`（mock `StringRedisTemplate` + `StreamOperations`）四条：

```java
    @Test
    @DisplayName("XADD 必须带 MAXLEN 上限：admin 长时间不消费不能把 Redis 撑大")
    void addCapsStreamLength();

    @Test
    @DisplayName("没有 TTL：TTL 淘汰=静默丢审计（这是选 Stream 不选 LPUSH+LTRIM+TTL 的全部理由）")
    void neverSetsExpire();

    @Test
    @DisplayName("Redis 抛异常时只 warn + 计数，不把业务写回滚")
    void redisFailureDoesNotPropagate();

    @Test
    @DisplayName("字段名固定 payload：admin 侧按同一个名字读，拼错=审计静默消失")
    void writesSinglePayloadField();
```

实现要点（`AuditOutbox`）：

```java
    public void record(AuditPayload payload) {
        try {
            redis.opsForStream().add(StreamRecords.newRecord()
                    .ofMap(Map.of(AuditPayloadCodec.FIELD, AuditPayloadCodec.write(payload)))
                    .withStreamKey(StreamKeys.auditPending()));
            redis.opsForStream().trim(StreamKeys.auditPending(), StreamKeys.MAX_LEN);
        } catch (RuntimeException e) {
            meters.counter("marketing.audit.outbox.error").increment();
            log.warn("[audit] 投递失败（该条审计丢失，但业务动作已完成）action={}, resource={}#{}, cause={}",
                    payload.action(), payload.resourceType(), payload.resourceId(), e.toString());
        }
    }
```

> `opsForStream()` 的 `add/trim` 具体签名以本仓库 Spring Data Redis 版本为准
> （`XADD` 的 maxlen 也可以直接 `XADD ... MAXLEN ~ n`，若 `add` 不带 maxlen 参数，
> 就把 `trim` 那行留着并断言它被调用）。**不要**为了少一次调用把 `trim` 省掉。

- [x] **Step 2: 写失败测试 —— drain 侧**

`AuditOutboxDrainerTest.java` 三条：

```java
    @Test
    @DisplayName("读到就落表并 XACK：不 ACK 的条目会永远留在 PEL 里")
    void acksAfterPersisting();

    @Test
    @DisplayName("业务时刻写进 create_time：审计时间线必须是动作发生的时间，不是搬运时间")
    void preservesBusinessTimestamp();

    @Test
    @DisplayName("坏载荷跳过并计数，不能卡住整批（一条脏数据让审计停摆是最坏的后果）")
    void skipsUndecodableRecord();
```

实现要点：`@Scheduled(fixedDelayString = "${marketing.audit.drain-ms:${CONFIG_POLL_SECONDS:5}000}")`
+ `XREADGROUP group=StreamKeys.ADMIN_DRAIN_GROUP`（首次 `createGroup` 幂等，捕获
`BusyException`），每批 ≤500，逐条 `AuditPayloadCodec.read` → 成功则
`auditService.recordEntity(toEntity(p))`，失败则 `counter("marketing.audit.drain.skipped")` + warn，
两种情况都 `XACK`。

> standalone 的 `@EnableScheduling` 已存在（`MarketingStandaloneApplication`），
> 但 FULL 进程形态的 admin 是独立 JVM —— 落地时确认 `marketing-admin` 的启动类也带
> `@EnableScheduling`；若没有，就在这里用一个 daemon `ScheduledExecutorService`
> （与 ⑤ 的 `ConfigSnapshotPoller` 同一手法，理由也相同：漏加开关的表现是"审计永远不落表"）。

- [x] **Step 3: LITE 与 FULL 同一条路径**

LITE 下 standalone 里既有 outbox 又有 drainer，审计照样绕一圈 Redis。
**不做"同进程就直落"的捷径**：两条路径意味着 LITE 测不到 drain，而 drain 恰恰是 ③ 里
唯一会静默丢数据的组件。绕一圈的代价是 ≤5s 延迟与一次 Redis 往返，值得。

- [x] **Step 4: 冒烟断言 + 收尾**

`smoke-test.sh` 加一条（放在链路 4 的"本轮动作写入审计"旁边，同一把 `audit_max_id` 前后差值口径）：
后台改一次预算 → 等 `drain-ms` → `GET /api/admin/audits?action=activity.budget.set` 必须有本轮新行，
且 `create_time` 早于本轮的清理动作时刻。跑完 `XLEN mkt:audit:pending` 必须归 0
（与 LITE MQ 那条"跑完 XLEN 恒 0"同形）。

```bash
source scripts/common.sh && mvn -q -pl marketing-common,marketing-admin -am test
./scripts/deploy-preview.sh && ./scripts/smoke-test.sh    # 期望 73/73（本任务加 1 条）
```

提交：`feat(audit): ③ 业务侧审计经 Redis Stream 投递、admin drain 落表（保留业务时刻）`

---

### Task 8: 重预热回执 —— 把 `41010 本形态不适用` 变成真路径

**Files:**
- Create: `marketing-common/.../reheat/ReheatRequest.java` + `ReheatAck.java`（载荷与编解码，形状照 Audit 那两个）
- Create: `marketing-common/.../reheat/ReheatDispatcher.java`（业务侧轮询执行器：`XREADGROUP mkt:reheat:pending` → `CacheReheatRegistry.reheat(type,key,force)` → `XADD mkt:reheat:ack`）
- Modify: `marketing-admin/.../controller/AdminCacheController.java`（本进程有该 type → 同步返回，现状不变；没有 → 投递 + `status=DISPATCHED`）
- Test: 三个（dispatcher 的"执行后必写 ack"、"失败也要写 ack（FAILED + error）"、controller 的分岔）

**关键取舍**：`41010` 不删 —— 它继续表示"这个能力在本进程没有"，只是多了一条**可执行**的替代路径。
`GET /api/admin/cache/reheat/ack?id=` 读回执；`id` 由 admin 生成（`INCR mkt:reheat:seq`），
回执载荷带 `{id, type, key, status: DONE|FAILED, before, after, error, at}`。

- [ ] **Step 1**: 写失败测试（三条，见 Files）
- [ ] **Step 2**: 实现 `ReheatDispatcher`（daemon 线程 + `ConfigSyncer` 让位标记与 ⑤ 完全同构：
  网关没有 reheater，也就永远不需要这条链；**别在网关里装它**）
- [ ] **Step 3**: 实现 controller 分岔 + ack 端点；审计照 T7 投 `cache.reheat.dispatch`
- [ ] **Step 4**: 冒烟两条：LITE 仍同步 `after` 有值（现有 `smoke-test.sh:324` 那条不动）；
  FULL 分进程下 `POST reheat?type=budget` 返回 `DISPATCHED` → 轮询 ack 直到 `DONE` 且
  `after` 等于新口径 → 再断 C 端余额。**这才第一次真正端到端验证了跨进程重预热**
  ①② 里那句"跨进程转发留给后面一段"的账。期望 75/75。

提交：`feat(reheat): ③ FULL 分进程的重预热走 pending/ack 两跳，回执可读`

---

### Task 9: 脚本冲击面 —— `reset-demo-data.sh` 改走后台端点

搬家本身已在 T3-T6 各自任务里做完（含删 C 端旧路径）。本任务只剩三件事：

- [ ] **Step 1: `reset-demo-data.sh` 换成"登录 + 改库存"**
  删掉 `:29-91` 的三条形态分支（探测容器 → restart seckill 容器 → 重启宿主机进程）与
  `docker exec redis-cli ... DEL/MGET` 那几段；改成：
  `POST /api/admin/auth/login` 换 token → `PUT /api/admin/seckill/SK2026001/stock` →
  分桶由 owning 服务在同一事务里重建。**冷栈守卫保留**（现状那一段"栈是冷的就非零退出"
  是最有价值的部分，别在改写时弄丢）。脚本因此需要 `ADMIN_JWT_SECRET` 可达的登录路径，
  dev/LITE/FULL 三套都要实测一遍。
- [ ] **Step 2: 新链路 6（母版 §9）**
  四条断言：① C 端 token 打 `POST /api/activity`（已搬走）必 `404`；
  ② admin token 打 C 端交易路径（`/api/coupon/grant`）仍通；
  ③ 只有裸 `X-Admin-Role: admin` 头、直连业务端口（不经网关）打 `PUT /api/admin/activities/.../budget`
  必 `40100` —— 这条是 §3.2 的部署级回归锚，**必须绕过网关**打服务端口；
  ④ `XLEN mkt:audit:pending` 跑完归 0。
- [ ] **Step 3: `load-probe.sh:18` 的提示语**（"必要时 reset-demo-data.sh"这句仍成立，
  只需补一句"需要后台账号"）；跑一次 `bash -n` 三条脚本。

提交：`test(smoke): ③ 链路 6 四条边界断言（含直连端口的裸头 40100）；reset-demo-data 改走后台端点`

---

### Task 10: 五形态复跑 + README 收口

与 ⑤ 的 T11 同构：

- [ ] Step 1 `mvn -q install` 全绿（记录用例/类数，README 两处计数刷新）
- [ ] Step 2 A LITE 容器 → 冒烟（期望 75/75 或 T8/T9 之后的实际条数），`docker stats` 复核内存
- [ ] Step 3 B FULL 进程 → 冒烟，**中间不做任何 SQL 清理**（原地切换的第二条证据）
- [ ] Step 4 C FULL 容器 → 冒烟（deploy-full 后等 broker 稳定一分钟再跑，见 README 同源现象⑤）
- [ ] Step 5 D dev → 冒烟；Step 6 E 每服务一库 → 冒烟（带 `MYSQL_DB=marketing_activity`）
- [ ] Step 7 README：API 表增删、新增"写入口矩阵"一节（哪个路径 · 哪个进程 · 哪个角色 · 是否审计）、
  覆盖矩阵五格刷新到本段条数、"测试与验证"计数刷新
- [ ] Step 8 母版 §13 追加 ③ 的实施偏离（至少三条：身份凭证改验签、审计走 Stream 而非 LPUSH+TTL、
  LITE 不做"同进程直落"捷径）

提交：`docs: ③ 口径收口（写入口矩阵 + 五形态复跑记录）`

---

## 落地时对计划的修正

1. **`git mv` 之后必须改 `package` 行**（Task 1 Step 1 漏写了这半句）：只改 import 的话编译报
   `cannot find symbol`，而报错位置在**引用方**（11 个 admin 文件），看起来像是 sed 没跑干净，
   实际是被移动文件自己的 `package com.example.marketing.admin.security;` 还在。
   Task 9 要搬 `ClientIp` 时同样注意。
2. **测试里的"时刻"必须用真实时钟**：`AdminTokenCodec.verify` 只比 `now > exp + skew`，
   写死一个 `NOW = 1_800_000_000` 会让那条"过期 token"断言随日历悄悄变成"还没过期"——
   它测的就不再是过期。改成 `now()` 现取 `Instant.now().getEpochSecond()`。
3. **`StreamOperations` 没有 `expire(...)`**（那是 `KeyOperations` / 模板上的方法）：
   "绝不设 TTL"这条只能断言 `verify(redis, never()).expire(anyString(), any(Duration.class))`。
   `XADD` 的 maxlen 也不用 `StreamRecords` 那套构造：本仓库用
   `opsForStream().add(key, Map.of(FIELD, json))` + `opsForStream().trim(key, MAX_LEN)` 两段式，
   断言分别打在 `add` 的 map 只有一个 `payload` 键、以及 `trim` 的上界参数上。
4. **`@DisplayName` 里不能再嵌双引号**：`"防止"谁签一枚"）"` 这种写法是编译错误（字符串提前闭合）。
   中文引号或改写句子，别用转义——转义后在终端里可读性更差。
5. **`AuditOutbox` 的失败注入只能这样打**：`when(stream.add(anyString(), anyMap()))` 抛异常，
   然后断言 `record` 不外溢 + 计数 +1。用 `doThrow` 配 `verify` 那条路在本仓库的 Mockito 版本上
   会撞上 `add` 的返回类型（`RecordId`）问题。

6. **Task 2 漏写了一处必改文件**：`application.yml` 的 rate-limit map 一加四条，
   ⑤ 留下的 `GatewayConfigDefinitionsTest.definitionsMatchYamlRateLimitMap` 立刻红
   （"yml 的 rate-limit 条目与声明清单漂移了"）。这条红是**设计如此**，别绕过它：
   新阈值必须同时进 `GatewayConfigDefinitions` 才能在线改，否则后台没有输入框、
   写了也没人消费。落地时按九个键补齐（四条新前缀各 50/s，与 admin-route 同档）。
7. **限流 map 的项是 `{limit, window-seconds}` 两段结构**，不是裸标量：
   写成 `admin-activity-route: ${RL_ADMIN_ACTIVITY:50}` 会被 binder 静默丢掉，
   表现恰好是"这条路由完全不限流"。Task 2 的测试因此加了一条"形状也必须对"的断言。
8. **yml 的语法/结构错误没有任何单测能抓**（`GatewayAdminRoutesTest` 读的是文本）。
   所以 T2 的收尾必须真起一次栈：LITE `deploy-preview.sh` + `smoke-test.sh`，
   并用 `curl` 验四条新前缀在无凭证时回 `40100`（证明路由生效，而不是 404 或 500）。
9. **`headers.remove("X-Admin-Token")` 在已鉴权路径上不是 load-bearing**（`set` 本来就会顶掉），
   所以那条断言要打在**免鉴权登录口**上：那里 claims 为 null、没有任何 set，
   少了 remove 就等于"未登录的请求带着 X-Admin-Role: admin 到下游"。变异检查证实：
   删掉 remove 后只有那条新写红的的测试会红（`expected: <null> but was: <attacker-minted>`）。

10. **`@AutoConfiguration` 用了 `@ConditionalOnBean(StringRedisTemplate)` 就必须
    `afterName = RedisAutoConfiguration`**（T1 的实现漏了，T3 起 LITE 时 standalone
    **直接起不来**：`No qualifying bean of type 'AuditOutbox'`）。
    同一 @AutoConfiguration 的成员条件评估时看不到别的自动配置稍后才注册的 bean ——
    ⑤ 给 `ConfigCommonAutoConfiguration` 写了这行，我抄漏了。
    **更该记住的是测试的形状**：`AdminSecurityAutoConfigurationTest` 原本手搓 mock 的
    `StringRedisTemplate`，所以这个装配缺陷在 5 条单测全绿的情况下存活了一整轮，
    直到真起栈才炸。现在补了一条 runner 里真放 `RedisAutoConfiguration` 的用例，
    去掉 `afterName` 它就红（变异检查已验）。
11. **MyBatis-Plus 3.5.7 的 `updateById` 有两个重载**（单条 / `Collection`）：
    Mockito 里写 `any()` 会报 `reference to updateById is ambiguous`，必须 `any(ActivityEntity.class)`。
12. **手工探针会污染共享种子**：T3 验证"改预算→C 端余额跟着变"时把 `ACT2026001` 的
    `budget_amount` 从种子的 1000000.00 改成了 78.00。改回来用的正是新端点（顺带再验一次
    reheat 生效）。**跑完探针要么还原、要么重跑冒烟**，否则下一轮的绿是假的。
    实测还原后重跑 72/72。

13. **手工探针的金额必须在规则门槛之上**：第一次探"改规则秒级生效"用了 199.90 元，
    而种子的 `满200减30` 与新加的 `满100减7` 都判不出差别（三次都是 0），
    看起来像"bumpVersion 没用"。换成 299.90 才拿到基线 30.00 → 建规则 37.00 → 停用回 30.00
    这条真实曲线。**验证一个机制前先确认探针有分辨力。**
14. Mockito 的 `never` 用法：`verify(mock, never()).method(any())`，
    写成 `never(mock).method(...)` 编译期就报 `cannot be applied to given types`。
15. **T4 顺带修掉一个既有契约缺陷**（不在计划里，是探针撞出来的）：`CalcInput`/`CalcItem`
    一个约束都没有，客户端把 `unitPrice` 拼成 `price` 就得到 `50000 系统繁忙`
    （NPE 被 catch-all 兜住）。现在 `@NotNull`/`@DecimalMin`/`@Min(1)` + `@NotEmpty @Valid`，
    三行 MockMvc 用例钉住"客户端错误必须报 40000 并点名是哪个字段"。
    **`@Valid` 必须打在 `List<CalcItem>` 上**，否则 CalcItem 里的约束全都形同不存在 ——
    这是嵌套校验最常见的漏法。
16. `deploy-full.sh` 的优惠路由探针必须一起改：discount 已经没有 C 端 GET 了。
    新增 `wait_route_admin`（先登录拿 token）打 `/api/admin/discount/rules`，
    这比原来那条更有代表性（同时验到 `lb://marketing-discount` → DB → MyBatis 整条链）。
17. **在 python 里生成 shell 片段时不要用 shell 层的 `'"'"'` 转义**：那是给 heredoc 用的，
    在 python 字符串里会原样落进脚本，产出 `'"'"'"code":0'"'"'` 这种坏参数。
    `bash -n` 抓得住（这次就是它抓的），所以脚本改完必跑 `bash -n`。

18. **探针的目标值必须落在合法区间内才有分辨力**：第一次探券的库存端点把
    `totalStock` 设成 1100，而该模板已发 7151 张 —— 被新写的守卫判成 40000 拒了，
    看起来却像"改了没生效"。读一眼真实数字（total 100000 / issued 7151 / remain 92841）
    再改成 100100，才看到 remain 跳到 92949。与修正 #13 是同一条教训的第二次命中。
19. **`updateTotalStock` 改成调 `reheat(no, true)`，不自己 DEL+算一遍**：
    原写法复用了 `remainOf` 公式但复制了"重建"这条路径 —— 公式一份、路径两份，
    仍然是地雷 E 的形态。走 reheat 之后 before/after 由它给出，日志与 ④ 拿到的
    就是键里真正的值。测试不用改（`verify(stockService).overwrite(...)` 断言的是外部效果）。
20. 顺带把三处 `requireVersion` 收进 `common/exception/VersionGuard`
    （activity、discount、coupon 各有一份同码同文案的判断，第三份出现时就该收口）。

21. **`SeckillController.stock` 里写死的 `16` 是个真缺陷**，不只是 404 归一那件事：
    预热走 `SeckillProperties.buckets`（可被 `SECKILL_BUCKETS` 改），余量查询却固定读 16 个键。
    桶数不是 16 时，对账会看到"少了几桶"。现在两边都取 `SeckillRuntimeConfig.buckets()`（唯一出处）。
22. 秒杀这组端点最要紧的是三条"不许顺手开闸"，各自都有断言：
    新建一律 OFFLINE 且不预热；**OFFLINE 改库存只写 DB 不 force 重建**（否则等于绕过上下线
    把已售进度冲掉）；上线用 `force=false` 只补缺（在途进度不许被动）。
    `reheat` 的"仅 ONLINE 且未过结束时间"守卫交给 `SeckillWarmUpService` 一份，本模块不复制判定。
23. 在 shell 里嵌 python heredoc 时，**终止符必须独占一行**、后面不能再跟 shell 命令：
    我把 `echo "dto done"` 写进了 `PY` 块内，python 报 SyntaxError 而整段文件一个都没落成，
    看起来却像"写完了"。凡是批量写文件，写完立刻 `grep` 验一次落点。

24. **"跑完 XLEN 恒 0"这条断言一开始就红了，因为它揭示了两个真缺陷**（不是断言写错）：
    - 计划只写了 ACK，没写 XDEL。总线上的条目 ACK 之后还留着，`XLEN` 只增不减，
      `MAXLEN` 最终会把<b>最旧的真审计</b>挤掉 —— 那才是真的丢数据。现在 ACK 后再 XDEL
      （顺序不能反：先删后确认会在"已删未确认"的窗口里让这条审计彻底消失）。
      这与 LITE 消息通道"消费完 XDEL、跑完 XLEN 恒 0"是同一手法，断言口径也复用了它。
    - `createGroup(key, group)` 默认从<b>最新消息</b>开始。先有投递、后建组（首次部署、
      或键比消费组先存在）的那批审计永远不会被投给消费者 —— 它们不是脏数据，是没读过的真账。
      改成 `ReadOffset.from("0")` 建组，并加一条断言钉住这个 offset（"从最新消息建组=静默丢账"）。
      环境里那 54 条积压正是这么来的：销毁旧组重启后全部入表（`XLEN` 归 0、表里 business 动作 59 行）。
25. drain 的落表走 `AuditService.recordPayload(...)` 而不是自己拼 entity：
    `cut()` 那套按列宽截断必须只有一份口径。

## 编写进度

Task 1-3 已写到"照抄即可跑"的颗粒度（每个代码片段都对着 `2026-09-23` 的最终产物核过签名：
`AdminClaims(uid, sub, role, pwdVersion, jti, iat, exp)` 七参、`JsonUtils.parse(String, TypeReference)`、
`TOKEN_EXPIRED=40101`/`FORBIDDEN=40300`、`AdminAuthFilter.tokenOf(exchange)` 与 `pass(exchange, claims)`
私有签名、`AdminAuthFilterTest` 的 `adminGet/token/chain/captured` 夹具、全文件 0 处 `StripPrefix`）。

Task 3（activity）同上颗粒度。

Task 4-6（discount / coupon / seckill）同上，且只写与 Task 3 的差异 —— 三处形状完全一致的部分
（身份、乐观锁、审计）用引用而不是复制代码。

Task 7-10 同上：T7 审计投递与 drain、T8 重预热回执、T9 脚本冲击面、T10 五形态复跑与 README。

**执行进度**：Task 1 已落地（`feat(common): ③ 后台身份件与审计投递口…`）。
Task 2 已落地（`feat(gateway): ③ 四条后台前缀路由…`）：四条路由两套 profile 齐、
限流九条、`X-Admin-Token` 透传；LITE 真起栈复跑 72/72，四条新前缀无凭证回 40100。
全仓 **207 用例 / 50 类绿**。变异检查累计 9 处（T1 五 + T2 四），全部咬人。
`AuditOutbox` 按计划提前到 T1 落了（T7 只剩 admin 侧 drain，届时它那份 Files 列表按"Modify"读）。
T7/T8/T10 的部分测试条目仍用一行式描述（`void xxx();` 那种），**实施时必须写成可编译的完整用例**
—— 那是"该断言什么"的清单，不是代码。T1-T6 的测试都已给全码，照它们的夹具写法补即可。
