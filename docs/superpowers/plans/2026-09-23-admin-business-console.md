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

**Interfaces:**
- Consumes: `AdminTokenCodec.verify(String, long)` → `TokenVerifyResult`（`status()` + `claims()`，已有）；`BizException.of(ErrorCode, String)`（已有）。
- Produces: `AdminRequestIdentity.require(HttpServletRequest, String...)` → `AdminPrincipal`；`AuditPayload`（13 字段 record）与 `AuditPayloadCodec.write/read`；`StreamKeys.auditPending()` / `reheatPending()` / `reheatAck()` / `ADMIN_DRAIN_GROUP`。Task 7 与 Task 3-6 直接消费这三件。

- [ ] **Step 1: 把两个 record/常量类移进 common**

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

- [ ] **Step 2: 写失败测试 —— 只有裸身份头时必须 40100**

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

- [ ] **Step 3: 实现 `AdminRequestIdentity`**

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

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=AdminRequestIdentityTest`
Expected: PASS（5 个用例）。

变异检查三处，逐条做完改回来：
1. `require` 里把"缺 token 就 40100"换成"缺 token 时改读 `X-Admin-Role` 头" → `bareHeadersAreNotCredentials` 必须红；
2. `new AdminPrincipal(claims...)` 改成 `new AdminPrincipal(..., request.getHeader("X-Admin-Role"), ...)` → `roleComesFromClaimsNotHeaders` 必须红；
3. 空密钥守卫删掉 → `blankSecretFailsFast` 必须红。

- [ ] **Step 5: 写失败测试 —— 审计载荷往返与"读侧永不抛"**

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

- [ ] **Step 6: 实现载荷与编解码**

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

- [ ] **Step 7: 跑测试确认通过 + 变异检查**

Run: `mvn -q -pl marketing-common -am test -Dtest=AuditPayloadCodecTest`
Expected: PASS（3 个用例）。

变异检查：把 `read` 里某个字段错位（`actorName` 读成 `role`）→ `roundTripsEveryField` 必须红；
把 `read` 改成直接抛 → `readNeverThrows` 必须红。

- [ ] **Step 8: 写失败测试 —— 装配矩阵（Servlet 装、密钥空不炸上下文）**

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

- [ ] **Step 9: 实现装配并登记**

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

- [ ] **Step 10: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common,marketing-admin -am test`
Expected: PASS。`marketing-admin` 必须一起跑 —— Step 1 挪走了它的两个类，编译能过才算改干净。

- [ ] **Step 11: 提交**

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
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/filter/AdminAuthFilterTest.java`（加 2 条）
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/config/GatewayAdminRoutesTest.java`（新建，读 yml 断言）

**Interfaces:**
- Consumes: `AdminAuthFilter` 已有的 `pass(...)`（`:127-137` 那串 headers 操作）。
- Produces: `/api/admin/activities/**`、`/api/admin/coupon/**`、`/api/admin/discount/**`、`/api/admin/seckill/**` 四条路由 + 同名四个限流 map 项；`X-Admin-Token` 头透传给下游。Task 3-6 的 controller 依赖这两件事都成立。

- [ ] **Step 1: 写失败测试 —— yml 里四前缀都在两套 profile 出现，且都进了限流 map**

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

- [ ] **Step 2: 加路由与限流（两段都要改）**

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

- [ ] **Step 3: 跑测试确认通过**

Run: `mvn -q -pl marketing-gateway -am test -Dtest=GatewayAdminRoutesTest`
Expected: PASS。

变异检查：把 nacos 段里 `admin-seckill-route` 那条删掉 → 断言必须红（"只在 1 个 profile 里配了"）。

- [ ] **Step 4: 写失败测试 —— 网关透传 X-Admin-Token，且客户端自带的被删**

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

- [ ] **Step 5: 实现透传**

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

- [ ] **Step 6: 跑测试确认通过 + 变异检查**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test`
Expected: PASS（含 ⑤ 原有的 28 个用例不退步）。

变异检查：把 `headers.remove("X-Admin-Token")` 那行删掉 → `stripsClientSuppliedTokenHeader` 必须红。

- [ ] **Step 7: 提交**

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

## 编写进度

Task 1-2 已写到"照抄即可跑"的颗粒度（每个代码片段都对着 `2026-09-23` 的最终产物核过签名：
`AdminClaims(uid, sub, role, pwdVersion, jti, iat, exp)` 七参、`JsonUtils.parse(String, TypeReference)`、
`TOKEN_EXPIRED=40101`/`FORBIDDEN=40300`、`AdminAuthFilter.tokenOf(exchange)` 与 `pass(exchange, claims)`
私有签名、`AdminAuthFilterTest` 的 `adminGet/token/chain/captured` 夹具、全文件 0 处 `StripPrefix`）。

**Task 3-10 待写**（顺序即依赖顺序，每个任务都按"失败测试 → 实现 → 变异检查 → 提交"展开）：
T3 activity、T4 discount、T5 coupon、T6 seckill、T7 审计 Stream 投递与 drain、
T8 重预热回执、T9 C 端收口与脚本冲击面、T10 五形态复跑与 README。
