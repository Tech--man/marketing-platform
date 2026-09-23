# ⑤ 在线配置下发（DB 真值 + 自包含 Redis 快照）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让限流阈值与灰度比例在线可改、≤5s 生效、不重启：真值在 MySQL，各进程经一份自包含 Redis 快照收敛，快照缺失或值非法时逐条退回本进程的 yml 出厂值。

**Architecture:** 一条单向数据流、三个角色。① `marketing-admin` 是唯一写方：校验后写 `admin_config`，再按四种形态各生成一份**全量合并快照**写进 `mkt:cfg:snapshot:{form}`；② `marketing-common` 提供参数自述 SPI（`ConfigDefinitionProvider`）与读取侧生效值容器（`ConfigValues`），各进程每 5s 比对 `mkt:cfg:version:{form}`，变了才取快照、逐条按本地声明校验；③ 网关既没有 DataSource 也没有阻塞 Redis 客户端（母版事实 #8），所以在自己模块里用 `ReactiveRedisTemplate` 实现同一套轮询，喂同一个 `ConfigValues`。灰度是例外：真值落 `activity` 的两个新列，由 owning 服务每 5s 回源 DB 重建，不经过 `admin_config`、不依赖 Redis（段内 spec §3 偏离 #3）。

**Tech Stack:** Java 17、Spring Boot 3.2.5、Spring Cloud Gateway（WebFlux + `ReactiveRedisTemplate`）、MyBatis-Plus 3.5.7（本段的配置存储刻意用 `JdbcTemplate`，理由见 Task 5）、Micrometer、Lettuce `StringRedisTemplate`、Jackson（复用 `JsonUtils`）、JUnit 5 + Mockito + H2 `MODE=MySQL`、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-09-23-online-config-delivery-design.md`（段内 spec，含对母版 `docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md` §5 的四条偏离及理由）

## Global Constraints

- **JDK 必须 17**：任何 `mvn` 前先 `source scripts/common.sh`（Homebrew 默认 JDK 26 会让 Lombok 注解处理崩成满屏 `cannot find symbol`）。
- **单模块测试必须带 `-am`**：`mvn -q -pl marketing-gateway -am test`。少了它会用本地仓库里的旧 `marketing-common`，报"程序包不存在"的假错。
- **不部署 `DEPLOY_FORM` 就等于没有这套机制**：未设置/无法识别的形态一律按 `GLOBAL` 解析，行为与本段之前逐字节一致。
- **不新增常驻进程**：所有件装进已有六个进程；轮询用各自一个 daemon 线程，不依赖 `@EnableScheduling`（六个里只有 standalone 开了它，漏加的表现是"改完没反应"）。
- **admin 绝不 import 业务模块**；网关绝不引入 DataSource 或阻塞 Redis 客户端。
- **不声明没有接线消费的配置键**（段内 spec §3 偏离 #4）：一条声明了却没被任何 `configValues.xxxOr()` 消费的键，就是后台里一个"点了没反应"的按钮。
- **基线**：现有 52 条 smoke 断言必须同时全绿；单测基线 25 类 / 106 个 `@Test`，本段收尾时刷新 README 里的这两个计数。
- **写路径顺序固定**：`INCR mkt:cfg:seq` → 写行 → `SET snapshot` → `SET version`；后两步任一失败返回 `41009`。
- 提交信息用中文，前缀沿用本仓库历史（`feat(config):` / `fix(...)` / `test(...)` / `docs:`）。

## 文件结构（先看落点，再看任务）

**marketing-common**（新增 13 个类 + imports 一行 + 3 个错误码）

| 文件（`common/config/`） | 责任 |
|---|---|
| `ConfigType.java` | 值类型枚举 INT/LONG/STRING |
| `ConfigDefinition.java` | 一个可在线改的参数：键、类型、边界、出厂值、说明 + 自校验 |
| `ConfigDefinitionProvider.java` | SPI：各模块自述"我有哪些参数可改" |
| `ConfigSchemaRegistry.java` | 本进程收集到的声明合集，按 key 索引，重复 key 启动期失败 |
| `ConfigSnapshot.java` | 快照载荷模型 `{version, generatedAt, entries:{key:{value,type,defVer}}}` |
| `ConfigSnapshotCodec.java` | 快照 JSON 编解码，读侧永不抛 |
| `ConfigSchemaPayload.java` / `ConfigSchemaCodec.java` | 服务自述的 Redis 载荷与编解码（每条声明带 `owner` 模块名） |
| `ConfigMerge.java` | 形态解析优先级的**唯一**实现：form 行 > GLOBAL 行 |
| `ConfigKeys.java` | 四个 Redis 键名与四种形态常量（唯一允许出现字面量的地方） |
| `ConfigForm.java` | `DEPLOY_FORM` 归一与白名单 |
| `ConfigValues.java` | 读取侧生效值容器：逐条校验、`intOr/longOr/stringOr`、degraded 清单 |
| `ConfigSnapshotPoller.java` | 阻塞侧轮询器（版本比对 + 取快照 + schema 自述），daemon 线程 |
| `ConfigCommonAutoConfiguration.java` | **新**自动装配，不带 `@ConditionalOnBean(DataSource)` |

改：`common/api/ErrorCode.java`（+`41008/41009/41010`）、`common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**marketing-gateway**：新增 `config/GatewayConfigDefinitions.java`（5 条限流声明）、`config/RateRuleResolver.java`（纯解析，可单测）、`config/GatewayConfigSyncer.java`（reactive 轮询）；改 `filter/RateLimitFilter.java`、`resources/application.yml`

**marketing-admin**：新增 `config/AdminConfigStore.java`（JdbcTemplate）、`config/ConfigSchemaReader.java`（`@Component`，只读固定服务名的自述键）、`config/ConfigSnapshotPublisher.java`、`config/AdminConfigService.java`、`dto/` 下 5 个 record（`ConfigSetRequest`、`ConfigEntryView`、`ConfigFormValueView`、`ConfigOrphanView`、`ConfigOverviewView`）、`controller/AdminConfigController.java`；改 `resources/application.yml`、`controller/AdminCacheController.java`（41000→41010）

**marketing-activity**：新增 `service/GrayRuleCache.java`；改 `service/GrayService.java`、`infrastructure/entity/ActivityEntity.java`；**删** `config/GrayProperties.java`；两份 yml 删 `marketing.gray` 块

**marketing-discount / marketing-seckill**：各 1 个 provider（`config/DiscountConfigDefinitions.java`、`config/SeckillConfigDefinitions.java`）+ seckill 的 `config/SeckillRuntimeConfig.java` + 6 处取值改道

**DDL / 脚本 / 文档**：`docker/mysql/init/01-schema.sql`、`docker/mysql/init-lite/01-schema-lite.sql`、新 `docker/mysql/migrate/2026-09-23-admin-config.sql`、`scripts/start-all.sh`、`scripts/start-dev.sh`、`scripts/smoke-test.sh`、`docker/docker-compose.preview.yml`、`docker/docker-compose.full-app.yml`、`README.md`

---

## 落地时对计划的修正（Task 1-4 执行后回填，后续任务以此为准）

1. **新增 `common/config/ConfigSyncer.java`（标记接口）**。母版事实 #8 讲"网关只有 reactive 模板"说的是它*用什么*：`spring-boot-starter-data-redis-reactive` 会把 spring-data-redis 核心带进来，网关里**确实有 `StringRedisTemplate` bean**，于是 common 的阻塞轮询器会在网关里也起一个线程，与 `GatewayConfigSyncer` 两套节拍喂同一份生效值。修法：装配条件改成 `@ConditionalOnMissingBean({ConfigSnapshotPoller.class, ConfigSyncer.class})`，网关同步器实现该标记；common 侧加一条上下文测试钉住"有标记就不装轮询器"。
2. **`ConfigDefinition.ofInt` 的 `min/max` 改成 `long`**（与 record 的字段类型一致）。否则调用点写 `ofInt(key, 1000, 1, 200_000L, ...)` 会报"possible lossy conversion from long to int"。
3. **`GatewayConfigSyncer.syncOnce()` / `publishSchema()` 返回 `Mono<Void>`**，`start()` 里 `.subscribe()`、测试里 `.block(Duration.ofSeconds(5))`。计划原来让 `syncOnce()` 内部自己 subscribe，测试只能靠 mock 恰好同步完成来通过——那是运气不是设计。
4. **snakeyaml 的多文档坑**：`new Yaml().load(reader)` 遇到 `application.yml`（local + nacos 两个文档）直接抛 `expected a single document`。守卫测试统一改用 `loadAll(...).iterator().next()` / 遍历。
5. **`ValueOperations.set(K,V)` 返回 void**，桩它必须用 `doAnswer` / `doThrow`，`when(ops.set(...))` 编不过。
6. **迁移脚本自带"本库有没有这张表"的前置判断**（计划里只写了 `CREATE TABLE IF NOT EXISTS`）：否则对 `marketing_activity` 执行会建出一张没人读的 `admin_config`，等于给"admin 连错库"留了个静默陷阱。实测三种库（单库 / 只有 activity / 只有 admin_user）判读都正确。
7. **DDL 探针要带 `MYSQL_USER`/`MYSQL_PASSWORD`**：MySQL 8 不允许用 GRANT 顺带建用户，探针少给这两个变量会在 `01-schema.sql:20` 就 `ERROR 1410` 退出，看起来像 DDL 坏了其实是用探针的人错了。
8. **`marketing.config.form` 用嵌套占位读，不进任何 application.yml**：写成 `@Value("${marketing.config.form:${DEPLOY_FORM:}}")`（poller、网关同步器、admin 三处），节拍同理 `${CONFIG_POLL_SECONDS:5}`，灰度回源频率 `${GRAY_REFRESH_SECONDS:5}`。计划原本要在 7 个 yml 里各加一段 `marketing.config`——那正是本仓库 Task #11 专门消灭过的 `marketing.*` 等值副本，一份定义只留一处。
9. **不建 `DiscountRuntimeConfig`**：discount 的两个参数分别落在 `PromoEngine` 与 `DiscountCalcService`，各自只读一个，所以两处直接注入 `ConfigValues`（`values.intOr/values.longOr`）；`SeckillRuntimeConfig` 保留，因为 `SeckillStockService` 一处就要读两个 TTL。判断标准见 Task 7 Step 6。
10. **`PromoEngine` 多了一个构造参数**：`new PromoEngine(DiscountProperties, ConfigValues)`，既有基准测试改传 `ConfigValues.empty()`；`SeckillStockService` 的第二参数从 `SeckillProperties` 变成 `SeckillRuntimeConfig`。新增 `PromoEngineOnlineLimitTest` 用三条非互斥规则证明"在线把上限压到 1，引擎真的只应用一条"——这是"声明必须被消费"的可执行版本。

---

### Task 1: common 配置契约与生效值核心（纯逻辑，零 Spring 接线）

**Files:**
- Create: `marketing-common/src/main/java/com/example/marketing/common/config/ConfigType.java`
- Create: `.../common/config/ConfigDefinition.java`
- Create: `.../common/config/ConfigDefinitionProvider.java`
- Create: `.../common/config/ConfigSchemaRegistry.java`
- Create: `.../common/config/ConfigSnapshot.java`
- Create: `.../common/config/ConfigSnapshotCodec.java`
- Create: `.../common/config/ConfigMerge.java`
- Create: `.../common/config/ConfigKeys.java`
- Create: `.../common/config/ConfigForm.java`
- Create: `.../common/config/ConfigValues.java`
- Test: `marketing-common/src/test/java/com/example/marketing/common/config/ConfigDefinitionTest.java`
- Test: `.../common/config/ConfigMergeTest.java`
- Test: `.../common/config/ConfigSnapshotCodecTest.java`
- Test: `.../common/config/ConfigValuesTest.java`
- Test: `.../common/config/ConfigSchemaRegistryTest.java`
- Test: `.../common/config/ConfigFormTest.java`

**Interfaces:**
- Consumes: 无（叶子任务）
- Produces（后续任务按这些签名调用，不得改名）：
  - `ConfigDefinition.ofInt(String key, int def, int min, int max, String desc)`、`ofLong(String, long, long, long, String)`、`ofText(String key, int maxLength, String def, String desc)`、`boolean accepts(String raw)`、`Long coerce(String raw)`、`int intDefault()`、`long longDefault()`，record 访问器 `key/type/min/max/defaultValue/description`
  - `ConfigDefinitionProvider { String service(); List<ConfigDefinition> definitions(); }`
  - `ConfigSchemaRegistry(List<ConfigDefinitionProvider>)`、`Optional<ConfigDefinition> find(String)`、`boolean declares(String)`、`List<ConfigDefinition> all()`、`Map<String,String> serviceByKey()`
  - `ConfigSnapshot(long version, String generatedAt, Map<String,ConfigSnapshot.Entry> entries)`、`ConfigSnapshot.Entry(String value, ConfigType type, long defVer)`、`static ConfigSnapshot empty()`
  - `ConfigSnapshotCodec.write(ConfigSnapshot)` / `read(String)`（永不抛）
  - `ConfigMerge.merge(String ownForm, List<ConfigMerge.Row>)`、`ConfigMerge.Row(String cfgKey, String form, String cfgValue)`
  - `ConfigValues(ConfigSchemaRegistry, MeterRegistry)`、`static ConfigValues empty()`、`void apply(ConfigSnapshot)`、`int intOr(String,int)`、`long longOr(String,long)`、`String stringOr(String,String)`、`boolean overridden(String)`、`long appliedVersion()`、`List<String> degradedKeys()`
  - `ConfigKeys.GLOBAL`、`ConfigKeys.FORMS`、`ConfigKeys.SEQUENCE`、`ConfigKeys.snapshot(form)`、`ConfigKeys.version(form)`、`ConfigKeys.schema(service)`
  - `ConfigForm.resolve(String raw)`、`ConfigForm.unrecognized(String raw)`

- [ ] **Step 1: 写失败测试 —— 类型与边界校验**

`ConfigDefinitionTest.java`：

```java
package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界校验。写侧拒才算反馈，读侧的"逐条忽略"只是兜底——
 * 越界值进了 DB 而运营看到"保存成功"，下次大促才会炸。
 */
class ConfigDefinitionTest {

    @Test
    @DisplayName("INT：区间内通过（含端点），越界/非数字/空值拒绝")
    void intBounds() {
        ConfigDefinition d = ConfigDefinition.ofInt("gateway.ratelimit.seckill-route.limit", 200, 1, 200000, "x");
        assertTrue(d.accepts("120"));
        assertTrue(d.accepts("1"));
        assertTrue(d.accepts("200000"));
        assertFalse(d.accepts("0"), "0 会让该路由全拒，不该在取值范围内");
        assertFalse(d.accepts("200001"));
        assertFalse(d.accepts("abc"));
        assertFalse(d.accepts("12.5"));
        assertFalse(d.accepts(null));
        assertFalse(d.accepts("  "));
    }

    @Test
    @DisplayName("LONG：接受大数，下界同样生效")
    void longBounds() {
        ConfigDefinition d = ConfigDefinition.ofLong("seckill.bought-mark-ttl-seconds", 86400, 60, 604800, "x");
        assertTrue(d.accepts("604800"));
        assertFalse(d.accepts("59"));
        assertEquals(86400L, d.longDefault());
    }

    @Test
    @DisplayName("STRING：只受长度约束，空串非法")
    void textMaxLength() {
        ConfigDefinition d = ConfigDefinition.ofText("demo.text", 8, "a", "x");
        assertTrue(d.accepts("hello"));
        assertFalse(d.accepts("123456789"));
        assertFalse(d.accepts(""));
    }

    @Test
    @DisplayName("边界写反在构造期就失败")
    void reversedBoundsFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigDefinition.ofInt("bad", 5, 10, 1, "x"));
    }
}
```

补 import：`import static org.junit.jupiter.api.Assertions.assertEquals;` 与 `import static org.junit.jupiter.api.Assertions.assertThrows;`。

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigDefinitionTest`
Expected: 编译失败 `找不到符号 ConfigDefinition`

- [ ] **Step 3: 实现 `ConfigType` 与 `ConfigDefinition`**

`ConfigType.java`：

```java
package com.example.marketing.common.config;

/**
 * 可在线改的值类型。只有这三种：布尔一旦出现就要回答"未知"是什么，
 * 那是比 true/false 更麻烦的第三种状态，留到有真实需求时再加。
 */
public enum ConfigType {
    INT, LONG, STRING
}
```

`ConfigDefinition.java`：

```java
package com.example.marketing.common.config;

import java.util.Objects;

/**
 * 一个可在线改的参数。边界含端点。
 *
 * <p>STRING 用 {@code max} 表达长度上限、{@code min} 恒为 1：空值既可能是"想清空"
 * 也可能是"手滑删除"，这种歧义不该出现在限流阈值这类参数里。</p>
 *
 * @param defaultValue 代码/yml 出厂值，只用于展示"改回去是什么"，运行时不取它
 */
public record ConfigDefinition(String key, ConfigType type, long min, long max,
                               String defaultValue, String description) {

    public ConfigDefinition {
        Objects.requireNonNull(key, "配置键不能为空");
        Objects.requireNonNull(type, "配置类型不能为空");
        if (min > max) {
            throw new IllegalArgumentException("配置 " + key + " 的边界反了: min=" + min + " max=" + max);
        }
    }

    public static ConfigDefinition ofInt(String key, int def, int min, int max, String desc) {
        return new ConfigDefinition(key, ConfigType.INT, min, max, String.valueOf(def), desc);
    }

    public static ConfigDefinition ofLong(String key, long def, long min, long max, String desc) {
        return new ConfigDefinition(key, ConfigType.LONG, min, max, String.valueOf(def), desc);
    }

    public static ConfigDefinition ofText(String key, int maxLength, String def, String desc) {
        return new ConfigDefinition(key, ConfigType.STRING, 1, maxLength, def, desc);
    }

    /** 数值型返回解析后的值（越界或非数字则 null）；STRING 恒返回 null，走长度校验 */
    public Long coerce(String raw) {
        if (raw == null || type == ConfigType.STRING) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(trimmed);
            return v < min || v > max ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean accepts(String raw) {
        if (raw == null) {
            return false;
        }
        if (type == ConfigType.STRING) {
            String trimmed = raw.trim();
            return !trimmed.isEmpty() && trimmed.length() <= max;
        }
        return coerce(raw) != null;
    }

    public int intDefault() {
        return Integer.parseInt(defaultValue);
    }

    public long longDefault() {
        return Long.parseLong(defaultValue);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigDefinitionTest`
Expected: PASS（4 个用例）

- [ ] **Step 5: 写失败测试 —— 形态解析优先级（两档共库的唯一安全阀）**

`ConfigMergeTest.java`：

```java
package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 生效优先级：当前 form 的行 &gt; GLOBAL 的行 &gt; 没有（由调用方退回出厂值）。
 *
 * <p>关键是"不串"：FULL 进程读到 LITE 的保守阈值会掐死吞吐，反过来是把洪流灌进单机。
 * 拆成三条独立断言而不是一个综合用例，将来改 merge 时红字能指明是哪一侧漏了。</p>
 */
class ConfigMergeTest {

    private static final List<ConfigMerge.Row> ROWS = List.of(
            new ConfigMerge.Row("k.global", "GLOBAL", "1"),
            new ConfigMerge.Row("k.lite", "LITE", "2"),
            new ConfigMerge.Row("k.full", "FULL", "3"),
            new ConfigMerge.Row("k.both", "GLOBAL", "10"),
            new ConfigMerge.Row("k.both", "LITE", "20"));

    @Test
    @DisplayName("LITE 读到 GLOBAL 与自己的行，同键时自己的行覆盖 GLOBAL")
    void liteSeesOwnAndGlobal() {
        Map<String, String> merged = ConfigMerge.merge("LITE", ROWS);
        assertEquals("1", merged.get("k.global"));
        assertEquals("20", merged.get("k.both"));
        assertEquals("2", merged.get("k.lite"));
        assertFalse(merged.containsKey("k.full"), "FULL 的值不得进入 LITE 的生效集");
    }

    @Test
    @DisplayName("未设置形态时只有 GLOBAL 生效")
    void unsetFormOnlySeesGlobal() {
        assertEquals(Map.of("k.global", "1", "k.both", "10"),
                ConfigMerge.merge(ConfigKeys.GLOBAL, ROWS));
    }

    @Test
    @DisplayName("大小写与空白无关；DEV 看不到 LITE/FULL 的行")
    void caseInsensitiveAndDevIsolated() {
        assertEquals("3", ConfigMerge.merge(" full ", ROWS).get("k.full"));
        assertEquals(Map.of("k.global", "1", "k.both", "10"), ConfigMerge.merge("DEV", ROWS));
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigMergeTest`
Expected: 编译失败 `找不到符号 ConfigMerge`

- [ ] **Step 7: 实现 `ConfigKeys`、`ConfigForm`、`ConfigMerge`**

`ConfigKeys.java`：

```java
package com.example.marketing.common.config;

import java.util.List;

/**
 * 在线配置的 Redis 键名，唯一允许出现这些字面量的地方。
 *
 * <p>{@code mkt:cfg:seq} 是一个全局 INCR 计数器。version 若靠"读 DB 的 MAX+1"得到，
 * 两个管理员并发写会拿到同一个版本号，读方按版本判"没变"就不再取第二份快照，
 * 于是陈旧值静默生效——那是本项目最贵的一类 bug（段内 spec §3 偏离 #1）。</p>
 */
public final class ConfigKeys {

    /** 只有这四种取值；新增形态要同时改这里与 DDL 的列注释 */
    public static final List<String> FORMS = List.of("GLOBAL", "LITE", "FULL", "DEV");
    public static final String GLOBAL = "GLOBAL";
    public static final String SEQUENCE = "mkt:cfg:seq";

    public static String snapshot(String form) {
        return "mkt:cfg:snapshot:" + form;
    }

    public static String version(String form) {
        return "mkt:cfg:version:" + form;
    }

    public static String schema(String service) {
        return "mkt:cfg:schema:" + service;
    }

    private ConfigKeys() {
    }
}
```

`ConfigForm.java`：

```java
package com.example.marketing.common.config;

/**
 * {@code DEPLOY_FORM} 的归一与白名单。
 *
 * <p>认不出的取值一律退回 GLOBAL 并由调用方告警：宁可少看到一层覆盖，
 * 也不能把某个形态的阈值套到形态名拼错的进程上。</p>
 */
public final class ConfigForm {

    public static String resolve(String raw) {
        if (raw == null) {
            return ConfigKeys.GLOBAL;
        }
        String normalized = raw.trim().toUpperCase();
        return ConfigKeys.FORMS.contains(normalized) ? normalized : ConfigKeys.GLOBAL;
    }

    /** 归一后仍是 GLOBAL 而原值非空 → 形态名写错了，要喊出来 */
    public static boolean unrecognized(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        return !ConfigKeys.FORMS.contains(raw.trim().toUpperCase());
    }

    private ConfigForm() {
    }
}
```

`ConfigMerge.java`：

```java
package com.example.marketing.common.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按形态合并 {@code admin_config} 行：当前 form 覆盖 GLOBAL，其它 form 一律忽略。
 *
 * <p>"两档共用同一份 MySQL"下的安全阀，也是全项目唯一一处 form 优先级实现——
 * admin 发布快照、单测、以及 ④ 的"期望值 vs 生效值"对比都必须走它；出现第二处就有漂移。</p>
 */
public final class ConfigMerge {

    public record Row(String cfgKey, String form, String cfgValue) {
    }

    public static Map<String, String> merge(String ownForm, List<Row> rows) {
        String form = ConfigForm.resolve(ownForm);
        Map<String, String> global = new LinkedHashMap<>();
        Map<String, String> own = new LinkedHashMap<>();
        for (Row row : rows) {
            String normalized = ConfigForm.resolve(row.form());
            if (normalized.equals(form)) {
                own.put(row.cfgKey(), row.cfgValue());
            } else if (normalized.equals(ConfigKeys.GLOBAL)) {
                global.put(row.cfgKey(), row.cfgValue());
            }
        }
        Map<String, String> merged = new LinkedHashMap<>(global);
        merged.putAll(own);
        return merged;
    }

    private ConfigMerge() {
    }
}
```

> `merge(" full ", ROWS)` 会命中 FULL 行、同时 GLOBAL 行也在结果里，所以那条断言只取 `.get("k.full")`——不要把整张 map 与单键比较。

- [ ] **Step 8: 写并跑 `ConfigFormTest`**

```java
package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFormTest {

    @Test
    @DisplayName("空值与未设置都按 GLOBAL 解析，且不算「形态名写错」")
    void blankMeansGlobal() {
        assertEquals("GLOBAL", ConfigForm.resolve(null));
        assertEquals("GLOBAL", ConfigForm.resolve(""));
        assertEquals("GLOBAL", ConfigForm.resolve("   "));
        assertFalse(ConfigForm.unrecognized(""));
        assertFalse(ConfigForm.unrecognized(null));
    }

    @Test
    @DisplayName("大小写无关；拼错的形态名退回 GLOBAL 并被标记")
    void caseInsensitiveAndTypoDetected() {
        assertEquals("LITE", ConfigForm.resolve(" lite "));
        assertEquals("FULL", ConfigForm.resolve("FULL"));
        assertTrue(ConfigForm.unrecognized("PREVIEW"));
        assertEquals("GLOBAL", ConfigForm.resolve("PREVIEW"));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest='ConfigMergeTest,ConfigFormTest'`
Expected: PASS（5 个用例）

- [ ] **Step 9: 写失败测试 —— 快照编解码与读取侧生效值**

`ConfigSnapshotCodecTest.java`：

```java
package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧"永不抛"是刻意的：坏载荷必须退化成空快照（=退回出厂值），
 * 而不是把异常抛进轮询线程、让它抱着上一次的陈旧值继续跑。
 */
class ConfigSnapshotCodecTest {

    @Test
    @DisplayName("写出再读回，版本/值/类型不丢")
    void roundTrip() {
        ConfigSnapshot s = new ConfigSnapshot(7L, "2026-09-23T10:00:00Z", Map.of(
                "gateway.ratelimit.coupon-route.limit",
                new ConfigSnapshot.Entry("120", ConfigType.INT, 5L)));
        ConfigSnapshot back = ConfigSnapshotCodec.read(ConfigSnapshotCodec.write(s));
        assertEquals(7L, back.version());
        assertEquals("120", back.entries().get("gateway.ratelimit.coupon-route.limit").value());
        assertEquals(ConfigType.INT, back.entries().get("gateway.ratelimit.coupon-route.limit").type());
    }

    @Test
    @DisplayName("null、空串、垃圾、字面量 null 都得到空快照")
    void garbageBecomesEmptySnapshot() {
        assertTrue(ConfigSnapshotCodec.read(null).entries().isEmpty());
        assertTrue(ConfigSnapshotCodec.read("{ not json").entries().isEmpty());
        assertTrue(ConfigSnapshotCodec.read("").entries().isEmpty());
        assertEquals(0L, ConfigSnapshotCodec.read("null").version());
    }

    @Test
    @DisplayName("载荷里出现读方不认识的字段时照旧解析（前向兼容）")
    void unknownFieldsIgnored() {
        String json = "{\"version\":3,\"generatedAt\":\"x\",\"extra\":1,"
                + "\"entries\":{\"a\":{\"value\":\"1\",\"type\":\"INT\",\"defVer\":0,\"future\":\"y\"}}}";
        assertEquals("1", ConfigSnapshotCodec.read(json).entries().get("a").value());
        assertEquals(3L, ConfigSnapshotCodec.read(json).version());
    }
}
```

`ConfigValuesTest.java`：

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取侧生效值：逐条按**本地声明**校验，未声明与非法都忽略并退回调用方给的出厂值。
 *
 * <p>"未声明就忽略"是"不一致时代码赢"的那一半：DB 里残留一行陈旧配置（比如参数已从代码里删掉）
 * 绝不能凭空生效，也不能凭空把服务搞挂。</p>
 */
class ConfigValuesTest {

    private static final ConfigDefinition LIMIT =
            ConfigDefinition.ofInt("gateway.ratelimit.coupon-route.limit", 1000, 1, 200000, "券领取阈值");
    private static final ConfigDefinition TTL =
            ConfigDefinition.ofLong("seckill.token-ttl-seconds", 600, 30, 86400, "排队 token 时长");

    private static ConfigValues values() {
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "unit-test";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(LIMIT, TTL);
            }
        }));
        return new ConfigValues(registry, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("快照里有合法值时用快照值")
    void appliesValidSnapshotValue() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(1L, "now",
                Map.of(LIMIT.key(), new ConfigSnapshot.Entry("120", ConfigType.INT, 0L))));
        assertEquals(120, v.intOr(LIMIT.key(), LIMIT.intDefault()));
        assertTrue(v.overridden(LIMIT.key()));
        assertEquals(1L, v.appliedVersion());
    }

    @Test
    @DisplayName("越界值逐条忽略、退回出厂值并记进 degraded；坏邻居不影响同批的好条目")
    void outOfRangeIgnoredPerEntry() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(2L, "now", Map.of(
                LIMIT.key(), new ConfigSnapshot.Entry("0", ConfigType.INT, 0L),
                TTL.key(), new ConfigSnapshot.Entry("60", ConfigType.INT, 0L))));
        assertEquals(1000, v.intOr(LIMIT.key(), 1000));
        assertEquals(60L, v.longOr(TTL.key(), 600L));
        assertEquals(List.of(LIMIT.key()), v.degradedKeys());
    }

    @Test
    @DisplayName("未声明的键忽略，取值走不到它")
    void undeclaredKeyIgnored() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(3L, "now",
                Map.of("discount.nope", new ConfigSnapshot.Entry("5", ConfigType.INT, 0L))));
        assertEquals(List.of("discount.nope"), v.degradedKeys());
        assertEquals(9, v.intOr("discount.nope", 9));
    }

    @Test
    @DisplayName("空快照 → 全部退回出厂值，overridden 为 false")
    void emptySnapshotMeansFactoryDefaults() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(1L, "now",
                Map.of(LIMIT.key(), new ConfigSnapshot.Entry("120", ConfigType.INT, 0L))));
        v.apply(ConfigSnapshot.empty());
        assertEquals(1000, v.intOr(LIMIT.key(), 1000));
        assertFalse(v.overridden(LIMIT.key()));
        assertEquals(0L, v.appliedVersion());
        assertTrue(v.degradedKeys().isEmpty());
    }

    @Test
    @DisplayName("类型不符的声明走 fallback（INT 键不会从 longOr 拿到值）")
    void typeMismatchFallsBack() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(1L, "now",
                Map.of(LIMIT.key(), new ConfigSnapshot.Entry("120", ConfigType.INT, 0L))));
        assertEquals(7L, v.longOr(LIMIT.key(), 7L), "LIMIT 声明的是 INT，longOr 不该用它");
    }
}
```

- [ ] **Step 10: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest='ConfigSnapshotCodecTest,ConfigValuesTest'`
Expected: 编译失败 `找不到符号 ConfigSnapshot`

- [ ] **Step 11: 实现 SPI、registry、快照、编解码与 `ConfigValues`**

`ConfigDefinitionProvider.java`：

```java
package com.example.marketing.common.config;

import java.util.List;

/**
 * 参数自述：每个模块声明"我有哪些参数可在线改、边界多少、出厂值多少"。
 *
 * <p>形状照 {@code CacheReheater}：能力长在参数所属的模块，后台只渲染与校验，
 * 不持有第二套清单。声明了却没有被任何 {@code configValues.xxxOr()} 消费的键，
 * 就是后台里一个"点了没反应"的按钮——那种键不要声明。</p>
 */
public interface ConfigDefinitionProvider {

    /**
     * 参数所属的<b>模块名</b>，不是进程名：LITE 下 discount 的参数跑在 marketing-standalone
     * 进程里，但归属仍是 marketing-discount，后台要显示后者。
     *
     * <p>Redis 自述键的后缀用的是 {@code spring.application.name}（进程名）——一个进程一份自述，
     * 而每条声明在载荷里额外带一个 {@code owner} 模块名。</p>
     */
    String service();

    List<ConfigDefinition> definitions();
}
```

`ConfigSchemaRegistry.java`：

```java
package com.example.marketing.common.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本进程收集到的声明合集，按 key 索引。
 *
 * <p>同一 key 被两个 provider 声明会在构造期失败而不是后写覆盖：一个键两处边界
 * 就是两套真相，与 {@code CacheReheatRegistry} 的处置同源。</p>
 */
public class ConfigSchemaRegistry {

    private final Map<String, ConfigDefinition> byKey = new LinkedHashMap<>();
    private final Map<String, String> serviceByKey = new LinkedHashMap<>();

    public ConfigSchemaRegistry(List<ConfigDefinitionProvider> providers) {
        for (ConfigDefinitionProvider provider : providers) {
            for (ConfigDefinition definition : provider.definitions()) {
                ConfigDefinition previous = byKey.putIfAbsent(definition.key(), definition);
                if (previous != null) {
                    throw new IllegalStateException("配置键重复声明: " + definition.key()
                            + "（已由 " + serviceByKey.get(definition.key()) + " 声明）");
                }
                serviceByKey.put(definition.key(), provider.service());
            }
        }
    }

    /** 空注册表：给"只按出厂值跑"的测试与裸 {@link ConfigValues#empty()} 用 */
    public static ConfigSchemaRegistry empty() {
        return new ConfigSchemaRegistry(List.of());
    }

    public Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public boolean declares(String key) {
        return byKey.containsKey(key);
    }

    public List<ConfigDefinition> all() {
        return List.copyOf(byKey.values());
    }

    /** key → 声明它的服务名（④ 展示归属、⑥ 分组渲染都取这个） */
    public Map<String, String> serviceByKey() {
        return Map.copyOf(serviceByKey);
    }
}
```

`ConfigSnapshot.java`：

```java
package com.example.marketing.common.config;

import java.util.Map;

/**
 * 一份自包含的全量配置快照。读方一次 GET 拿到全部生效值，
 * 因此不存在"改了 A 又改 B、读方拿到一新一旧"的半应用窗口。
 *
 * @param version     全局单调序号（来自 {@code mkt:cfg:seq}）
 * @param generatedAt 发布时刻（ISO-8601），只用于人看与 ④ 的"多久没更新"
 * @param defVer      发布时聚合到的 schema 版本，仅诊断用
 */
public record ConfigSnapshot(long version, String generatedAt, Map<String, Entry> entries) {

    public record Entry(String value, ConfigType type, long defVer) {
    }

    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, "", Map.of());
    }

    public ConfigSnapshot {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }
}
```

`ConfigSnapshotCodec.java`：

```java
package com.example.marketing.common.config;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

/**
 * 快照 JSON 编解码。读侧永不抛：坏载荷退化成空快照（全部回出厂值），
 * 而不是让轮询线程带伤抱着上一份陈旧值。
 */
public final class ConfigSnapshotCodec {

    private static final TypeReference<Map<String, Object>> ROOT = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, ConfigSnapshot.Entry>> ENTRIES = new TypeReference<>() {
    };

    public static String write(ConfigSnapshot snapshot) {
        return JsonUtils.toJson(snapshot);
    }

    public static ConfigSnapshot read(String json) {
        if (json == null || json.isBlank()) {
            return ConfigSnapshot.empty();
        }
        try {
            Map<String, Object> root = JsonUtils.parse(json, ROOT);
            if (root == null) {
                return ConfigSnapshot.empty();
            }
            long version = root.get("version") instanceof Number n ? n.longValue() : 0L;
            Object generatedAt = root.get("generatedAt");
            Map<String, ConfigSnapshot.Entry> entries = root.get("entries") == null
                    ? Map.of()
                    : JsonUtils.parse(JsonUtils.toJson(root.get("entries")), ENTRIES);
            return new ConfigSnapshot(version, generatedAt == null ? "" : generatedAt.toString(), entries);
        } catch (Exception e) {
            return ConfigSnapshot.empty();
        }
    }

    private ConfigSnapshotCodec() {
    }
}
```

`ConfigValues.java`：

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 读取侧的生效值容器：整个进程唯一一份，volatile 换指针，读路径零锁。
 *
 * <p>校验只在 {@link #apply} 做一次（每 5s 一次），不在取值时做——秒杀与领券是热路径，
 * 每次抢购都 parse 一遍数字是不能接受的。</p>
 *
 * <p>"缺值时退回哪"由调用方决定：它把 yml/代码出厂值作为 fallback 传进来，
 * 于是限流阈值天然就是母版 §5.4 的 FALLBACK_YML 语义——既不过限，也绝不让网关起不来。</p>
 */
public class ConfigValues {

    private final ConfigSchemaRegistry registry;
    private final MeterRegistry meters;
    private final AtomicReference<Map<String, String>> effective = new AtomicReference<>(Map.of());
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());
    private final AtomicReference<List<String>> degraded = new AtomicReference<>(List.of());

    public ConfigValues(ConfigSchemaRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
        meters.gauge("marketing.config.snapshot.version", snapshot, s -> (double) s.get().version());
        meters.gauge("marketing.config.degraded.size", degraded, d -> (double) d.get().size());
    }

    /** 无在线覆盖的裸容器：给单测与"只按出厂值跑"的路径用 */
    public static ConfigValues empty() {
        return new ConfigValues(ConfigSchemaRegistry.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public void apply(ConfigSnapshot next) {
        Map<String, String> accepted = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, ConfigSnapshot.Entry> e : next.entries().entrySet()) {
            Optional<ConfigDefinition> def = registry.find(e.getKey());
            if (def.isEmpty() || !def.get().accepts(e.getValue().value())) {
                ignored.add(e.getKey());
                Counter.builder("marketing.config.entry.ignored")
                        .tag("key", e.getKey())
                        .register(meters)
                        .increment();
                continue;
            }
            accepted.put(e.getKey(), e.getValue().value().trim());
        }
        effective.set(Map.copyOf(accepted));
        degraded.set(List.copyOf(ignored));
        snapshot.set(next);
    }

    public int intOr(String key, int fallback) {
        Long v = coerce(key, ConfigType.INT);
        return v == null ? fallback : v.intValue();
    }

    public long longOr(String key, long fallback) {
        Long v = coerce(key, ConfigType.LONG);
        return v == null ? fallback : v;
    }

    public String stringOr(String key, String fallback) {
        Optional<ConfigDefinition> def = registry.find(key);
        if (def.isEmpty() || def.get().type() != ConfigType.STRING) {
            return fallback;
        }
        String raw = effective.get().get(key);
        return raw == null ? fallback : raw;
    }

    /** 当前这个键的值确实来自在线快照（而不是 fallback） */
    public boolean overridden(String key) {
        return effective.get().containsKey(key);
    }

    public long appliedVersion() {
        return snapshot.get().version();
    }

    /** 最近一次 apply 里被忽略的键：④ 的 degraded 展示与冒烟断言都读它 */
    public List<String> degradedKeys() {
        return degraded.get();
    }

    private Long coerce(String key, ConfigType expect) {
        Optional<ConfigDefinition> def = registry.find(key);
        if (def.isEmpty() || def.get().type() != expect) {
            return null;
        }
        // effective 里的值已在 apply 时校验过，这里只数值化，不重复边界判断
        return def.get().coerce(effective.get().get(key));
    }
}
```

- [ ] **Step 12: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest='ConfigSnapshotCodecTest,ConfigValuesTest'`
Expected: PASS（3+5 = 8 个用例）

- [ ] **Step 13: 写并跑 registry 测试**

`ConfigSchemaRegistryTest.java`：

```java
package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个键两处声明 = 两套真相，必须启动期失败。
 * 把 putIfAbsent 改成 put 之后第一条必须红，否则这条测试是空的。
 */
class ConfigSchemaRegistryTest {

    private static ConfigDefinitionProvider provider(String service, ConfigDefinition... defs) {
        return new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return service;
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(defs);
            }
        };
    }

    @Test
    @DisplayName("同键重复声明在构造期抛错，并点名键与先来者")
    void duplicateKeyFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ConfigSchemaRegistry(List.of(
                        provider("gateway", ConfigDefinition.ofInt("a", 1, 0, 10, "x")),
                        provider("admin", ConfigDefinition.ofInt("a", 2, 0, 10, "y")))));
        assertTrue(e.getMessage().contains("a"));
        assertTrue(e.getMessage().contains("gateway"));
    }

    @Test
    @DisplayName("find/declares/serviceByKey 与声明一致")
    void collectsAcrossProviders() {
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(
                provider("gateway", ConfigDefinition.ofInt("a", 1, 0, 10, "x")),
                provider("seckill", ConfigDefinition.ofLong("b", 1L, 0L, 10L, "y"))));
        assertEquals(2, registry.all().size());
        assertEquals("seckill", registry.serviceByKey().get("b"));
        assertEquals(ConfigType.INT, registry.find("a").orElseThrow().type());
        assertFalse(registry.declares("c"));
        assertTrue(registry.declares("b"));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigSchemaRegistryTest`
Expected: PASS（2 个用例）

- [ ] **Step 14: 变异检查（证明断言真的咬人）**

逐条临时改动，每次都要看到指名的测试变红，改回后再全绿：

1. `ConfigMerge.merge` 把 `else if (normalized.equals(ConfigKeys.GLOBAL))` 改成 `else` → `liteSeesOwnAndGlobal` 与 `unsetFormOnlySeesGlobal` 必须红。
2. `ConfigValues.apply` 去掉 `!def.get().accepts(...)` 这半个条件 → `outOfRangeIgnoredPerEntry` 必须红。
3. `ConfigSchemaRegistry` 的 `putIfAbsent` → `put` → `duplicateKeyFailsFast` 必须红。
4. `ConfigDefinition.coerce` 删掉 `v < min || v > max` 判断 → `intBounds` 必须红。

Run（每轮）: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest='ConfigMergeTest,ConfigValuesTest,ConfigSchemaRegistryTest,ConfigDefinitionTest'`

- [ ] **Step 15: 提交**

```bash
git add marketing-common/src/main/java/com/example/marketing/common/config \
        marketing-common/src/main/resources/META-INF \
        marketing-common/src/test/java/com/example/marketing/common/config
git commit -m "feat(config): 在线配置的契约与生效值核心（形态合并/逐条校验/自包含快照编解码）"
```

---

### Task 2: common 的装配与阻塞侧轮询器（含 schema 自述载荷）

**Files:**
- Create: `marketing-common/src/main/java/com/example/marketing/common/config/ConfigSchemaPayload.java`
- Create: `.../common/config/ConfigSchemaCodec.java`
- Create: `.../common/config/ConfigSnapshotPoller.java`
- Create: `.../common/config/ConfigCommonAutoConfiguration.java`
- Modify: `marketing-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `marketing-common/src/test/java/com/example/marketing/common/config/ConfigSnapshotPollerTest.java`
- Test: `.../common/config/ConfigCommonAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ConfigKeys`、`ConfigForm`、`ConfigValues`、`ConfigSnapshot`、`ConfigSnapshotCodec`、`ConfigSchemaRegistry`、`ConfigDefinitionProvider`、`ConfigDefinition`
- Produces:
  - `ConfigSchemaPayload(long generatedAt, String service, List<Map<String,Object>> definitions)`
  - `ConfigSchemaCodec.write(ConfigSchemaPayload)` / `ConfigSchemaCodec.read(String)`（坏载荷返回 null）
  - `ConfigSnapshotPoller(StringRedisTemplate redis, ConfigValues values, ConfigSchemaRegistry registry, String ownForm, String service, long pollSeconds, MeterRegistry meters)` + `void start()` + `void stop()` + 包内可见 `void refreshOnce()` / `void publishSchema()`
  - Bean：`configSchemaRegistry`、`configValues`、`configSnapshotPoller`（第三个仅当存在 `StringRedisTemplate`）
  - 属性：`marketing.config.form`（默认空）、`marketing.config.poll-seconds`（默认 5）

- [ ] **Step 1: 实现 schema 载荷与编解码**

`ConfigSchemaPayload.java`：

```java
package com.example.marketing.common.config;

import java.util.List;
import java.util.Map;

/**
 * 某个服务自述的可改参数清单（Redis 载荷）。
 *
 * <p>用 {@code List<Map>} 而不是 {@code List<ConfigDefinition>}：Map 的键集合就是协议本身，
 * 服务端加字段不会让旧版 admin 反序列化失败，而 record 加字段会让所有旧载荷读不出来。</p>
 */
public record ConfigSchemaPayload(long generatedAt, String service, List<Map<String, Object>> definitions) {
}
```

`ConfigSchemaCodec.java`：

```java
package com.example.marketing.common.config;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * schema 载荷编解码。读侧坏载荷返回 null：admin 面对一份写错的自述，
 * 应当"看不见这个服务的参数"并把它列进 unreported，而不是整个配置页 500。
 */
public final class ConfigSchemaCodec {

    public static String write(ConfigSchemaPayload payload) {
        return JsonUtils.toJson(payload);
    }

    public static ConfigSchemaPayload read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, new TypeReference<ConfigSchemaPayload>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigSchemaCodec() {
    }
}
```

- [ ] **Step 2: 写失败测试 —— 轮询器的四条路径**

`ConfigSnapshotPollerTest.java`。四条各自钉住一个"会静默出事"的分支：版本没变不该取快照、变了必须取并应用、Redis 异常必须**保住现值**（一次网络抖动不能把阈值打回出厂）、快照键被删必须退回出厂（链路 5 的验收路径）。

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigSnapshotPollerTest {

    private static final String KEY = "gateway.ratelimit.coupon-route.limit";

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ConfigValues values;
    private ConfigSnapshotPoller poller;
    private volatile boolean failNext;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> {
            if (failNext) {
                throw new IllegalStateException("redis down");
            }
            return store.get(inv.<String>getArgument(0));
        });
        // ValueOperations.set(K,V) 返回 void：桩它只能用 doAnswer/doThrow，when() 编不过
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString());
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "marketing-gateway";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(ConfigDefinition.ofInt(KEY, 1000, 1, 200000, "券阈值"));
            }
        }));
        values = new ConfigValues(registry, new SimpleMeterRegistry());
        poller = new ConfigSnapshotPoller(redis, values, registry, "LITE", "marketing-gateway", 5,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        poller.stop();
    }

    private void putSnapshot(long version, String value) {
        store.put(ConfigKeys.version("LITE"), String.valueOf(version));
        store.put(ConfigKeys.snapshot("LITE"), ConfigSnapshotCodec.write(new ConfigSnapshot(
                version, "now", Map.of(KEY, new ConfigSnapshot.Entry(value, ConfigType.INT, 1L)))));
    }

    @Test
    @DisplayName("版本变了才取快照并应用；同版本再刷不重复取全量")
    void fetchesSnapshotOnlyWhenVersionChanged() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        assertEquals(120, values.intOr(KEY, 1000));
        assertEquals(3L, values.appliedVersion());
        verify(ops, times(1)).get(ConfigKeys.snapshot("LITE"));

        poller.refreshOnce();
        verify(ops, times(1)).get(ConfigKeys.snapshot("LITE"));
    }

    @Test
    @DisplayName("快照键被删 → 退回出厂值（不报错、不保持旧值）")
    void deletedSnapshotFallsBackToFactory() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        store.remove(ConfigKeys.version("LITE"));
        store.remove(ConfigKeys.snapshot("LITE"));
        poller.refreshOnce();
        assertEquals(1000, values.intOr(KEY, 1000));
        assertEquals(0L, values.appliedVersion());
    }

    @Test
    @DisplayName("Redis 抛异常时保住上一次生效值，不把阈值打回出厂")
    void redisFailureKeepsLastApplied() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        failNext = true;
        poller.refreshOnce();
        assertEquals(120, values.intOr(KEY, 1000));
        assertEquals(3L, values.appliedVersion());
    }

    @Test
    @DisplayName("schema 自述写进本服务自己的键，内容含键与边界")
    void publishesOwnSchema() {
        poller.publishSchema();
        String json = store.get(ConfigKeys.schema("marketing-gateway"));
        assertNotNull(json, "自述必须落在 mkt:cfg:schema:{service}");
        assertTrue(json.contains(KEY), "自述里要能看到自己声明的键: " + json);
        assertTrue(json.contains("200000"), "自述里要带上边界，否则 admin 无法校验: " + json);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigSnapshotPollerTest`
Expected: 编译失败 `找不到符号 ConfigSnapshotPoller`

- [ ] **Step 4: 实现 `ConfigSnapshotPoller`**

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阻塞侧的快照轮询器：每 {@code pollSeconds} 比对版本，变了才取全量快照。
 *
 * <p>用自起的 daemon 线程而不是 {@code @Scheduled}：六个进程里只有 standalone 标了
 * {@code @EnableScheduling}，靠它就得给每个模块各加一处开关，而漏加的表现是
 * "在线改完没反应"——那是最难查的一类问题。</p>
 *
 * <p>Redis 异常一律保住上一次生效值并记计数：一次抖动不该把限流阈值打回出厂值。
 * 与"快照被删则退回出厂"是两条相反的路径，区分它们的是"Redis 说不存在"与"Redis 没说清"。</p>
 */
@Slf4j
public class ConfigSnapshotPoller {

    /** 每这么久重投一次 schema（Redis 被清空后自愈），与轮询节拍共用一个计数器 */
    private static final long SCHEMA_REPUBLISH_SECONDS = 60L;

    private final StringRedisTemplate redis;
    private final ConfigValues values;
    private final ConfigSchemaRegistry registry;
    private final String ownForm;
    private final String service;
    private final long pollSeconds;
    private final MeterRegistry meters;
    private final AtomicLong tick = new AtomicLong();
    private volatile ScheduledExecutorService scheduler;

    public ConfigSnapshotPoller(StringRedisTemplate redis, ConfigValues values,
                                ConfigSchemaRegistry registry, String ownForm,
                                String service, long pollSeconds, MeterRegistry meters) {
        this.redis = redis;
        this.values = values;
        this.registry = registry;
        this.ownForm = ConfigForm.resolve(ownForm);
        this.service = service;
        this.pollSeconds = Math.max(1L, pollSeconds);
        this.meters = meters;
        if (ConfigForm.unrecognized(ownForm)) {
            log.warn("[config] DEPLOY_FORM 取值 '{}' 无法识别，按 GLOBAL 解析（只认全局覆盖）", ownForm);
        } else if (ownForm == null || ownForm.trim().isEmpty()) {
            log.info("[config] 未设置 DEPLOY_FORM，在线配置只对 form=GLOBAL 的行生效");
        }
    }

    public void start() {
        // 启动先取一次：否则冷启动后第一个 5s 跑在出厂阈值上，改过的值"看起来没生效"
        refreshOnce();
        publishSchema();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-config-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    /** 读版本 → 变了读快照 → 逐条校验后应用；任何异常都吃掉并保住现值 */
    void refreshOnce() {
        try {
            String raw = redis.opsForValue().get(ConfigKeys.version(ownForm));
            long current = raw == null ? 0L : Long.parseLong(raw.trim());
            if (current == values.appliedVersion() && current != 0L) {
                maybeRepublishSchema();
                return;
            }
            ConfigSnapshot snapshot = ConfigSnapshotCodec.read(
                    redis.opsForValue().get(ConfigKeys.snapshot(ownForm)));
            values.apply(snapshot);
            List<String> degraded = values.degradedKeys();
            if (!degraded.isEmpty()) {
                log.warn("[config] form={} 快照中 {} 个条目未被采纳（未声明或越界），已退回出厂值: {}",
                        ownForm, degraded.size(), degraded);
            }
            log.info("[config] form={} 生效快照 version={}, entries={}, degraded={}",
                    ownForm, snapshot.version(), snapshot.entries().size(), degraded.size());
            maybeRepublishSchema();
        } catch (Exception e) {
            meters.counter("marketing.config.poll.error").increment();
            log.warn("[config] 刷新失败，沿用上一次生效值 version={}: {}",
                    values.appliedVersion(), e.toString());
        }
    }

    void publishSchema() {
        if (registry.all().isEmpty()) {
            // 没有可改参数的服务不写空自述：那会让 admin 的 unreported 失去意义
            return;
        }
        try {
            List<Map<String, Object>> defs = new ArrayList<>();
            Map<String, String> owners = registry.serviceByKey();
            for (ConfigDefinition d : registry.all()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", d.key());
                // owner 是模块名：LITE 下进程统一是 standalone，但参数归属仍按模块显示
                one.put("owner", owners.getOrDefault(d.key(), service));
                one.put("type", d.type().name());
                one.put("min", d.min());
                one.put("max", d.max());
                one.put("defaultValue", d.defaultValue());
                one.put("description", d.description() == null ? "" : d.description());
                defs.add(one);
            }
            redis.opsForValue().set(ConfigKeys.schema(service), ConfigSchemaCodec.write(
                    new ConfigSchemaPayload(Instant.now().getEpochSecond(), service, defs)));
        } catch (Exception e) {
            log.warn("[config] schema 自述写入失败（不影响本进程取值）: {}", e.toString());
        }
    }

    private void maybeRepublishSchema() {
        long every = Math.max(1L, SCHEMA_REPUBLISH_SECONDS / pollSeconds);
        if (tick.incrementAndGet() % every == 0) {
            publishSchema();
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigSnapshotPollerTest`
Expected: PASS（4 个用例）

- [ ] **Step 6: 写失败测试 —— 装配条件矩阵**

`ConfigCommonAutoConfigurationTest.java`。这里钉的是母版事实 #8：网关没有 DataSource，装配必须照样成立，否则在线限流恰好在那个最需要它的进程里不生效。

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配条件。用 ApplicationContextRunner 而不是真中间件：
 * Lettuce 是"建 bean 不建连接"，所以端口 1 也够用来验证"没有 StringRedisTemplate 就不起线程"。
 */
class ConfigCommonAutoConfigurationTest {

    private static LettuceConnectionFactory neverConnecting() {
        LettuceConnectionFactory f = new LettuceConnectionFactory("127.0.0.1", 1);
        f.afterPropertiesSet();
        return f;
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ConfigDefinitionProvider.class, () -> new ConfigDefinitionProvider() {
                    @Override
                    public String service() {
                        return "unit";
                    }

                    @Override
                    public List<ConfigDefinition> definitions() {
                        return List.of(ConfigDefinition.ofInt("a", 1, 0, 9, "x"));
                    }
                })
                .withConfiguration(AutoConfigurations.of(ConfigCommonAutoConfiguration.class));
    }

    @Test
    @DisplayName("没有 DataSource 也装配 registry 与 ConfigValues（网关那条路）")
    void wiresWithoutDataSource() {
        runner().run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(ConfigSchemaRegistry.class).length);
            assertEquals(1, ctx.getBeanNamesForType(ConfigValues.class).length);
            assertEquals(1, ctx.getBean(ConfigSchemaRegistry.class).all().size());
            assertFalse(ctx.getBeanNamesForType(ConfigSnapshotPoller.class).length > 0,
                    "没有 StringRedisTemplate 就不该起轮询线程");
        });
    }

    @Test
    @DisplayName("有 StringRedisTemplate 时装配轮询器；连不上也不炸，只是刷不出值")
    void pollerWiredWithRedis() {
        LettuceConnectionFactory f = neverConnecting();
        try {
            runner().withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(f))
                    .withPropertyValues("marketing.config.form=LITE", "marketing.config.poll-seconds=2")
                    .run(ctx -> {
                        assertEquals(1, ctx.getBeanNamesForType(ConfigSnapshotPoller.class).length);
                        assertEquals(0L, ctx.getBean(ConfigValues.class).appliedVersion());
                    });
        } finally {
            f.destroy();
        }
    }

    @Test
    @DisplayName("两个 provider 声明同一个键时上下文启动失败（而不是静默覆盖）")
    void duplicateKeyFailsContext() {
        runner().withBean("secondProvider", ConfigDefinitionProvider.class, () -> new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "unit2";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(ConfigDefinition.ofInt("a", 2, 0, 9, "y"));
            }
        }).run(ctx -> {
            // registry 是懒解析的：起不来的证据可能在 getBean 时才抛出，两条路都要真断到
            Throwable failure = ctx.getStartupFailure();
            if (failure != null) {
                assertTrue(hasIllegalStateCause(failure), "根因应是重复键检查: " + failure);
                return;
            }
            assertThrows(IllegalStateException.class, () -> ctx.getBean(ConfigSchemaRegistry.class));
        });
    }

    private static boolean hasIllegalStateCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IllegalStateException) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 7: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test -Dtest=ConfigCommonAutoConfigurationTest`
Expected: 编译失败 `找不到符号 ConfigCommonAutoConfiguration`

- [ ] **Step 8: 实现 `ConfigCommonAutoConfiguration` 并登记 imports**

```java
package com.example.marketing.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 在线配置的读取侧装配。
 *
 * <p><b>为什么不塞进 {@code MarketingCommonAutoConfiguration}</b>：那个类整体挂着
 * {@code @ConditionalOnBean(DataSource)}，而网关没有库。塞进去的净结果是
 * "在线限流恰好在网关里不生效"。</p>
 *
 * <p>本类只条件依赖 micrometer 与（可选）StringRedisTemplate。网关只有 reactive 模板，
 * 于是它的轮询件在网关模块自己实现（GatewayConfigSyncer），这里自然不装阻塞线程。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
public class ConfigCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigSchemaRegistry configSchemaRegistry(ObjectProvider<ConfigDefinitionProvider> providers) {
        return new ConfigSchemaRegistry(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigValues configValues(ConfigSchemaRegistry registry, MeterRegistry meters) {
        return new ConfigValues(registry, meters);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public ConfigSnapshotPoller configSnapshotPoller(StringRedisTemplate redis,
                                                    ConfigValues values,
                                                    ConfigSchemaRegistry registry,
                                                    MeterRegistry meters,
                                                    @Value("${marketing.config.form:}") String form,
                                                    @Value("${spring.application.name:unknown}") String service,
                                                    @Value("${marketing.config.poll-seconds:5}") long pollSeconds) {
        return new ConfigSnapshotPoller(redis, values, registry, form, service, pollSeconds, meters);
    }
}
```

`ConfigSchemaRegistry.empty()` 与 `ConfigValues.empty()` 已在 Task 1 Step 11 就位，Task 5/7 的测试直接用它们。

imports 文件（现有只有一行）追加：

```
com.example.marketing.common.config.MarketingCommonAutoConfiguration
com.example.marketing.common.config.ConfigCommonAutoConfiguration
```

- [ ] **Step 9: 跑测试确认通过 + common 全量回归**

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test`
Expected: 全绿。common 从 6 个测试类涨到 9 个（+`ConfigDefinition/ConfigMerge/ConfigForm/ConfigSnapshotCodec/ConfigValues/ConfigSchemaRegistry/ConfigSnapshotPoller/ConfigCommonAutoConfiguration` 中的前 8 项分布；按实际文件数计），且不出现任何既有测试变红。

- [ ] **Step 10: 变异检查**

1. 摘掉 `@ConditionalOnBean(StringRedisTemplate.class)` → `wiresWithoutDataSource` 最后一句必须红。
2. `refreshOnce()` 里删掉"版本未变就返回"那个分支 → `fetchesSnapshotOnlyWhenVersionChanged` 的 `times(1)` 必须红。
3. `catch (Exception e)` 改成"应用空快照" → `redisFailureKeepsLastApplied` 必须红。
4. `publishSchema()` 的键从 `ConfigKeys.schema(service)` 改成硬编码 `"mkt:cfg:schema"` → `publishesOwnSchema` 必须红。

- [ ] **Step 11: 提交**

```bash
git add marketing-common/src/main/java/com/example/marketing/common/config \
        marketing-common/src/main/resources/META-INF \
        marketing-common/src/test/java/com/example/marketing/common/config
git commit -m "feat(config): 不带 DataSource 条件的配置装配 + 阻塞侧快照轮询器 + schema 自述"
```

---

### Task 3: DDL —— `admin_config` 真值表与 `activity` 灰度两列

**Files:**
- Modify: `docker/mysql/init/01-schema.sql`（`activity` 建表段 `:43-58`、种子段 `:100-103`、admin 段 `admin_audit_log` 之后）
- Modify: `docker/mysql/init-lite/01-schema-lite.sql`（对应三处）
- Create: `docker/mysql/migrate/2026-09-23-admin-config.sql`

**Interfaces:**
- Consumes: 无
- Produces: 表 `admin_config(cfg_key, form, cfg_value, version, updated_by, remark)`（列名与 Task 5 的 SQL 逐字一致）、列 `activity.gray_percent` / `activity.gray_whitelist`（与 Task 6 的实体字段对应）、种子 `ACT2026001.gray_percent = 100`

- [ ] **Step 1: 两份 init DDL 各加两列**

在 `activity` 建表语句里，`remark` 之后、`version` 之前插入（**init 与 init-lite 两份逐字相同**）：

```sql
    gray_percent   INT          NULL COMMENT '灰度放量百分比 0-100；NULL=未配灰度=全量放行',
    gray_whitelist VARCHAR(255) NULL COMMENT '灰度白名单 userId CSV；NULL 或空=无白名单',
```

- [ ] **Step 2: 两份 init DDL 各加 `admin_config`**

在 `admin_audit_log` 建表语句之后追加（init 版此时在 `marketing_admin` 库、init-lite 版在单库 `marketing` 内，所以两份内容一致、位置各一份）：

```sql
-- 在线配置真值。只有被代码里 ConfigDefinitionProvider 声明过的键才会被读方采纳：
-- "不一致时代码赢"，DB 里的陈旧行既不生效也不报错，由 ④ 的 ORPHAN 清单显式暴露。
-- 删行 = 恢复出厂（不是写回原值，否则 yml 改了会被一行陈旧 DB 值永远压住）。
CREATE TABLE IF NOT EXISTS admin_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    cfg_key     VARCHAR(64)  NOT NULL COMMENT '参数键，与 ConfigDefinition.key 一致',
    form        VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/LITE/FULL/DEV',
    cfg_value   VARCHAR(255) NOT NULL COMMENT '按声明的 type 解析；越界或类型不符时读方逐条忽略',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '写入时 mkt:cfg:seq 的值，仅用于展示第几版生效',
    updated_by  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后一次写的后台账号',
    remark      VARCHAR(255) NOT NULL DEFAULT '',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_form (cfg_key, form)
) ENGINE = InnoDB COMMENT '在线配置真值（删行即恢复出厂）';
```

- [ ] **Step 3: 种子带上灰度值**

两份文件里把 `INSERT INTO activity (...)` 的列清单与值各扩一项（**这是链路 0 第 63-64 行断言在 Task 6 之后的唯一支撑**）：

```sql
-- 种子：一个在线活动（灰度 100% 与原 yml marketing.gray.ACT2026001.percent 等值，Task 6 删 yml 那份）
INSERT INTO activity (activity_no, name, status, start_time, end_time, budget_amount, used_amount, remark, gray_percent)
SELECT 'ACT2026001', '2026 秋季大促', 'ONLINE', NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 365 DAY, 1000000.00, 0.00, '脚手架演示活动', 100
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE activity_no = 'ACT2026001');
```

- [ ] **Step 4: 写迁移脚本（给已建好的卷，必须能独立执行）**

`docker/mysql/migrate/2026-09-23-admin-config.sql` 全文：

```sql
-- ============================================================
-- 迁移 ⑤：在线配置下发的落库面
--   适用：本变更之前已建好的 MySQL 卷（含常驻数据卷）
--   新库不需要执行（init 与 init-lite 两份 DDL 已含表与列）
--
--   对"每个装着 activity / admin_* 表的库"都要执行一遍：
--     单库档      → mysql -umarketing -p marketing           < 本文件
--     四库隔离档  → 对 marketing_activity 与 marketing_admin 各执行一遍
--   （admin_config 只应存在于装了 admin_user 的那个库；activity 列只存在于有 activity 的库，
--    缺哪张表时对应语句会报错，按库裁剪即可——与 2026-09-22-bizkey-scope.sql 同一处置）
--
--   SET NAMES 必需：本文件有中文 COMMENT，客户端默认字符集不是 utf8mb4 时会把中文
--   按 latin1 写进去（2026-09-22 的种子就是这个坑，见 fix-seed-encoding.sql 的成因说明）。
-- ============================================================
SET NAMES utf8mb4;

-- 1) activity 灰度两列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，用 information_schema 兜幂等）
SET @s := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE activity ADD COLUMN gray_percent INT NULL COMMENT ''灰度放量百分比 0-100；NULL=未配灰度=全量放行''',
    'DO 0') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'activity' AND column_name = 'gray_percent');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE activity ADD COLUMN gray_whitelist VARCHAR(255) NULL COMMENT ''灰度白名单 userId CSV；NULL 或空=无白名单''',
    'DO 0') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'activity' AND column_name = 'gray_whitelist');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 既有卷的 ACT2026001 补种子：链路 0 断言"percent=100 命中"，yml 那份在 Task 6 删掉
UPDATE activity SET gray_percent = 100
 WHERE activity_no = 'ACT2026001' AND gray_percent IS NULL;

-- 3) 真值表
CREATE TABLE IF NOT EXISTS admin_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    cfg_key     VARCHAR(64)  NOT NULL COMMENT '参数键，与 ConfigDefinition.key 一致',
    form        VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/LITE/FULL/DEV',
    cfg_value   VARCHAR(255) NOT NULL COMMENT '按声明的 type 解析；越界或类型不符时读方逐条忽略',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '写入时 mkt:cfg:seq 的值，仅用于展示第几版生效',
    updated_by  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后一次写的后台账号',
    remark      VARCHAR(255) NOT NULL DEFAULT '',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_form (cfg_key, form)
) ENGINE = InnoDB COMMENT '在线配置真值（删行即恢复出厂）';

-- 4) 自检：期望 gray_columns=2、admin_config 存在、ACT2026001 的灰度是 100
SELECT COUNT(*) AS gray_columns FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'activity'
   AND column_name IN ('gray_percent', 'gray_whitelist');
SELECT COUNT(*) AS admin_config_exists FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'admin_config';
SELECT activity_no, gray_percent FROM activity WHERE activity_no = 'ACT2026001';
```

- [ ] **Step 5: 在常驻库上真实执行迁移（不是只看语法）**

```bash
docker compose -f docker/docker-compose.data.yml up -d --wait
docker exec -i mkt-mysql mysql -umarketing -pmarketing123 marketing \
  < docker/mysql/migrate/2026-09-23-admin-config.sql
```

Expected: `gray_columns=2`、`ACT2026001 | 100`；`admin_config_exists` 按库而变——

| 执行的库 | `admin_config_exists` 期望 | 为什么 |
|---|---|---|
| `marketing`（单库档，LITE/dev 用的就是它） | **1** | 业务表与 admin_* 表同库，真值表也该在这 |
| `marketing_activity`（隔离档） | **0** | 真值表只属于 admin 那个库，这里不该有 |
| `marketing_admin`（隔离档） | **1** | 它就是 admin 的库 |

把期望写死成一个值就是错的：三种取值各对应一个真实姿态，看到 0 或 1 要先对上这张表再判对错。

再把同一份脚本**原样重跑一遍**：Expected 全部语句无报错（幂等性），`gray_columns` 仍为 2。然后对隔离档（若 `marketing_activity` / `marketing_admin` 两库仍在）各执行一遍：

```bash
docker exec -i mkt-mysql mysql -umarketing -pmarketing123 marketing_activity \
  < docker/mysql/migrate/2026-09-23-admin-config.sql
docker exec -i mkt-mysql mysql -umarketing -pmarketing123 marketing_admin \
  < docker/mysql/migrate/2026-09-23-admin-config.sql
```

- [ ] **Step 6: 字符集回归检查（latin1 坑的专项）**

```bash
docker exec mkt-mysql mysql -umarketing -pmarketing123 -e \
  "SELECT column_comment FROM information_schema.columns \
   WHERE table_schema='marketing' AND table_name='activity' AND column_name='gray_percent'"
```
Expected: 读回来是"灰度放量百分比 0-100；NULL=未配灰度=全量放行"，不是 `åº¦æ...` 这类双重编码。

- [ ] **Step 7: 验证新卷走 init 路径**

用一次性容器验证两份 init 脚本能整份执行，**不碰常驻卷**（常驻卷里有本轮全部测试数据，删卷不在本计划的授权范围内）：

```bash
docker run --rm --name mkt-ddl-check \
  -v "$PWD/docker/mysql/init:/docker-entrypoint-initdb.d:ro" \
  -e MYSQL_ROOT_PASSWORD=check \
  "$(awk -F'[ ]' '/image: mysql/{print $2}' docker/docker-compose.data.yml | head -1)" \
  sh -c 'docker-entrypoint.sh mysqld --skip-networking=0 --port=3399 >/dev/null 2>&1 &
         for i in $(seq 1 60); do mysqladmin -uroot -pcheck ping >/dev/null 2>&1 && break; sleep 1; done
         mysql -uroot -pcheck -e "SHOW DATABASES"
         mysql -uroot -pcheck marketing_activity -e "SHOW CREATE TABLE admin_config\G" | head -20'
```
Expected: 建库与建表全部成功，无 `Unknown column` / syntax 报错。若 `awk` 取镜像名失败，直接写 `docker-compose.data.yml` 里那个 mysql 镜像标签。init-lite 那份把挂载目录换成 `docker/mysql/init-lite`、目标库换成 `marketing`，同法跑一次。

- [ ] **Step 8: 提交**

```bash
git add docker/mysql/init/01-schema.sql docker/mysql/init-lite/01-schema-lite.sql \
        docker/mysql/migrate/2026-09-23-admin-config.sql
git commit -m "feat(config): admin_config 真值表与 activity 灰度两列（init/init-lite/迁移三份）"
```

---

### Task 4: 网关限流阈值在线化（没有库的那个进程）

**Files:**
- Create: `marketing-gateway/src/main/java/com/example/marketing/gateway/config/GatewayConfigDefinitions.java`
- Create: `.../gateway/config/RateRuleResolver.java`
- Create: `.../gateway/config/GatewayConfigSyncer.java`
- Modify: `.../gateway/filter/RateLimitFilter.java`（构造器 + `:52-58`）
- Modify: `marketing-gateway/src/main/resources/application.yml`（`marketing.config.*`）
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/config/RateRuleResolverTest.java`
- Test: `.../gateway/config/GatewayConfigDefinitionsTest.java`
- Test: `.../gateway/config/GatewayRouteTableTest.java`
- Test: `.../gateway/config/GatewayConfigSyncerTest.java`

**Interfaces:**
- Consumes: Task 1/2 的 `ConfigValues`、`ConfigDefinition`、`ConfigDefinitionProvider`、`ConfigSchemaRegistry`、`ConfigSnapshot`、`ConfigSnapshotCodec`、`ConfigKeys`、`ConfigForm`、`ConfigSchemaPayload`、`ConfigSchemaCodec`；`GatewayProperties.RateRule`
- Produces:
  - `GatewayConfigDefinitions.keyOf(String routeId)` → `"gateway.ratelimit." + routeId + ".limit"`
  - `RateRuleResolver.resolve(String routeId, GatewayProperties.RateRule ymlRule, ConfigValues values)` → `RateRuleResolver.Outcome(GatewayProperties.RateRule rule, boolean fromSnapshot, boolean ignored)`
  - `GatewayConfigSyncer`（`@PostConstruct` 起 reactive 轮询、`void syncOnce()` 包内可见）

- [ ] **Step 1: 写失败测试 —— 阈值的四种来源**

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流阈值取值来源。钉的是母版风险 #1 的另一半：快照坏/越界时退回 yml 出厂值——
 * 既不能过限，更不能让网关因此拒绝服务。
 */
class RateRuleResolverTest {

    private static final String ROUTE = "seckill-route";

    private static ConfigValues valuesWith(String rawValue) {
        ConfigValues values = new ConfigValues(
                new ConfigSchemaRegistry(List.of(new GatewayConfigDefinitions())), new SimpleMeterRegistry());
        if (rawValue != null) {
            values.apply(new ConfigSnapshot(1L, "now", Map.of(
                    GatewayConfigDefinitions.keyOf(ROUTE),
                    new ConfigSnapshot.Entry(rawValue, ConfigType.INT, 0L))));
        }
        return values;
    }

    private static GatewayProperties.RateRule yml() {
        GatewayProperties.RateRule r = new GatewayProperties.RateRule();
        r.setLimit(200);
        r.setWindowSeconds(1);
        return r;
    }

    @Test
    @DisplayName("快照有合法值时用快照值，窗口仍取 yml")
    void snapshotWinsOnLimitOnly() {
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, yml(), valuesWith("5"));
        assertTrue(o.fromSnapshot());
        assertEquals(5, o.rule().getLimit());
        assertEquals(1, o.rule().getWindowSeconds(), "window-seconds 不参与在线化");
    }

    @Test
    @DisplayName("越界值退回 yml 出厂值并标 ignored（不过限，也不 500）")
    void outOfRangeFallsBackToYml() {
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, yml(), valuesWith("0"));
        assertFalse(o.fromSnapshot());
        assertTrue(o.ignored());
        assertEquals(200, o.rule().getLimit());
    }

    @Test
    @DisplayName("无在线覆盖时原样返回 yml 那个对象：每请求零分配，也不去改共享 bean")
    void noOverrideReturnsSameInstance() {
        GatewayProperties.RateRule rule = yml();
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, rule, valuesWith(null));
        assertSame(rule, o.rule());
        assertFalse(o.fromSnapshot());
    }

    @Test
    @DisplayName("路由不在 yml map 里 → 完全不限流的现状不变")
    void unknownRouteStillUnlimited() {
        assertNull(new RateRuleResolver().resolve("nope-route", null, valuesWith("5")).rule());
    }
}
```

> `valuesWith` 直接以 `List.of(new GatewayConfigDefinitions())` 建 registry：网关的声明清单本身就是被测对象的一部分，用真实 provider 而不是再造一个匿名桩。

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=RateRuleResolverTest`
Expected: 编译失败 `找不到符号 RateRuleResolver`

- [ ] **Step 3: 实现声明与解析器**

`GatewayConfigDefinitions.java`：

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关层限流阈值的在线自述。
 *
 * <p>出厂值就是 application.yml 里那五个 RL_* 的默认值（FULL 口径），LITE 的保守值由
 * compose 环境变量给。两条路都留着：在线值只在被显式写过之后才盖住它们。</p>
 *
 * <p>下界取 1 而不是 0：0 等于"把入口关掉"，那不该是一个阈值字段的取值范围——
 * 真要关入口有下线动作。</p>
 */
@Component
public class GatewayConfigDefinitions implements ConfigDefinitionProvider {

    public static final String PREFIX = "gateway.ratelimit.";
    public static final String SUFFIX = ".limit";
    private static final long MAX_LIMIT = 200_000L;

    public static String keyOf(String routeId) {
        return PREFIX + routeId + SUFFIX;
    }

    @Override
    public String service() {
        return "marketing-gateway";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofInt(keyOf("activity-route"), 500, 1, MAX_LIMIT, "活动路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("coupon-route"), 1000, 1, MAX_LIMIT, "领券路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("discount-route"), 2000, 1, MAX_LIMIT, "优惠计算路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("seckill-route"), 200, 1, MAX_LIMIT, "秒杀路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("admin-route"), 50, 1, MAX_LIMIT, "后台路由每秒阈值（受 BCrypt 成本约束）"));
    }
}
```

`RateRuleResolver.java`：

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigValues;
import org.springframework.stereotype.Component;

/**
 * 一次限流判定该用哪个阈值：在线快照 &gt; 本进程 yml/环境变量出厂值。
 *
 * <p>单独成一个纯类是为了可单测——filter 里那半条 reactive 链不好测，
 * 而"取错档的阈值"是母版风险 #1 里最贵的一种错。</p>
 */
@Component
public class RateRuleResolver {

    public record Outcome(GatewayProperties.RateRule rule, boolean fromSnapshot, boolean ignored) {
    }

    public Outcome resolve(String routeId, GatewayProperties.RateRule ymlRule, ConfigValues values) {
        if (ymlRule == null) {
            // 母版事实 #3：route 不在 rate-limit map 里 = 完全不限流。本段不改这条语义。
            return new Outcome(null, false, false);
        }
        String key = GatewayConfigDefinitions.keyOf(routeId);
        if (!values.overridden(key)) {
            // 返回 yml 那个实例本身：不每请求新建对象，也绝不去改共享的 @ConfigurationProperties bean
            return new Outcome(ymlRule, false, values.degradedKeys().contains(key));
        }
        GatewayProperties.RateRule merged = new GatewayProperties.RateRule();
        merged.setLimit(values.intOr(key, (int) ymlRule.getLimit()));
        merged.setWindowSeconds(ymlRule.getWindowSeconds());
        return new Outcome(merged, true, false);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=RateRuleResolverTest`
Expected: PASS（4 个用例）

- [ ] **Step 5: 写并跑"声明与 yml 必须一一对应"的守卫测试**

防的是"改了 yml 忘了改声明"：yml 有路由而声明没有 → 后台改不动它（运营以为改了就没救了）；声明有而 yml 没这条路由 → 一个不存在的桶挂在清单里。

`GatewayConfigDefinitionsTest.java`：

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayConfigDefinitionsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDocument() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            return new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("yml 的 rate-limit 条目与在线声明清单严格一一对应")
    void definitionsMatchYamlRateLimitMap() throws Exception {
        Map<String, Object> marketing = (Map<String, Object>) firstDocument().get("marketing");
        Map<String, Object> gateway = (Map<String, Object>) marketing.get("gateway");
        Map<String, Object> rateLimit = (Map<String, Object>) gateway.get("rate-limit");
        Set<String> yamlKeys = rateLimit.keySet().stream()
                .map(k -> GatewayConfigDefinitions.keyOf(String.valueOf(k)))
                .collect(Collectors.toSet());
        Set<String> declaredKeys = new GatewayConfigDefinitions().definitions().stream()
                .map(ConfigDefinition::key).collect(Collectors.toSet());
        assertEquals(yamlKeys, declaredKeys, "yml 的 rate-limit 条目与声明清单漂移了");
    }

    @Test
    @DisplayName("每条声明的出厂值必须落在自己的边界内")
    void defaultsAreWithinOwnBounds() {
        for (ConfigDefinition d : new GatewayConfigDefinitions().definitions()) {
            assertTrue(d.accepts(d.defaultValue()), d.key() + " 的出厂值不自洽: " + d.defaultValue());
        }
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=GatewayConfigDefinitionsTest`
Expected: PASS（2 个用例）

- [ ] **Step 6: 写并跑"两套 profile 的路由表必须同步"的守卫测试**

①② 那条教训（漏 nacos profile 的一条路由 = 只在升档后才 404）的机器化版本，也是 ③⑥ 会反复用到的护栏。

`GatewayRouteTableTest.java`：

```java
package com.example.marketing.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * local 与 nacos 两套 profile 的路由 id 必须同集合，且每条路由都要有 rate-limit 条目。
 *
 * <p>两条都是"看着没事、换形态就全红"的那类：漏一条 lb:// 路由，LITE 一切正常而 FULL
 * 整片 404；route 不在 rate-limit map 里等于完全不限流，于是新增路由很容易变成一条暗道。</p>
 */
class GatewayRouteTableTest {

    private static List<Map<String, Object>> documents() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            List<Map<String, Object>> out = new ArrayList<>();
            new Yaml().loadAll(new InputStreamReader(in, StandardCharsets.UTF_8)).forEach(out::add);
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> routeIds(Map<String, Object> doc) {
        Set<String> ids = new HashSet<>();
        if (doc == null) {
            return ids;
        }
        Map<String, Object> spring = (Map<String, Object>) doc.get("spring");
        if (spring == null) {
            return ids;
        }
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = cloud == null ? null : (Map<String, Object>) cloud.get("gateway");
        List<Map<String, Object>> routes = gateway == null ? null : (List<Map<String, Object>>) gateway.get("routes");
        if (routes != null) {
            routes.forEach(r -> ids.add(String.valueOf(r.get("id"))));
        }
        return ids;
    }

    @Test
    @DisplayName("两套 profile 的路由 id 同集合")
    void bothProfilesDeclareTheSameRoutes() throws Exception {
        List<Map<String, Object>> docs = documents();
        assertTrue(docs.size() >= 2, "application.yml 应有 local 与 nacos 两个文档");
        assertEquals(routeIds(docs.get(0)), routeIds(docs.get(1)), "local 与 nacos 的路由清单漂移了");
    }

    @Test
    @DisplayName("每条路由都有 rate-limit 条目（没有=不限流）")
    void everyRouteHasRateLimitRule() throws Exception {
        Map<String, Object> marketing = (Map<String, Object>) documents().get(0).get("marketing");
        Map<String, Object> gateway = (Map<String, Object>) marketing.get("gateway");
        Map<String, Object> rateLimit = (Map<String, Object>) gateway.get("rate-limit");
        for (String id : routeIds(documents().get(0))) {
            assertTrue(rateLimit.containsKey(id), "路由 " + id + " 没有 rate-limit 条目 = 不限流");
        }
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=GatewayRouteTableTest`
Expected: PASS。若第二条红，说明 yml 真有条目缺失——**照红字补 yml，不许放宽测试**。

- [ ] **Step 7: 写失败测试 —— reactive 同步器喂到 `ConfigValues`**

`GatewayConfigSyncerTest.java`（Mockito 打桩 reactive 模板，与 `AdminAuthFilterTest` 同手法）：

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayConfigSyncerTest {

    private static final String KEY = "gateway.ratelimit.seckill-route.limit";

    private static ConfigSchemaRegistry registry() {
        return new ConfigSchemaRegistry(java.util.List.of(new GatewayConfigDefinitions()));
    }

    @SuppressWarnings("unchecked")
    private ReactiveRedisTemplate<String, String> redisReturning(String version, Mono<String> snapshot) {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(eq(ConfigKeys.version("LITE")))).thenReturn(version == null ? Mono.empty() : Mono.just(version));
        when(ops.get(eq(ConfigKeys.snapshot("LITE")))).thenReturn(snapshot);
        when(ops.set(anyString(), anyString())).thenReturn(Mono.just(true));
        return redis;
    }

    private static String snapshotJson(long version, String value) {
        return ConfigSnapshotCodec.write(new ConfigSnapshot(version, "now",
                Map.of(KEY, new ConfigSnapshot.Entry(value, ConfigType.INT, 0L))));
    }

    @Test
    @DisplayName("按本进程形态取快照键，解析后喂进 ConfigValues")
    void syncsOwnFormSnapshot() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        ReactiveRedisTemplate<String, String> redis = redisReturning("9", Mono.just(snapshotJson(9L, "120")));
        new GatewayConfigSyncer(redis, values, registry(), "LITE", "marketing-gateway", 5,
                new SimpleMeterRegistry()).syncOnce();
        assertEquals(120, values.intOr(KEY, 200));
        assertEquals(9L, values.appliedVersion());
    }

    @Test
    @DisplayName("Redis 报错时保持现值（一次抖动不能把阈值打回出厂）")
    void redisErrorKeepsLastApplied() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        ReactiveRedisTemplate<String, String> redis = redisReturning("9", Mono.just(snapshotJson(9L, "120")));
        GatewayConfigSyncer syncer = new GatewayConfigSyncer(redis, values, registry(), "LITE",
                "marketing-gateway", 5, new SimpleMeterRegistry());
        syncer.syncOnce();
        ReactiveRedisTemplate<String, String> broken = redisReturning(null,
                Mono.error(new IllegalStateException("boom")));
        new GatewayConfigSyncer(broken, values, registry(), "LITE", "marketing-gateway", 5,
                new SimpleMeterRegistry()).syncOnce();
        assertEquals(120, values.intOr(KEY, 200), "第二次同步失败后仍要用第一次的值");
    }

    @Test
    @DisplayName("version 键被删 → 退回出厂（快照丢失时的 FALLBACK_YML 语义）")
    void missingVersionKeyClearsOverrides() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        new GatewayConfigSyncer(redisReturning("9", Mono.just(snapshotJson(9L, "120"))), values, registry(),
                "LITE", "marketing-gateway", 5, new SimpleMeterRegistry()).syncOnce();
        assertEquals(120, values.intOr(KEY, 200));
        new GatewayConfigSyncer(redisReturning(null, Mono.empty()), values, registry(),
                "LITE", "marketing-gateway", 5, new SimpleMeterRegistry()).syncOnce();
        assertEquals(200, values.intOr(KEY, 200));
    }
}
```

- [ ] **Step 8: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=GatewayConfigSyncerTest`
Expected: 编译失败 `找不到符号 GatewayConfigSyncer`

- [ ] **Step 9: 实现 `GatewayConfigSyncer`**

```java
package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigForm;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaCodec;
import com.example.marketing.common.config.ConfigSchemaPayload;
import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关侧的配置同步器：与 common 的 ConfigSnapshotPoller 同一件事，但只能用
 * ReactiveRedisTemplate——网关没有 DataSource，也绝不能为在线配置引入阻塞 Redis 客户端
 * （母版事实 #8：那等于把一个 JDBC 式线程模型塞进事件循环进程）。
 *
 * <p>读的是常量键、写的是自己的 schema，用户可控输入为零。</p>
 */
@Slf4j
@Component
public class GatewayConfigSyncer {

    private static final long SCHEMA_REPUBLISH_SECONDS = 60L;

    private final ReactiveRedisTemplate<String, String> redis;
    private final ConfigValues values;
    private final ConfigSchemaRegistry registry;
    private final String ownForm;
    private final String service;
    private final long pollSeconds;
    private final MeterRegistry meters;
    private final AtomicLong tick = new AtomicLong();

    public GatewayConfigSyncer(ReactiveRedisTemplate<String, String> redis, ConfigValues values,
                               ConfigSchemaRegistry registry,
                               @Value("${marketing.config.form:}") String form,
                               @Value("${spring.application.name:marketing-gateway}") String service,
                               @Value("${marketing.config.poll-seconds:5}") long pollSeconds,
                               MeterRegistry meters) {
        this.redis = redis;
        this.values = values;
        this.registry = registry;
        this.ownForm = ConfigForm.resolve(form);
        this.service = service;
        this.pollSeconds = Math.max(1L, pollSeconds);
        this.meters = meters;
        if (ConfigForm.unrecognized(form)) {
            log.warn("[config] 网关 DEPLOY_FORM='{}' 无法识别，按 GLOBAL 解析", form);
        }
    }

    @PostConstruct
    public void start() {
        syncOnce();
        publishSchema();
        Flux.interval(Duration.ofSeconds(pollSeconds), Duration.ofSeconds(pollSeconds))
                .subscribe(n -> syncOnce(),
                        e -> log.error("[config] 网关轮询链异常终止（在线配置将停止更新）", e));
    }

    /** 版本比对 → 变了才取全量快照；异常吃掉并保住现值 */
    void syncOnce() {
        long expected = values.appliedVersion();
        redis.opsForValue().get(ConfigKeys.version(ownForm))
                .defaultIfEmpty("")
                .map(raw -> raw.trim().isEmpty() ? 0L : Long.parseLong(raw.trim()))
                .flatMap(current -> {
                    if (current == expected && current != 0L) {
                        return Mono.empty();
                    }
                    return redis.opsForValue().get(ConfigKeys.snapshot(ownForm))
                            .defaultIfEmpty("")
                            .doOnNext(json -> {
                                values.apply(ConfigSnapshotCodec.read(json));
                                if (!values.degradedKeys().isEmpty()) {
                                    log.warn("[config] 网关 form={} 有 {} 个条目未采纳，退回 yml: {}",
                                            ownForm, values.degradedKeys().size(), values.degradedKeys());
                                }
                                log.info("[config] 网关 form={} version={}", ownForm, values.appliedVersion());
                            });
                })
                .doOnError(e -> {
                    meters.counter("marketing.config.poll.error").increment();
                    log.warn("[config] 网关刷新失败，沿用 version={}: {}",
                            values.appliedVersion(), e.toString());
                })
                .onErrorComplete()
                .subscribe();
        if (tick.incrementAndGet() % Math.max(1L, SCHEMA_REPUBLISH_SECONDS / pollSeconds) == 0) {
            publishSchema();
        }
    }

    /** 自述可改参数：admin 只读这些键渲染表单与校验，不 import 网关任何类 */
    void publishSchema() {
        if (registry.all().isEmpty()) {
            return;
        }
        List<Map<String, Object>> defs = new ArrayList<>();
        Map<String, String> owners = registry.serviceByKey();
        for (ConfigDefinition d : registry.all()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", d.key());
            one.put("owner", owners.getOrDefault(d.key(), service));
            one.put("type", d.type().name());
            one.put("min", d.min());
            one.put("max", d.max());
            one.put("defaultValue", d.defaultValue());
            one.put("description", d.description() == null ? "" : d.description());
            defs.add(one);
        }
        redis.opsForValue().set(ConfigKeys.schema(service), ConfigSchemaCodec.write(
                        new ConfigSchemaPayload(Instant.now().getEpochSecond(), service, defs)))
                .doOnError(e -> log.warn("[config] 网关 schema 自述写入失败: {}", e.toString()))
                .onErrorComplete()
                .subscribe();
    }
}
```

- [ ] **Step 10: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test -Dtest=GatewayConfigSyncerTest`
Expected: PASS（3 个用例）。注意 `syncOnce()` 里用的是 `.subscribe()`，测试要能立刻看到结果——若断言偶发失败，把 `syncOnce()` 改成返回 `Mono<Void>`（内部 `...subscribe()` 改成 `return chain.then()`），测试里 `.block(Duration.ofSeconds(5))`；**这是形态问题不是时序魔法，不许用 sleep 掩盖**。

- [ ] **Step 11: 改 `RateLimitFilter` 接线**

字段与构造器（替换 `:35-47`）：

```java
    private final GatewayProperties properties;
    private final RateRuleResolver ruleResolver;
    private final ConfigValues configValues;
    private final org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    private final RedisScript<Long> slidingWindowScript;

    public RateLimitFilter(GatewayProperties properties, RateRuleResolver ruleResolver,
                           ConfigValues configValues,
                           org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> redisTemplate) {
        this.properties = properties;
        this.ruleResolver = ruleResolver;
        this.configValues = configValues;
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sliding_window.lua"));
        script.setResultType(Long.class);
        this.slidingWindowScript = script;
    }
```

`filter(...)` 里把取规则那三行换成：

```java
        RateRuleResolver.Outcome resolved = ruleResolver.resolve(
                route.getId(), properties.getRateLimit().get(route.getId()), configValues);
        if (resolved.rule() == null) {
            return chain.filter(exchange);
        }
        GatewayProperties.RateRule rule = resolved.rule();
        if (resolved.ignored()) {
            log.warn("[rate-limit] 在线值未被采纳，本请求退回 yml 出厂值 route={}, limit={}",
                    route.getId(), rule.getLimit());
        }
```

补 import `com.example.marketing.common.config.ConfigValues;` 与 `com.example.marketing.gateway.config.RateRuleResolver;`，并把类 Javadoc 的第一段改为一句事实陈述：阈值来源优先级"在线快照 &gt; 本进程 yml（`RL_*`）"，越界与未声明逐条退回出厂值；`window-seconds` 恒取 yml。

- [ ] **Step 12: yml 加两行属性**

`marketing-gateway/src/main/resources/application.yml`，在 `marketing:` 块内与 `gateway:` 同级：

```yaml
  # 在线配置下发：DEPLOY_FORM 未设置时只对 form=GLOBAL 的覆盖生效（行为与本段之前一致）
  config:
    form: ${DEPLOY_FORM:}
    poll-seconds: ${CONFIG_POLL_SECONDS:5}
```

- [ ] **Step 13: 网关全模块回归 + 变异检查**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway -am test`
Expected: 全绿（含 ①② 的 `AdminAuthFilterTest` 10 条、`AuthFilterTest` 4 条不受影响）

变异：① 把 `resolve` 里 `if (ymlRule == null)` 的分支改成返回 `limit=0` 的规则 → `unknownRouteStillUnlimited` 必须红；② 删掉 `merged.setWindowSeconds(...)` → `snapshotWinsOnLimitOnly` 必须红；③ 把 `syncOnce` 的 `defaultIfEmpty("")` 去掉（版本键缺失即 NPE 路径）→ `missingVersionKeyClearsOverrides` 必须红。

- [ ] **Step 14: 提交**

```bash
git add marketing-gateway/src
git commit -m "feat(config): 网关限流阈值在线化（自包含快照 + reactive 轮询 + 越界逐条退回 yml）"
```

---

### Task 5: admin 写路径（校验 / 广播 / 41009 / 权限细筛 / 审计）

**Files:**
- Modify: `marketing-common/src/main/java/com/example/marketing/common/api/ErrorCode.java`（+3 个码）
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/config/AdminConfigStore.java`
- Create: `.../admin/config/ConfigSchemaReader.java`（`@Component`）
- Create: `.../admin/config/ConfigSnapshotPublisher.java`
- Create: `.../admin/config/AdminConfigService.java`
- Create: `.../admin/dto/ConfigSetRequest.java`、`ConfigEntryView.java`、`ConfigFormValueView.java`、`ConfigOrphanView.java`、`ConfigOverviewView.java`
- Create: `.../admin/controller/AdminConfigController.java`
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/config/AdminConfigStoreTest.java`
- Test: `.../admin/config/AdminConfigServiceTest.java`

**Interfaces:**
- Consumes: Task 1/2 全部公共类型；`AdminPrincipal`、`AdminIdentityService.require(request, String...)`、`AdminRoles.ADMIN`、`AuditService.record(AuditRecord)`、`AuditRecord`（13 字段）、`ClientIp.of(request)`、`ErrorCode.CONFIG_NOT_BROADCAST`
- Produces:
  - `AdminConfigStore`: `List<ConfigMerge.Row> rows()`、`List<AdminConfigStore.Row> detail()`、`String findValue(String key, String form)`、`void upsert(String key, String form, String value, long version, String by, String remark)`、`int delete(String key, String form)`；`record Row(String cfgKey, String form, String value, String version, String updatedBy, String remark)`
  - `ConfigSchemaReader`: `List<ConfigDefinition> declared()`、`Optional<ConfigDefinition> find(String key)`、`Map<String,String> serviceByKey()`、`List<String> unreported()`、`long schemaVersion()`
  - `ConfigSnapshotPublisher`: `long nextSequence()`、`void publishAll(long seq)`
  - `AdminConfigService`: `ConfigOverviewView overview()`、`ConfigEntryView set(AdminPrincipal, ConfigSetRequest, String ip)`、`void delete(AdminPrincipal, String key, String form, String ip)`、`long rebroadcast(AdminPrincipal, String ip)`
  - HTTP：`GET /api/admin/config`、`PUT /api/admin/config`、`DELETE /api/admin/config?cfgKey=&form=`、`POST /api/admin/config/rebroadcast`
  - 错误码：`41008`、`41009`、`41010`

- [ ] **Step 1: 加三个错误码**

`ErrorCode.java` 在 `ACTIVITY_NOT_ONLINE(41007, "活动未上线或已结束"),` 之后插入：

```java
    /** 乐观锁冲突：后台两个标签页同时编辑同一行（③ 的字段编辑也用它） */
    CONFIG_VERSION_CONFLICT(41008, "数据已被他人修改，请刷新后重试"),
    /** DB 已提交但 Redis 广播失败：静默不一致的显式出口，配"重新广播"动作修复 */
    CONFIG_NOT_BROADCAST(41009, "配置已落库但未广播，请用重新广播修复"),
    /** 该能力在当前形态下不存在（reheat 从 41000 迁到这里，见母版风险 #4） */
    FORM_NOT_APPLICABLE(41010, "本形态不适用该操作"),
```

Run: `source scripts/common.sh && mvn -q -pl marketing-common -am test`
Expected: 全绿（枚举加值不动任何断言）

- [ ] **Step 2: 写失败测试 —— store 的 SQL 语义（H2 `MODE=MySQL`）**

`AdminConfigStoreTest.java`。为什么 store 用 `JdbcTemplate` 而不是 MyBatis-Plus mapper：这里需要的只是一条 upsert 与两个整体读，`JdbcTemplate` 能被真 SQL 测到（mapper 桩测不到 SQL 语义），与 `IdempotentExecutor` / `LocalMessageService` 同手法。

```java
package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigMerge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminConfigStoreTest {

    private AdminConfigStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_config_store;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS admin_config");
        jdbc.execute("""
                CREATE TABLE admin_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    cfg_key VARCHAR(64) NOT NULL,
                    form VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
                    cfg_value VARCHAR(255) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_by VARCHAR(64) NOT NULL DEFAULT '',
                    remark VARCHAR(255) NOT NULL DEFAULT '',
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_key_form UNIQUE (cfg_key, form))""");
        store = new AdminConfigStore(jdbc);
    }

    @Test
    @DisplayName("同键同形态再写是覆盖而不是加行，version/updated_by/remark 一起走")
    void upsertOverridesSameKeyForm() {
        store.upsert("k", "LITE", "120", 1L, "admin", "第一次");
        store.upsert("k", "LITE", "150", 2L, "operator", "改一下");
        assertEquals(1, store.rows().size());
        assertEquals("150", store.findValue("k", "LITE"));
        AdminConfigStore.Row row = store.detail().get(0);
        assertEquals("2", row.version());
        assertEquals("改一下", row.remark());
        assertEquals("operator", row.updatedBy());
    }

    @Test
    @DisplayName("同键不同形态是两行，且合并后各读各的（分形态阈值共存一张表）")
    void differentFormsCoexist() {
        store.upsert("k", "LITE", "120", 1L, "admin", "");
        store.upsert("k", "FULL", "1000", 2L, "admin", "");
        List<ConfigMerge.Row> rows = store.rows();
        assertEquals(2, rows.size());
        assertEquals("120", ConfigMerge.merge("LITE", rows).get("k"));
        assertEquals("1000", ConfigMerge.merge("FULL", rows).get("k"));
    }

    @Test
    @DisplayName("delete 命中返回 1、未命中返回 0（恢复出厂就是删行）")
    void deleteReportsAffectedRows() {
        store.upsert("k", "GLOBAL", "1", 1L, "admin", "");
        assertEquals(1, store.delete("k", "GLOBAL"));
        assertNull(store.findValue("k", "GLOBAL"));
        assertEquals(0, store.delete("k", "GLOBAL"));
        assertTrue(store.rows().isEmpty());
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test -Dtest=AdminConfigStoreTest`
Expected: 编译失败 `找不到符号 AdminConfigStore`

- [ ] **Step 4: 实现 `AdminConfigStore`**

```java
package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigMerge;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code admin_config} 的存取。用 JdbcTemplate 而不是 BaseMapper：这里要的只是一条 upsert
 * 与两个整体读，而 JdbcTemplate 能被 H2 真 SQL 测到——mapper 桩测不到 SQL 语义，
 * 与 IdempotentExecutor / LocalMessageService 同一手法。
 */
@Repository
public class AdminConfigStore {

    /** 展示行：版本、操作人与备注（④ 的"期望值 vs 生效值"也要用它） */
    public record Row(String cfgKey, String form, String value, String version,
                      String updatedBy, String remark) {
    }

    private final JdbcTemplate jdbc;

    public AdminConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConfigMerge.Row> rows() {
        return jdbc.query("SELECT cfg_key, form, cfg_value FROM admin_config ORDER BY cfg_key, form",
                (rs, i) -> new ConfigMerge.Row(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public List<Row> detail() {
        return jdbc.query("SELECT cfg_key, form, cfg_value, version, updated_by, remark "
                        + "FROM admin_config ORDER BY cfg_key, form",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    public String findValue(String key, String form) {
        List<String> found = jdbc.queryForList(
                "SELECT cfg_value FROM admin_config WHERE cfg_key = ? AND form = ?",
                String.class, key, form);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 先 UPDATE 再 INSERT：ON DUPLICATE KEY UPDATE 在 H2 的 MySQL 模式下语义不完全一致，
     * 而这个方法必须有单测覆盖。并发插入撞唯一键时重跑一次 UPDATE，不重试第二次 INSERT。
     */
    public void upsert(String key, String form, String value, long version, String by, String remark) {
        if (jdbc.update("UPDATE admin_config SET cfg_value = ?, version = ?, updated_by = ?, remark = ? "
                + "WHERE cfg_key = ? AND form = ?", value, version, by, remark, key, form) > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO admin_config (cfg_key, form, cfg_value, version, updated_by, remark) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", key, form, value, version, by, remark);
        } catch (DuplicateKeyException raced) {
            jdbc.update("UPDATE admin_config SET cfg_value = ?, version = ?, updated_by = ?, remark = ? "
                    + "WHERE cfg_key = ? AND form = ?", value, version, by, remark, key, form);
        }
    }

    public int delete(String key, String form) {
        return jdbc.update("DELETE FROM admin_config WHERE cfg_key = ? AND form = ?", key, form);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test -Dtest=AdminConfigStoreTest`
Expected: PASS（3 个用例）

- [ ] **Step 6: 实现 `ConfigSchemaReader` 与 `ConfigSnapshotPublisher`**

`ConfigSchemaReader.java`：

```java
package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaCodec;
import com.example.marketing.common.config.ConfigSchemaPayload;
import com.example.marketing.common.config.ConfigType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聚合各服务自述的 schema：这是后台"能改哪些参数"的唯一清单，而 admin 不 import 业务模块
 * （母版 §4 的第一条不变量）。
 *
 * <p>服务名是**固定候选集**而不是 SCAN 出来的：与 ④ 禁 SCAN 同源，"任意键名都能被发现"
 * 本身就是一个面。没上报的服务显式进 {@link #unreported()}，而不是静默少一个下拉项——
 * 运营看到"少了个参数"与看到"该服务未上报"是两回事。</p>
 */
@Slf4j
@Component
public class ConfigSchemaReader {

    /** 六个进程 + LITE 聚合名。LITE 下业务模块的自述挂在 marketing-standalone 上 */
    public static final List<String> SERVICES = List.of(
            "marketing-gateway", "marketing-activity", "marketing-coupon",
            "marketing-discount", "marketing-seckill", "marketing-admin", "marketing-standalone");

    private final StringRedisTemplate redis;

    public ConfigSchemaReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public List<ConfigDefinition> declared() {
        return new ArrayList<>(byKey().values());
    }

    public Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(byKey().get(key));
    }

    /** key → 模块名（取自自述里的 owner，而不是进程名：LITE 下进程名统一是 standalone） */
    public Map<String, String> serviceByKey() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload == null || payload.definitions() == null) {
                continue;
            }
            for (Map<String, Object> raw : payload.definitions()) {
                out.putIfAbsent(String.valueOf(raw.get("key")),
                        String.valueOf(raw.getOrDefault("owner", payload.service())));
            }
        }
        return out;
    }

    /** 聚合 schema 的版本（取最新一份自述的 generatedAt），只用于快照条目的 defVer */
    public long schemaVersion() {
        long max = 0L;
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload != null) {
                max = Math.max(max, payload.generatedAt());
            }
        }
        return max;
    }

    public List<String> unreported() {
        List<String> missing = new ArrayList<>();
        for (String service : SERVICES) {
            if (read(service) == null) {
                missing.add(service);
            }
        }
        return missing;
    }

    private Map<String, ConfigDefinition> byKey() {
        Map<String, ConfigDefinition> byKey = new LinkedHashMap<>();
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload == null || payload.definitions() == null) {
                continue;
            }
            for (Map<String, Object> raw : payload.definitions()) {
                ConfigDefinition d = toDefinition(raw);
                ConfigDefinition previous = byKey.putIfAbsent(d.key(), d);
                if (previous != null && !previous.equals(d)) {
                    log.warn("[config] 同一个键被两个服务以不同边界声明: {}（{} 与 {}），以先上报者为准",
                            d.key(), previous, d);
                }
            }
        }
        return byKey;
    }

    private ConfigSchemaPayload read(String service) {
        try {
            return ConfigSchemaCodec.read(redis.opsForValue().get(ConfigKeys.schema(service)));
        } catch (Exception e) {
            log.warn("[config] 读取 {} 的 schema 自述失败: {}", service, e.toString());
            return null;
        }
    }

    private static ConfigDefinition toDefinition(Map<String, Object> raw) {
        ConfigType type = ConfigType.valueOf(String.valueOf(raw.getOrDefault("type", "STRING")));
        long min = raw.get("min") instanceof Number n ? n.longValue() : 0L;
        long max = raw.get("max") instanceof Number n ? n.longValue() : 255L;
        return new ConfigDefinition(String.valueOf(raw.get("key")), type, min, max,
                String.valueOf(raw.getOrDefault("defaultValue", "")),
                String.valueOf(raw.getOrDefault("description", "")));
    }
}
```

`ConfigSnapshotPublisher.java`：

```java
package com.example.marketing.admin.config;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigMerge;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按四种形态各生成一份合并后的全量快照并广播。
 *
 * <p>顺序固定：先 SET snapshot 再 SET version。反过来会让读方看到新版本却取到旧内容，
 * 而"版本号领先于内容"比"慢一秒"糟得多。</p>
 *
 * <p>某形态合并后为空时**删**两个键：只发布"表里出现过的 form"会留一个洞——把 LITE 的行
 * 删干净之后 LITE 快照停在旧值上，"恢复出厂"永远不生效（段内 spec §3 偏离 #2）。</p>
 */
@Slf4j
@Service
public class ConfigSnapshotPublisher {

    private final StringRedisTemplate redis;
    private final AdminConfigStore store;
    private final ConfigSchemaReader schemaReader;

    public ConfigSnapshotPublisher(StringRedisTemplate redis, AdminConfigStore store,
                                  ConfigSchemaReader schemaReader) {
        this.redis = redis;
        this.store = store;
        this.schemaReader = schemaReader;
    }

    /** 全局单调序号。Redis 不可用时连广播也做不到，直接按"未广播"报，不假装成功 */
    public long nextSequence() {
        try {
            Long seq = redis.opsForValue().increment(ConfigKeys.SEQUENCE);
            if (seq == null) {
                throw new IllegalStateException("INCR 返回空");
            }
            return seq;
        } catch (Exception e) {
            throw BizException.of(ErrorCode.CONFIG_NOT_BROADCAST,
                    "Redis 不可用，配置未写入（广播依赖 Redis 序号）: " + e.getMessage());
        }
    }

    public void publishAll(long seq) {
        List<ConfigMerge.Row> rows = store.rows();
        long defVer = schemaReader.schemaVersion();
        Map<String, ConfigDefinition> declared = new LinkedHashMap<>();
        schemaReader.declared().forEach(d -> declared.put(d.key(), d));
        try {
            for (String form : ConfigKeys.FORMS) {
                Map<String, String> merged = ConfigMerge.merge(form, rows);
                if (merged.isEmpty()) {
                    redis.delete(List.of(ConfigKeys.snapshot(form), ConfigKeys.version(form)));
                    continue;
                }
                Map<String, ConfigSnapshot.Entry> entries = new LinkedHashMap<>();
                merged.forEach((key, value) -> {
                    ConfigDefinition def = declared.get(key);
                    entries.put(key, new ConfigSnapshot.Entry(value,
                            def == null ? ConfigType.STRING : def.type(), defVer));
                });
                redis.opsForValue().set(ConfigKeys.snapshot(form), ConfigSnapshotCodec.write(
                        new ConfigSnapshot(seq, Instant.now().toString(), entries)));
                redis.opsForValue().set(ConfigKeys.version(form), String.valueOf(seq));
            }
            log.info("[config] 快照已广播 seq={}, 真值行数={}", seq, rows.size());
        } catch (Exception e) {
            throw BizException.of(ErrorCode.CONFIG_NOT_BROADCAST,
                    "配置已落库但未广播（各进程仍按旧值），用重新广播修复: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 7: 写失败测试 —— 写路径的裁决**

`AdminConfigServiceTest.java`。每条都对应一个现实后果：未声明键写了没人消费（静默按钮）、越界值进了 DB（下次大促炸）、广播失败被当成功（静默不一致）、删行不生效（恢复出厂是假的）。

```java
package com.example.marketing.admin.config;

import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.common.exception.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminConfigServiceTest {

    private static final String KEY = "gateway.ratelimit.seckill-route.limit";
    private static final ConfigDefinition DEF =
            ConfigDefinition.ofInt(KEY, 200, 1, 200000, "秒杀阈值");
    private static final AdminPrincipal ADMIN = new AdminPrincipal(1L, "admin", "admin", "jti-1");

    private AdminConfigStore store;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ConfigSchemaReader schemaReader;
    private AdminConfigService service;
    private AuditService auditService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_config_svc;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS admin_config");
        jdbc.execute("""
                CREATE TABLE admin_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    cfg_key VARCHAR(64) NOT NULL,
                    form VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
                    cfg_value VARCHAR(255) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_by VARCHAR(64) NOT NULL DEFAULT '',
                    remark VARCHAR(255) NOT NULL DEFAULT '',
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_key_form UNIQUE (cfg_key, form))""");
        store = new AdminConfigStore(jdbc);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(ConfigKeys.SEQUENCE)).thenReturn(11L);
        schemaReader = mock(ConfigSchemaReader.class);
        when(schemaReader.find(KEY)).thenReturn(Optional.of(DEF));
        when(schemaReader.find("nope.key")).thenReturn(Optional.empty());
        when(schemaReader.declared()).thenReturn(List.of(DEF));
        ConfigValues values = new ConfigValues(ConfigSchemaRegistry.empty(), new SimpleMeterRegistry());
        auditService = mock(AuditService.class);
        service = new AdminConfigService(store, new ConfigSnapshotPublisher(redis, store, schemaReader),
                schemaReader, values, auditService, "LITE");
    }

    @Test
    @DisplayName("写成功：行落库带 seq 版本、四个形态的快照都重发、审计记下 before/after")
    void happyPathWritesThenBroadcasts() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "大促收口"), "127.0.0.1");
        assertEquals("120", store.findValue(KEY, "LITE"));
        assertEquals("11", store.detail().get(0).version());
        // 四种形态各写一次 snapshot + version
        verify(ops).set(eq(ConfigKeys.snapshot("LITE")), anyString());
        verify(ops).set(eq(ConfigKeys.version("LITE")), eq("11"));
        verify(ops).set(eq(ConfigKeys.snapshot("GLOBAL")), anyString());
        verify(auditService).record(org.mockito.ArgumentMatchers.argThat(r ->
                "config.set".equals(r.action()) && r.requestSummary().contains("120")
                        && r.actorName().equals("admin")));
    }

    @Test
    @DisplayName("未声明的键直接拒（不做「接受了但没人消费」的静默按钮）")
    void undeclaredKeyRejected() {
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest("nope.key", "LITE", "1", ""), "ip"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(store.rows().isEmpty(), "被拒的写不许在库里留行");
    }

    @Test
    @DisplayName("越界值在碰 DB 与 Redis 之前就拒")
    void outOfRangeRejectedBeforeTouchingAnything() {
        assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "LITE", "0", ""), "ip"));
        assertTrue(store.rows().isEmpty());
        verify(ops, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("非法 form 拒：写进一个没人读的形态就是幽灵配置")
    void unknownFormRejected() {
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "PREVIEW", "120", ""), "ip"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("GLOBAL"));
    }

    @Test
    @DisplayName("广播失败 → 41009，但行仍在库里且拒绝也被审计")
    void broadcastFailureSurfacesAs41009AndKeepsRow() {
        doThrow(new IllegalStateException("redis down")).when(ops).set(anyString(), anyString());
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "LITE", "120", ""), "ip"));
        assertEquals(ErrorCode.CONFIG_NOT_BROADCAST.getCode(), e.getCode());
        assertEquals("120", store.findValue(KEY, "LITE"), "已落库是事实，回滚只会让审计对不上");
        verify(auditService).record(org.mockito.ArgumentMatchers.argThat(r ->
                r.resultCode() == ErrorCode.CONFIG_NOT_BROADCAST.getCode()));
    }

    @Test
    @DisplayName("删除命中才重广播；未命中 40400 且不碰 Redis")
    void deleteIsRestoreToFactory() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", ""), "ip");
        org.mockito.Mockito.clearInvocations(ops);
        service.delete(ADMIN, KEY, "LITE", "ip");
        assertNull(store.findValue(KEY, "LITE"));
        verify(ops).delete(org.mockito.ArgumentMatchers.anyCollection());

        assertThrows(BizException.class, () -> service.delete(ADMIN, KEY, "LITE", "ip"));
    }

    @Test
    @DisplayName("overview 报出自己的形态、生效值与来源，且未上报服务被列出来")
    void overviewExposesSourceOfTruth() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "GLOBAL", "150", ""), "ip");
        var view = service.overview();
        assertEquals("LITE", view.ownForm());
        var entry = view.entries().stream().filter(e -> e.key().equals(KEY)).findFirst().orElseThrow();
        assertEquals("150", entry.effectiveValue());
        assertEquals("GLOBAL", entry.source());
        assertNotNull(view.unreportedServices());
    }
}
```

- [ ] **Step 8: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test -Dtest=AdminConfigServiceTest`
Expected: 编译失败 `找不到符号 AdminConfigService`

- [ ] **Step 9: 实现五个 DTO**

```java
package com.example.marketing.admin.dto;

/** 配置写请求。form 必填：留空会被写成 GLOBAL，而"我以为改的是 LITE"是不可见的。 */
public record ConfigSetRequest(String cfgKey, String form, String value, String remark) {
}
```

```java
package com.example.marketing.admin.dto;

/** admin_config 的一行（某个形态上的覆盖）。 */
public record ConfigFormValueView(String form, String value, String version,
                                  String updatedBy, String remark) {
}
```

```java
package com.example.marketing.admin.dto;

/** 库里存在、但当前没有任何代码声明的键：不生效、不自动删，交给 ④ 的 ORPHAN 清单。 */
public record ConfigOrphanView(String form, String cfgKey, String value,
                               String version, String updatedBy) {
}
```

```java
package com.example.marketing.admin.dto;

import java.util.List;

/**
 * 一个可改参数的完整视图。
 *
 * @param source FORM（本进程形态有自己的覆盖）/ GLOBAL / DEFAULT（没人覆盖，跑出厂值）
 */
public record ConfigEntryView(String key, String service, String type, long min, long max,
                              String defaultValue, String description, String effectiveValue,
                              String source, List<ConfigFormValueView> rows) {
}
```

```java
package com.example.marketing.admin.dto;

import java.util.List;

/**
 * 配置页总览。
 *
 * @param ownForm            本进程解析到的形态（未设置时为 GLOBAL）
 * @param appliedVersion     本进程当前应用的快照版本（0 = 没有任何在线覆盖生效）
 * @param degradedKeys       本进程最近一次刷新里被忽略的键
 * @param unreportedServices 固定候选集里没上报自述的服务（配置页要显式说，而不是少一项）
 */
public record ConfigOverviewView(String ownForm, long appliedVersion, List<ConfigEntryView> entries,
                                 List<ConfigOrphanView> orphans, List<String> unreportedServices,
                                 List<String> degradedKeys) {
}
```

- [ ] **Step 10: 实现 `AdminConfigService`**

```java
package com.example.marketing.admin.config;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ConfigEntryView;
import com.example.marketing.admin.dto.ConfigFormValueView;
import com.example.marketing.admin.dto.ConfigOrphanView;
import com.example.marketing.admin.dto.ConfigOverviewView;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigForm;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigMerge;
import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 配置写路径：唯一的真值写入者 + 唯一的广播者。
 *
 * <p>三条纪律：① 只接受代码声明过的键（"未声明"就拒，而不是写一条没人消费的行）；
 * ② 顺序固定 INCR → 写行 → 发快照 → 发版本，后两步失败一律 41009 显式暴露；
 * ③ 恢复出厂 = 删行。</p>
 */
@Slf4j
@Service
public class AdminConfigService {

    private final AdminConfigStore store;
    private final ConfigSnapshotPublisher publisher;
    private final ConfigSchemaReader schemaReader;
    private final ConfigValues values;
    private final AuditService auditService;
    private final String ownForm;

    public AdminConfigService(AdminConfigStore store, ConfigSnapshotPublisher publisher,
                             ConfigSchemaReader schemaReader, ConfigValues values,
                             AuditService auditService,
                             @org.springframework.beans.factory.annotation.Value("${marketing.config.form:}")
                             String form) {
        this.store = store;
        this.publisher = publisher;
        this.schemaReader = schemaReader;
        this.values = values;
        this.auditService = auditService;
        this.ownForm = ConfigForm.resolve(form);
    }

    public ConfigOverviewView overview() {
        List<ConfigMerge.Row> rows = store.rows();
        List<AdminConfigStore.Row> detail = store.detail();
        Map<String, String> merged = ConfigMerge.merge(ownForm, rows);
        Set<String> declaredKeys = new LinkedHashSet<>();
        List<ConfigEntryView> entries = new ArrayList<>();
        for (ConfigDefinition d : schemaReader.declared()) {
            declaredKeys.add(d.key());
            String effective = merged.get(d.key());
            String source = effective == null ? "DEFAULT"
                    : (rows.stream().anyMatch(r -> r.cfgKey().equals(d.key()) && formEquals(r, ownForm))
                    ? "FORM" : "GLOBAL");
            entries.add(new ConfigEntryView(d.key(), schemaReader.serviceByKey().getOrDefault(d.key(), ""),
                    d.type().name(), d.min(), d.max(), d.defaultValue(), d.description(),
                    effective == null ? d.defaultValue() : effective, source, rowsOf(detail, d.key())));
        }
        List<ConfigOrphanView> orphans = new ArrayList<>();
        for (AdminConfigStore.Row r : detail) {
            if (!declaredKeys.contains(r.cfgKey())) {
                orphans.add(new ConfigOrphanView(r.form(), r.cfgKey(), r.value(), r.version(), r.updatedBy()));
            }
        }
        return new ConfigOverviewView(ownForm, values.appliedVersion(), entries, orphans,
                schemaReader.unreported(), values.degradedKeys());
    }

    public ConfigEntryView set(AdminPrincipal actor, ConfigSetRequest request, String ip) {
        String key = trimmed(request.cfgKey());
        String form = requireForm(request.form());
        String value = trimmed(request.value());
        ConfigDefinition def = schemaReader.find(key).orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST,
                "参数 " + key + " 未被任何在线服务声明，不能改（改了也没有人消费）"));
        if (!def.accepts(value)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "参数 " + key + " 的值非法: " + value + "，类型 " + def.type()
                            + "，允许区间 [" + def.min() + ", " + def.max() + "]");
        }
        String before = store.findValue(key, form);
        long seq = publisher.nextSequence();
        store.upsert(key, form, value, seq, actor.username(), trimmed(request.remark()));
        broadcastOrThrow(actor, "config.set", key, form, before, value, ip, seq);
        log.info("[admin] 配置写入 key={}, form={}, {} -> {}, seq={}, actor={}",
                key, form, before, value, seq, actor.username());
        return entryView(key);
    }

    public void delete(AdminPrincipal actor, String key, String form, String ip) {
        String k = trimmed(key);
        String f = requireForm(form);
        String before = store.findValue(k, f);
        long seq = publisher.nextSequence();
        if (store.delete(k, f) == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "该参数在这个形态上没有覆盖（本来就是出厂值）");
        }
        broadcastOrThrow(actor, "config.delete", k, f, before, "", ip, seq);
        log.info("[admin] 配置恢复出厂 key={}, form={}, 原值={}, seq={}, actor={}",
                k, f, before, seq, actor.username());
    }

    public long rebroadcast(AdminPrincipal actor, String ip) {
        long seq = publisher.nextSequence();
        try {
            publisher.publishAll(seq);
        } catch (BizException e) {
            audit(actor, "config.rebroadcast", "", "", seq, e.getCode(), e.getMessage(), ip);
            throw e;
        }
        audit(actor, "config.rebroadcast", "", "", seq, 0, "", ip);
        log.info("[admin] 配置重新广播 seq={}, actor={}", seq, actor.username());
        return seq;
    }

    /** 后两步失败必须显式暴露：静默不一致是本项目最贵的一类 bug */
    private void broadcastOrThrow(AdminPrincipal actor, String action, String key, String form,
                                  String before, String after, String ip, long seq) {
        try {
            publisher.publishAll(seq);
        } catch (BizException e) {
            audit(actor, action, key, form, before, after, seq, e.getCode(), e.getMessage(), ip);
            throw e;
        }
        audit(actor, action, key, form, before, after, seq, 0, "", ip);
    }

    private ConfigEntryView entryView(String key) {
        Optional<ConfigDefinition> def = schemaReader.find(key);
        ConfigDefinition d = def.orElseThrow(() -> BizException.of(ErrorCode.SYSTEM_ERROR,
                "刚写完的参数在声明清单里找不到: " + key));
        Map<String, String> merged = ConfigMerge.merge(ownForm, store.rows());
        return new ConfigEntryView(d.key(), "", d.type().name(), d.min(), d.max(), d.defaultValue(),
                d.description(), merged.getOrDefault(d.key(), d.defaultValue()),
                merged.containsKey(d.key()) ? "FORM" : "DEFAULT", rowsOf(store.detail(), d.key()));
    }

    private static List<ConfigFormValueView> rowsOf(List<AdminConfigStore.Row> detail, String key) {
        List<ConfigFormValueView> out = new ArrayList<>();
        for (AdminConfigStore.Row r : detail) {
            if (r.cfgKey().equals(key)) {
                out.add(new ConfigFormValueView(r.form(), r.value(), r.version(), r.updatedBy(), r.remark()));
            }
        }
        return out;
    }

    private static boolean formEquals(ConfigMerge.Row r, String form) {
        return ConfigForm.resolve(r.form()).equals(ConfigForm.resolve(form));
    }

    private static String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String requireForm(String raw) {
        String form = trimmed(raw).toUpperCase();
        if (!ConfigKeys.FORMS.contains(form)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "form 必须是 " + ConfigKeys.FORMS + " 之一，收到: " + raw);
        }
        return form;
    }

    private void audit(AdminPrincipal actor, String action, String key, String form,
                       String before, String after, long seq, int code, String err, String ip) {
        auditService.record(new AuditRecord(actor.uid(), actor.username(), actor.role(), action,
                "admin_config", key + ":" + form, "PUT", "/api/admin/config",
                "from=" + before + ", to=" + after + ", form=" + form + ", seq=" + seq,
                code, err, ip, 0));
    }

    private void audit(AdminPrincipal actor, String action, String key, String form, long seq,
                       int code, String err, String ip) {
        audit(actor, action, key, form, "", "", seq, code, err, ip);
    }
}
```

> `overview()` 里对 `source` 的 FORM/GLOBAL 判定读的是 `store.rows()` 的重复遍历，参数多时是 O(n²)。落地时先按 form 建索引再判定（一次 `Map<String,String> formValueByKey` + 一次 `merged`），别把二次遍历留在真值路径上——这条不需要测试，但必须做。

- [ ] **Step 11: 实现 controller 并跑通测试**

```java
package com.example.marketing.admin.controller;

import com.example.marketing.admin.dto.ConfigEntryView;
import com.example.marketing.admin.dto.ConfigOverviewView;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.admin.security.AdminRoles;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.admin.config.AdminConfigService;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线配置面。读任何后台角色都行；写只有 admin ——
 * operator 在网关那一层是"可写运维角色"（OPERATIONAL），但阈值不是运维动作而是业务口径，
 * 所以这里再收一层细筛。缺了这层，operator 就能在双十一当天改限流。
 */
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final AdminConfigService configService;
    private final AdminIdentityService identityService;

    @GetMapping
    public Result<ConfigOverviewView> overview(HttpServletRequest request) {
        identityService.require(request);
        return Result.ok(configService.overview());
    }

    @PutMapping
    public Result<ConfigEntryView> set(HttpServletRequest request, @RequestBody ConfigSetRequest body) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        return Result.ok(configService.set(actor, body, ClientIp.of(request)));
    }

    @DeleteMapping
    public Result<Void> delete(HttpServletRequest request,
                               @RequestParam String cfgKey,
                               @RequestParam String form) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        configService.delete(actor, cfgKey, form, ClientIp.of(request));
        return Result.ok();
    }

    @PostMapping("/rebroadcast")
    public Result<Long> rebroadcast(HttpServletRequest request) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        return Result.ok(configService.rebroadcast(actor, ClientIp.of(request)));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test`
Expected: 全绿（含既有 `RequestSummaryTest` 5、`LoginPolicyTest` 6、`LoginGuardTest` 2）

- [ ] **Step 12: admin 的 yml 加两行**

`marketing-admin/src/main/resources/application.yml` 的 `marketing:` 块内加：

```yaml
  # 在线配置：admin 既是写方也是读方（自己的配置页要显示"我当前应用的是哪一份"）
  config:
    form: ${DEPLOY_FORM:}
    poll-seconds: ${CONFIG_POLL_SECONDS:5}
```

- [ ] **Step 13: 变异检查**

1. 去掉 `schemaReader.find(key).orElseThrow(...)`（改成"未声明也照写"）→ `undeclaredKeyRejected` 必须红。
2. 把 `!def.accepts(value)` 的拒绝改成 log + 继续 → `outOfRangeRejectedBeforeTouchingAnything` 必须红。
3. 把 `broadcastOrThrow` 里的 `catch` 改成吞掉异常（不抛）→ `broadcastFailureSurfacesAs41009AndKeepsRow` 必须红。
4. 把 `publishAll` 的空合并分支从 `redis.delete(...)` 改成 `continue` → `deleteIsRestoreToFactory` 的 `verify(ops).delete(...)` 必须红。
5. 把 `store.delete(...) == 0` 的判定去掉 → 同一条测试的第二半（未命中抛异常）必须红。

- [ ] **Step 14: 提交**

```bash
git add marketing-common/src/main/java/com/example/marketing/common/api/ErrorCode.java \
        marketing-admin/src
git commit -m "feat(config): 后台配置写路径（未声明拒写、快照广播失败显式 41009、删行即恢复出厂）"
```

---

### Task 6: 灰度真值落 DB（owning 服务每 5s 回源，不经 Redis）

**Files:**
- Create: `marketing-activity/src/main/java/com/example/marketing/activity/service/GrayRuleCache.java`
- Modify: `.../activity/service/GrayService.java`（整份替换）
- Modify: `.../activity/infrastructure/entity/ActivityEntity.java`（+2 字段）
- Delete: `.../activity/config/GrayProperties.java`
- Modify: `marketing-activity/src/main/resources/application.yml`（删 `marketing.gray` 块 `:48-53`）
- Modify: `marketing-standalone/src/main/resources/application.yml`（删 `gray:` 块 `:53-56`，并把 `:49-52` 的注释改成只讲 mq.type）
- Test: `marketing-activity/src/test/java/com/example/marketing/activity/service/GrayRuleCacheTest.java`
- Test: `.../activity/service/GrayServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `activity.gray_percent` / `gray_whitelist` 列；`JdbcTemplate`（activity 模块已装配，`BudgetService` 就在用）
- Produces: `GrayRuleCache.Rule(int percent, Set<Long> whitelist)`、`Optional<Rule> GrayRuleCache.rule(String activityNo)`、`void refreshNow()`、`GrayService.hit(String,Long)` 语义不变（**未配灰度 = 全量放行**，链路 0 的两条断言依赖它）

- [ ] **Step 1: 实体加两个字段**

`ActivityEntity.java` 在 `remark` 之后插入：

```java
    /** 灰度放量百分比 0-100；null = 未配灰度 = 全量放行（与 GrayService 的既有语义一致） */
    private Integer grayPercent;
    /** 灰度白名单 userId CSV；null 或空 = 无白名单 */
    private String grayWhitelist;
```

- [ ] **Step 2: 写失败测试（回源、钳位、CSV、异常保持现值）**

`GrayRuleCacheTest.java`：

```java
package com.example.marketing.activity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度真值在 DB（段内 spec §3 偏离 #3）。这里钉四件事：
 * 未配灰度的行不参与、越界值被钳位而不是"意外全量"、CSV 能解析、
 * 以及 DB 读失败时保住上一次规则。
 */
class GrayRuleCacheTest {

    private JdbcTemplate jdbc;
    private GrayRuleCache cache;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gray_rule_cache;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS activity");
        jdbc.execute("""
                CREATE TABLE activity (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    activity_no VARCHAR(64) NOT NULL,
                    gray_percent INT NULL,
                    gray_whitelist VARCHAR(255) NULL)""");
        cache = new GrayRuleCache(jdbc, 5);
    }

    private void insert(String no, Integer percent, String whitelist) {
        jdbc.update("INSERT INTO activity (activity_no, gray_percent, gray_whitelist) VALUES (?, ?, ?)",
                no, percent, whitelist);
    }

    @Test
    @DisplayName("只有配了灰度的行进规则表；CSV 白名单能解析（含空格）")
    void loadsOnlyConfiguredRowsAndParsesCsv() {
        insert("A1", 5, "70001, 70002");
        insert("A2", null, null);
        cache.refreshNow();
        Optional<GrayRuleCache.Rule> r = cache.rule("A1");
        assertTrue(r.isPresent());
        assertEquals(5, r.get().percent());
        assertEquals(Set.of(70001L, 70002L), r.get().whitelist());
        assertFalse(cache.rule("A2").isPresent(), "未配灰度必须落到'没有规则'，让 hit() 走全量放行");
    }

    @Test
    @DisplayName("越界的 gray_percent 被钳到 [0,100]：150 不能变成全量放行")
    void percentIsClamped() {
        insert("A1", 150, null);
        insert("A2", -3, null);
        cache.refreshNow();
        assertEquals(100, cache.rule("A1").orElseThrow().percent());
        assertEquals(0, cache.rule("A2").orElseThrow().percent());
    }

    @Test
    @DisplayName("非数字白名单项被跳过，其余照常生效")
    void badCsvEntriesSkipped() {
        insert("A1", 5, "70001, oops, 70003");
        cache.refreshNow();
        assertEquals(Set.of(70001L, 70003L), cache.rule("A1").orElseThrow().whitelist());
    }

    @Test
    @DisplayName("回源失败时保住上一次规则（DB 抖一下不该把灰度打回全量）")
    void refreshFailureKeepsPreviousRules() {
        insert("A1", 5, null);
        cache.refreshNow();
        jdbc.execute("DROP TABLE activity");
        cache.refreshNow();
        assertTrue(cache.rule("A1").isPresent(), "读不到 DB 时要继续用旧规则，而不是清空");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-activity -am test -Dtest=GrayRuleCacheTest`
Expected: 编译失败 `找不到符号 GrayRuleCache`

- [ ] **Step 4: 实现 `GrayRuleCache`**

```java
package com.example.marketing.activity.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 灰度规则的进程内缓存：真值在 {@code activity} 的两个列，每 {@code refreshSeconds} 回源一次。
 *
 * <p>为什么不走在线配置那套快照广播（母版 §5.4 原本这么写）：Redis 只能当变更通知，
 * 而"谁都能全量放行"的语义一旦被 Redis 被清空触发，就是把刚修掉的静默不一致换个地方复发。
 * 回源 DB 把这一整类风险消掉，代价是 5s 收敛窗口与一条 {@code WHERE gray_percent IS NOT NULL}
 * 的小查询（种子 60 行）。段内 spec §3 偏离 #3。</p>
 *
 * <p>读失败保住上一次规则：DB 抖一下不该把所有活动的灰度打回"全量放行"。</p>
 */
@Slf4j
@Component
public class GrayRuleCache {

    public record Rule(int percent, Set<Long> whitelist) {
    }

    private final JdbcTemplate jdbc;
    private final long refreshSeconds;
    private volatile Map<String, Rule> rules = Map.of();
    private volatile ScheduledExecutorService scheduler;

    public GrayRuleCache(JdbcTemplate jdbc,
                        @Value("${marketing.gray.refresh-seconds:5}") long refreshSeconds) {
        this.jdbc = jdbc;
        this.refreshSeconds = Math.max(1L, refreshSeconds);
    }

    @PostConstruct
    public void start() {
        refreshNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-gray-rule-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshNow, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    public Optional<Rule> rule(String activityNo) {
        return Optional.ofNullable(rules.get(activityNo));
    }

    /** 包内可见给单测；失败时不覆盖 rules */
    void refreshNow() {
        try {
            Map<String, Rule> next = new LinkedHashMap<>();
            jdbc.query("SELECT activity_no, gray_percent, gray_whitelist FROM activity "
                    + "WHERE gray_percent IS NOT NULL", rs -> {
                next.put(rs.getString(1), new Rule(clamp(rs.getInt(2)), parseWhitelist(rs.getString(3))));
            });
            rules = Map.copyOf(next);
        } catch (Exception e) {
            log.warn("[gray] 灰度规则回源失败，沿用上一份（{} 条）: {}", rules.size(), e.toString());
        }
    }

    private static int clamp(int raw) {
        if (raw < 0) {
            log.warn("[gray] gray_percent={} 越界，钳到 0", raw);
            return 0;
        }
        if (raw > 100) {
            log.warn("[gray] gray_percent={} 越界，钳到 100", raw);
            return 100;
        }
        return raw;
    }

    private static Set<Long> parseWhitelist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> out = new HashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(trimmed));
            } catch (NumberFormatException bad) {
                log.warn("[gray] 白名单里有非数字项，已跳过: {}", trimmed);
            }
        }
        return Set.copyOf(out);
    }
}
```

`jdbc.query(String, RowCallbackHandler)` 返回 void，所以 `refreshNow()` 里不需要任何 `.getClass()` 之类的收尾；`clamp` 只管数值区间，"这一行算不算配了灰度"由 SQL 的 `IS NOT NULL` 决定。

- [ ] **Step 5: 替换 `GrayService` 并跑测试**

```java
package com.example.marketing.activity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 灰度命中判断：白名单直通，否则按 userId 稳定取模放量。
 *
 * <p>同一 userId 在比例不变时命中结果稳定（一致性灰度），避免用户"闪进闪出"。</p>
 *
 * <p><b>未配灰度 = 全量放行</b>，由活动状态 ONLINE 控制参与资格：这条语义是链路 0 的断言，
 * 也是"新建活动默认可参与"的约定。所以规则来自 {@link GrayRuleCache}（真值在 DB 列）
 * 而不是 yml —— yml 那份在 2026-09-23 删掉了，它既改不动也留不住（Redis 与重启都会让它漂）。</p>
 */
@Service
@RequiredArgsConstructor
public class GrayService {

    private final GrayRuleCache cache;

    /**
     * 判断用户是否可参与活动。
     *
     * @param activityNo 活动编号；未配灰度规则视为全量
     */
    public boolean hit(String activityNo, Long userId) {
        Optional<GrayRuleCache.Rule> rule = cache.rule(activityNo);
        if (rule.isEmpty()) {
            return true;
        }
        if (userId != null && rule.get().whitelist().contains(userId)) {
            return true;
        }
        long uid = userId == null ? 0L : userId;
        return Math.floorMod(uid, 100L) < rule.get().percent();
    }
}
```

删除 `marketing-activity/src/main/java/com/example/marketing/activity/config/GrayProperties.java`，并确认全仓再无引用：

Run: `grep -rn "GrayProperties\|marketing\.gray" --include=*.java --include=*.yml marketing-* docker scripts 2>/dev/null; echo "exit=$?"`
Expected: 只剩 `marketing.gray.refresh-seconds`（新属性）这一处命中；`GrayProperties` 零命中。

- [ ] **Step 6: 写并跑 `GrayServiceTest`**

```java
package com.example.marketing.activity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 灰度判定本身。规则来源换成 DB 之后，这些语义必须一条不变：
 * 未配规则全量、percent=0 一个都不放、白名单优先于 0%、取模命中稳定。
 */
class GrayServiceTest {

    private static GrayService with(String activityNo, Optional<GrayRuleCache.Rule> rule) {
        GrayRuleCache cache = mock(GrayRuleCache.class);
        when(cache.rule(anyString())).thenReturn(Optional.empty());
        when(cache.rule(activityNo)).thenReturn(rule);
        return new GrayService(cache);
    }

    @Test
    @DisplayName("未配灰度 = 全量放行（链路 0 依赖的语义）")
    void noRuleMeansFullTraffic() {
        assertTrue(with("A1", Optional.empty()).hit("A1", 70001L));
    }

    @Test
    @DisplayName("percent=0 一个都不放，除非在白名单里")
    void zeroPercentBlocksEveryoneButWhitelist() {
        GrayService s = with("A1", Optional.of(new GrayRuleCache.Rule(0, Set.of(999L))));
        assertFalse(s.hit("A1", 70001L));
        assertTrue(s.hit("A1", 999L), "白名单要能穿透 0% —— 内测账号靠它");
    }

    @Test
    @DisplayName("percent=5 时按 userId 取模命中，且同一用户结果稳定")
    void percentModuloIsStable() {
        GrayService s = with("A1", Optional.of(new GrayRuleCache.Rule(5, Set.of())));
        assertTrue(s.hit("A1", 70001L), "70001 % 100 = 1 < 5");
        assertFalse(s.hit("A1", 70050L), "70050 % 100 = 50 >= 5");
        assertTrue(s.hit("A1", 101L));
    }

    @Test
    @DisplayName("userId 缺失按 0 处理（未登录也要有确定答案）")
    void nullUserIdUsesZero() {
        assertTrue(with("A1", Optional.of(new GrayRuleCache.Rule(5, Set.of()))).hit("A1", null));
        assertFalse(with("A1", Optional.of(new GrayRuleCache.Rule(0, Set.of()))).hit("A1", null));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-activity -am test -Dtest='GrayRuleCacheTest,GrayServiceTest'`
Expected: PASS（4+4）

- [ ] **Step 7: 删两处 yml 的灰度块**

`marketing-activity/src/main/resources/application.yml` 删掉整段（含上面那行注释）：

```yaml
# 本地灰度规则（profile=nacos 时由 Nacos 配置中心推送覆盖/刷新）
marketing:
  gray:
    ACT2026001:
      percent: 100
      whitelist: [ ]
```

`marketing-standalone/src/main/resources/application.yml` 删掉 `gray:` 那四行，并把上面的注释改为：

```yaml
marketing:
  # 只保留真正存在形态差异 / 无代码默认的配置：mq.type（LITE 用 Redis Stream）
  # 与 admin（后台密钥）。灰度规则已移进 activity.gray_percent 列——它是数据，不是配置
  mq:
    type: ${MQ_TYPE:redis-stream}
```

- [ ] **Step 8: 变异检查 + 全模块回归**

变异：① 把 `GrayService.hit` 的"无规则返回 true"改成 false → `noRuleMeansFullTraffic` 与 `zeroPercentBlocksEveryoneButWhitelist` 必须红（这条改了会直接掀链路 0）；② 把 `clamp` 的 `> 100` 分支删掉 → `percentIsClamped` 必须红；③ 把 `refreshNow` 的 catch 改成 `rules = Map.of()` → `refreshFailureKeepsPreviousRules` 必须红。

Run: `source scripts/common.sh && mvn -q -pl marketing-activity,marketing-standalone -am test`
Expected: 全绿（`StandaloneComponentScanTest` 不受影响；`ActivityServiceTest` 2 条不受影响）

- [ ] **Step 9: 提交**

```bash
git add marketing-activity marketing-standalone
git commit -m "feat(config): 灰度真值落 activity 列并按 5s 回源，删掉 yml 的 marketing.gray"
```

---

### Task 7: discount 与 seckill 的参数接入在线值（不留空按钮）

**Files:**
- Create: `marketing-discount/src/main/java/com/example/marketing/discount/config/DiscountConfigDefinitions.java`
- Create: `.../discount/config/DiscountRuntimeConfig.java`
- Modify: `.../discount/service/DiscountCalcService.java:63`
- Modify: `.../discount/engine/PromoEngine.java:46`
- Create: `marketing-seckill/src/main/java/com/example/marketing/seckill/config/SeckillConfigDefinitions.java`
- Create: `.../seckill/config/SeckillRuntimeConfig.java`
- Modify: `.../seckill/service/SeckillStockService.java`（字段 + `:64,86,105,152,165`）
- Modify: `.../seckill/job/SeckillTimeoutJob.java:50`（+ 新字段）
- Modify: `marketing-seckill/src/test/java/com/example/marketing/seckill/service/SeckillStockServiceTest.java:16`
- Modify: `marketing-discount/src/test/java/com/example/marketing/discount/engine/PromoEngineBenchmarkTest.java:67`
- Test: `.../discount/config/DiscountRuntimeConfigTest.java`
- Test: `.../seckill/config/SeckillRuntimeConfigTest.java`

**Interfaces:**
- Consumes: `ConfigValues`（Task 1）、`ConfigDefinition.ofInt/ofLong`、`ConfigDefinitionProvider`
- Produces:
  - `DiscountRuntimeConfig.calcTimeoutMs() : long`、`maxRulesPerOrder() : int`
  - `SeckillRuntimeConfig.tokenTtlSeconds() / payTimeoutSeconds() / boughtMarkTtlSeconds() : long`
  - 键：`discount.calc-timeout-ms`、`discount.max-rules-per-order`、`seckill.token-ttl-seconds`、`seckill.pay-timeout-seconds`、`seckill.bought-mark-ttl-seconds`

- [ ] **Step 1: 写失败测试（在线值优先、缺省回 properties）**

`SeckillRuntimeConfigTest.java`：

```java
package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 三个 TTL 的取值优先级。为什么要有这个包装类：不包一层，就要在 6 个调用点各写一遍
 * "在线值 or 出厂值"，而漏掉一处就是"改了这个参数、那个没变"的静默不一致。
 */
class SeckillRuntimeConfigTest {

    private static ConfigValues withOnline(String key, String value) {
        ConfigValues values = new ConfigValues(new ConfigSchemaRegistry(
                List.of(new SeckillConfigDefinitions())), new SimpleMeterRegistry());
        values.apply(new ConfigSnapshot(1L, "now",
                Map.of(key, new ConfigSnapshot.Entry(value, ConfigType.LONG, 0L))));
        return values;
    }

    @Test
    @DisplayName("没有在线覆盖时用 SeckillProperties 的值（=今天的默认行为）")
    void fallsBackToProperties() {
        SeckillRuntimeConfig cfg = new SeckillRuntimeConfig(ConfigValues.empty(), new SeckillProperties());
        assertEquals(600L, cfg.tokenTtlSeconds());
        assertEquals(300L, cfg.payTimeoutSeconds());
        assertEquals(86400L, cfg.boughtMarkTtlSeconds());
    }

    @Test
    @DisplayName("在线值存在且合法时优先；越界的条目由 ConfigValues 挡掉后回出厂")
    void onlineValueWinsAndOutOfRangeFallsBack() {
        assertEquals(120L, new SeckillRuntimeConfig(
                withOnline("seckill.token-ttl-seconds", "120"), new SeckillProperties()).tokenTtlSeconds());
        assertEquals(600L, new SeckillRuntimeConfig(
                withOnline("seckill.token-ttl-seconds", "5"), new SeckillProperties()).tokenTtlSeconds(),
                "5 低于下界 30，必须退回 600 而不是把排队 token 变成 5 秒");
    }

    @Test
    @DisplayName("分桶数不进白名单：它同时是 seckill_activity.buckets 列")
    void bucketsNotOnlineEditable() {
        assertEquals(0, new SeckillConfigDefinitions().definitions().stream()
                .filter(d -> d.key().contains("buckets")).count());
    }
}
```

`DiscountRuntimeConfigTest.java`：

```java
package com.example.marketing.discount.config;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscountRuntimeConfigTest {

    private static DiscountRuntimeConfig withOnline(String key, String value) {
        ConfigValues values = new ConfigValues(new ConfigSchemaRegistry(
                List.of(new DiscountConfigDefinitions())), new SimpleMeterRegistry());
        values.apply(new ConfigSnapshot(1L, "now",
                Map.of(key, new ConfigSnapshot.Entry(value, ConfigType.INT, 0L))));
        return new DiscountRuntimeConfig(values, new DiscountProperties());
    }

    @Test
    @DisplayName("出厂值来自 DiscountProperties")
    void fallsBackToProperties() {
        DiscountRuntimeConfig cfg = new DiscountRuntimeConfig(ConfigValues.empty(), new DiscountProperties());
        assertEquals(50L, cfg.calcTimeoutMs());
        assertEquals(5, cfg.maxRulesPerOrder());
    }

    @Test
    @DisplayName("在线值优先，且超时阈值与叠加数各自独立生效")
    void onlineValuesWin() {
        DiscountRuntimeConfig cfg = withOnline("discount.max-rules-per-order", "2");
        assertEquals(2, cfg.maxRulesPerOrder());
        assertEquals(50L, cfg.calcTimeoutMs(), "只改了一个参数，另一个不该跟着漂");
    }

    @Test
    @DisplayName("下界：maxRulesPerOrder 为 0 会被拒（等于任何订单都不给优惠）")
    void zeroRulesPerOrderRejected() {
        assertEquals(5, withOnline("discount.max-rules-per-order", "0").maxRulesPerOrder());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `source scripts/common.sh && mvn -q -pl marketing-seckill,marketing-discount -am test -Dtest='SeckillRuntimeConfigTest,DiscountRuntimeConfigTest'`
Expected: 编译失败 `找不到符号 SeckillRuntimeConfig`

- [ ] **Step 3: 实现两组声明与包装**

```java
package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀的在线可调参数。
 *
 * <p><b>{@code seckill.buckets} 故意不在清单里</b>：它同时是 {@code seckill_activity.buckets}
 * 列（SeckillController 还有硬编码兜底 16），在线改全局值与行值会变成两套真相。</p>
 *
 * <p>{@code service()} 是模块名而不是进程名：LITE 下这些参数跑在 marketing-standalone 进程里，
 * 但归属仍然是 seckill 模块，后台要显示后者。</p>
 */
@Component
public class SeckillConfigDefinitions implements ConfigDefinitionProvider {

    public static final String TOKEN_TTL = "seckill.token-ttl-seconds";
    public static final String PAY_TIMEOUT = "seckill.pay-timeout-seconds";
    public static final String BOUGHT_MARK_TTL = "seckill.bought-mark-ttl-seconds";

    @Override
    public String service() {
        return "marketing-seckill";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofLong(TOKEN_TTL, 600, 30, 86400, "排队 token 结果保留时长（秒）"),
                ConfigDefinition.ofLong(PAY_TIMEOUT, 300, 30, 86400, "未支付订单回补库存的超时（秒）"),
                ConfigDefinition.ofLong(BOUGHT_MARK_TTL, 86400, 60, 2592000, "防重购标记 TTL（秒）"));
    }
}
```

```java
package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 三个 TTL 的唯一取值出口：在线值 &gt; {@link SeckillProperties}。
 *
 * <p>包一层而不是在 6 个调用点各写一遍，是因为漏掉一处就是"改了参数、只有部分行为变"——
 * 那比不能在线改更糟。</p>
 */
@Component
@RequiredArgsConstructor
public class SeckillRuntimeConfig {

    private final ConfigValues values;
    private final SeckillProperties properties;

    public long tokenTtlSeconds() {
        return values.longOr(SeckillConfigDefinitions.TOKEN_TTL, properties.getTokenTtlSeconds());
    }

    public long payTimeoutSeconds() {
        return values.longOr(SeckillConfigDefinitions.PAY_TIMEOUT, properties.getPayTimeoutSeconds());
    }

    public long boughtMarkTtlSeconds() {
        return values.longOr(SeckillConfigDefinitions.BOUGHT_MARK_TTL, properties.getBoughtMarkTtlSeconds());
    }
}
```

```java
package com.example.marketing.discount.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** 优惠引擎的在线可调参数。{@code versionKey}/{@code snapshotCheckSeconds} 不进清单：那是缓存自身的地基。 */
@Component
public class DiscountConfigDefinitions implements ConfigDefinitionProvider {

    public static final String CALC_TIMEOUT_MS = "discount.calc-timeout-ms";
    public static final String MAX_RULES_PER_ORDER = "discount.max-rules-per-order";

    @Override
    public String service() {
        return "marketing-discount";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofInt(CALC_TIMEOUT_MS, 50, 1, 5000, "单次计算超时（毫秒），超时降级返回原价"),
                ConfigDefinition.ofInt(MAX_RULES_PER_ORDER, 5, 1, 20, "整单最多叠加规则数"));
    }
}
```

```java
package com.example.marketing.discount.config;

import com.example.marketing.common.config.ConfigValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 两个引擎参数的唯一取值出口：在线值 &gt; {@link DiscountProperties}。 */
@Component
@RequiredArgsConstructor
public class DiscountRuntimeConfig {

    private final ConfigValues values;
    private final DiscountProperties properties;

    public long calcTimeoutMs() {
        return values.longOr(DiscountConfigDefinitions.CALC_TIMEOUT_MS, properties.getCalcTimeoutMs());
    }

    public int maxRulesPerOrder() {
        return values.intOr(DiscountConfigDefinitions.MAX_RULES_PER_ORDER, properties.getMaxRulesPerOrder());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-seckill,marketing-discount -am test -Dtest='SeckillRuntimeConfigTest,DiscountRuntimeConfigTest'`
Expected: PASS（3+3）

- [ ] **Step 5: 改道调用点**

`SeckillStockService`：把字段 `private final SeckillProperties properties;` 换成 `private final SeckillRuntimeConfig runtime;`，5 处 `properties.getTokenTtlSeconds()` / `properties.getBoughtMarkTtlSeconds()` 分别换成 `runtime.tokenTtlSeconds()` / `runtime.boughtMarkTtlSeconds()`（`:64,86,105,152,165`），import 换成 `com.example.marketing.seckill.config.SeckillRuntimeConfig`。

`SeckillTimeoutJob:50`：`properties.getPayTimeoutSeconds()` → `runtime.payTimeoutSeconds()`，并新增字段 `private final SeckillRuntimeConfig runtime;`（`properties` 仍保留，`:74` 还要读 buckets）。

`DiscountCalcService:63`：`properties.getCalcTimeoutMs()` → `runtime.calcTimeoutMs()`，新增字段 `private final DiscountRuntimeConfig runtime;`（`properties` 仍保留，`snapshot()` 路径还在用它）。

`PromoEngine:46`：`properties.getMaxRulesPerOrder()` → `runtime.maxRulesPerOrder()`，新增字段（该类若是 `@RequiredArgsConstructor` 就加一个 final 字段；若是手写构造器，参数追加在 `DiscountProperties` 之后）。

- [ ] **Step 6: 修两处既有测试的构造调用**

`SeckillStockServiceTest:16`：

```java
    private final SeckillStockService service = new SeckillStockService(
            null, new SeckillRuntimeConfig(ConfigValues.empty(), new SeckillProperties()));
```

`PromoEngineBenchmarkTest:67`：

```java
        PromoEngine engine = new PromoEngine(new DiscountProperties(), ConfigValues.empty());
```

取值口径在本任务里是定死的：`PromoEngine` 与 `DiscountCalcService` 各自只读一个参数，所以注入 `ConfigValues` 而不是 `DiscountRuntimeConfig`；`DiscountRuntimeConfig` 只服务于"一处要读多个参数"的调用点（seckill 的三个 TTL 在同一个类里都被读）。判断标准一句话：**读一个参数直接问 `ConfigValues`，读两个以上才包一层 RuntimeConfig**——包早了是多一个类，包晚了就是漏改一处。因此 Step 5 里 `PromoEngine` 的新字段是 `ConfigValues values`，`DiscountCalcService` 同理；`SeckillStockService`/`SeckillTimeoutJob` 用 `SeckillRuntimeConfig`。

- [ ] **Step 7: 变异检查 + 两模块回归**

Run: `source scripts/common.sh && mvn -q -pl marketing-seckill,marketing-discount -am test`
Expected: 全绿（含既有 `SeckillWarmUpServiceTest` 2、`SeckillStockServiceTest`、`AllocatorTest`、`CombinationSelectorTest`、`RuleCacheManagerTest`）

变异：① 把 `SeckillRuntimeConfig.tokenTtlSeconds()` 改回 `properties.getTokenTtlSeconds()` → `onlineValueWinsAndOutOfRangeFallsBack` 第一条必须红；② 把 `SeckillStockService` 里 5 处中的 1 处漏改（保留 `properties`）→ 编译不过（字段已删），说明包装层是唯一的取值口，这正是设计意图；③ 把 `DiscountRuntimeConfig.maxRulesPerOrder()` 的下界从 1 改成 0 → `zeroRulesPerOrderRejected` 必须红。

- [ ] **Step 8: 提交**

```bash
git add marketing-discount marketing-seckill
git commit -m "feat(config): 优惠与秒杀的 5 个参数接进在线值（每模块一个唯一取值出口）"
```

---

### Task 8: `DEPLOY_FORM` 与轮询间隔落到五套入口

**Files:**
- Modify: `docker/docker-compose.preview.yml`（standalone 与 gateway 两个 environment 块）
- Modify: `docker/docker-compose.full-app.yml`（`&app-env` 锚点一处，五个服务继承）
- Modify: `scripts/start-all.sh`（`ADMIN_JWT_SECRET` 导出之后）
- Modify: `scripts/start-dev.sh:40-45` 区段
- Test/验证: 无 Java 测试，靠 `docker compose config` 与 `bash -n` + 实跑

**Interfaces:**
- Consumes: `marketing.config.form`（Task 2/4/5 的属性名）
- Produces: 环境变量 `DEPLOY_FORM ∈ {LITE, FULL, DEV}` 与 `CONFIG_POLL_SECONDS`（可选，默认 5）

- [ ] **Step 1: LITE 容器档两个服务加 `DEPLOY_FORM: LITE`**

`docker-compose.preview.yml`：`standalone.environment` 里 `MQ_TYPE: redis-stream` 之后加：

```yaml
      # 形态标识：在线配置按它取 form=LITE 的覆盖（没有则只看 GLOBAL）
      # 与下面 gateway 的 RL_* 是同一件事的两条路：env 是出厂值，DB 是在线覆盖
      DEPLOY_FORM: LITE
```

`gateway.environment` 里 `RL_DISCOUNT: "500"` 之后加同一行 `DEPLOY_FORM: LITE`（带注释"网关与业务进程必须同一形态标识，否则两档阈值会互相看不见"）。

- [ ] **Step 2: FULL 容器档在锚点加一次**

`docker-compose.full-app.yml` 的 `&app-env` 里、`SPRING_PROFILES_ACTIVE: nacos` 之后加：

```yaml
    # 一容器一服务，全部继承同一个 form=FULL：扩容档的阈值只从 DB 的 GLOBAL/FULL 行取
    DEPLOY_FORM: FULL
```

- [ ] **Step 3: 两套本机脚本导出**

`scripts/start-all.sh` 在 `ADMIN_JWT_SECRET` 那两行之后加：

```bash
# 形态标识：在线配置按它分档。进程形态与容器形态同档，所以这里也是 FULL。
export DEPLOY_FORM="${DEPLOY_FORM:-FULL}"
```

`scripts/start-dev.sh` 在 `export ADMIN_JWT_SECRET=...`（`:45`）之后、`start_jvm` 定义之前加：

```bash
# 必须与密钥一样放在启动任何 JVM 之前导出：standalone 与 gateway 要在同一个 form 下解析
export DEPLOY_FORM="${DEPLOY_FORM:-DEV}"
```

- [ ] **Step 4: 静态验证**

```bash
bash -n scripts/start-all.sh scripts/start-dev.sh
docker compose -f docker/docker-compose.preview.yml config | grep -c "DEPLOY_FORM"
docker compose -f docker/docker-compose.full-app.yml config | grep -c "DEPLOY_FORM"
```
Expected: `bash -n` 无输出；preview 至少 **2** 次命中；full-app 至少 **6** 次命中（锚点展开到 5 个服务 + 锚点自身，按实际输出判读，**只要少于 5 就说明锚点没继承到某个服务**）。

- [ ] **Step 5: 实跑一次 LITE 并确认 form 落地**

```bash
./scripts/deploy-preview.sh
docker logs mkt-preview-standalone 2>&1 | grep -m1 "\[config\]"
docker logs mkt-preview-gateway 2>&1 | grep -m1 "\[config\]"
docker exec mkt-redis redis-cli KEYS 'mkt:cfg:schema:*'
```
Expected: 两条日志都显示 `form=LITE`；Redis 里出现 `mkt:cfg:schema:marketing-standalone` 与 `mkt:cfg:schema:marketing-gateway` 两个自述键（业务模块的参数自述在 LITE 下挂在 standalone 上，这是预期）。

- [ ] **Step 6: 提交**

```bash
git add docker/docker-compose.preview.yml docker/docker-compose.full-app.yml scripts/start-all.sh scripts/start-dev.sh
git commit -m "feat(config): DEPLOY_FORM 落到五套入口（LITE/FULL 容器、FULL/dev 进程）"
```

---

### Task 9: `41010 本形态不适用`（把 reheat 从 41000 迁出）

**Files:**
- Modify: `marketing-admin/src/main/java/com/example/marketing/admin/controller/AdminCacheController.java`（`:36` 的 Javadoc、`:68` 的抛码、`:69` 的文案）
- Modify: `scripts/smoke-test.sh:290`（链路 4 的 FULL 分支两条断言）
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/controller/AdminCacheControllerTest.java`

**Interfaces:**
- Consumes: `ErrorCode.FORM_NOT_APPLICABLE`（Task 5 加的）
- Produces: FULL 分进程下 `POST /api/admin/cache/reheat` 返回 `41010`；错误文案点名"③ 的跨进程回执"

- [ ] **Step 1: 写失败测试**

`AdminCacheControllerTest.java`（用 `Proxy` 打桩 `CacheReheatRegistry`，与 `ActivityServiceTest` 同手法；不起 Spring）：

```java
package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只在某档可用的能力必须显式报错，不能静默成功（母版风险 #4）。
 * 这里的"报错"用 41010 而不是 41000：41000 是业务失败，运营看到它会去找业务方，
 * 而真正该做的是换形态执行。
 */
class AdminCacheControllerTest {

    private static CacheReheatRegistry registryWith(List<String> types) {
        return (CacheReheatRegistry) Proxy.newProxyInstance(
                CacheReheatRegistry.class.getClassLoader(),
                new Class<?>[]{CacheReheatRegistry.class},
                (proxy, method, args) -> "types".equals(method.getName()) ? types : null);
    }

    private static AdminIdentityService identity() {
        AdminIdentityService identityService = mock(AdminIdentityService.class);
        // require(request, String... roles)：varargs 位置用 any() 才匹配任意个数（OPERATIONAL 是两个）
        when(identityService.require(any(MockHttpServletRequest.class), org.mockito.ArgumentMatchers.<String>any()))
                .thenReturn(new AdminPrincipal(1L, "admin", "admin", "jti"));
        when(identityService.require(any(MockHttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin", "admin", "jti"));
        return identityService;
    }

    @Test
    @DisplayName("本进程没有 reheater 时报 41010，并且留下拒绝痕迹")
    void emptyRegistryReportsFormNotApplicable() {
        AuditService auditService = mock(AuditService.class);
        AdminCacheController controller =
                new AdminCacheController(registryWith(List.of()), identity(), auditService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        BizException e = assertThrows(BizException.class,
                () -> controller.reheat(request, "budget", "ACT2026001", true));
        assertEquals(ErrorCode.FORM_NOT_APPLICABLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("③"), "文案要点名下一个把它接起来的段: " + e.getMessage());
        verify(auditService).record(org.mockito.ArgumentMatchers.argThat(r ->
                r.resultCode() == ErrorCode.FORM_NOT_APPLICABLE.getCode()));
    }

    @Test
    @DisplayName("本进程有 reheater 时正常分发（LITE 路径不受影响）")
    void dispatchesWhenAvailable() {
        CacheReheatRegistry registry = (CacheReheatRegistry) Proxy.newProxyInstance(
                CacheReheatRegistry.class.getClassLoader(), new Class<?>[]{CacheReheatRegistry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "types" -> List.of("budget");
                    case "reheat" -> new CacheReheater.Result("budget", "ACT2026001", 0L, 7000L, "fake");
                    default -> null;
                });
        AuditService auditService = mock(AuditService.class);
        var result = new AdminCacheController(registry, identity(), auditService)
                .reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true);
        assertEquals(7000L, result.after());
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test -Dtest=AdminCacheControllerTest`
Expected: 编译期或断言失败（当前抛的是 41000，且 `Proxy` 出来的 `registryWith(List.of())` 让既有代码走 `isEmpty()` 分支 → 第二条断言 `FORM_NOT_APPLICABLE` 红）

- [ ] **Step 2: 改 controller**

`AdminCacheController.java`：

- 类 Javadoc 末段把"跨进程转发属于 ⑤…本轮不做"改成："跨进程**回执**属于 ③（写库 + pending/ack 轮询，母版 §6.3），⑤ 只交付了这把轮询器本身；这里显式报 `41010`。"
- 拒绝分支与抛错：

```java
            auditService.record(new AuditRecord(denied.uid(), denied.username(), denied.role(),
                    "cache.reheat", type, key, "POST", "/api/admin/cache/reheat",
                    "type=" + type + ", key=" + key + ", force=" + force,
                    ErrorCode.FORM_NOT_APPLICABLE.getCode(), "本进程无 reheater", ClientIp.of(request), 0));
            throw BizException.of(ErrorCode.FORM_NOT_APPLICABLE,
                    "当前进程没有任何缓存重预热实现（FULL 分进程形态下 reheater 在业务服务里），"
                            + "请在 owning 服务上执行，或等 ③ 的跨进程重预热回执");
```

- [ ] **Step 3: 跑测试确认通过**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin -am test -Dtest=AdminCacheControllerTest`
Expected: PASS（2 个用例）

- [ ] **Step 4: 改 smoke 链路 4 的两条 FULL 分支断言**

`scripts/smoke-test.sh:290-291`：

```bash
    expect "FULL 分进程下重预热显式报错（不静默返回成功）" '"code":41010' "$R"
    expect "报错里点名 owning 服务与待办形态" '③' "$R"
```

- [ ] **Step 5: 确认 `41000` 在 smoke 里剩下的都是真业务错误**

Run: `grep -n "41000\|41010" scripts/smoke-test.sh`
Expected: `41000` 只剩链路 0 的"重复活动编号被拒"（`:97`）一条；`41010` 两处都在链路 4。若还有别处 `41000`，逐个判读它是不是真业务冲突，不许顺手改码。

- [ ] **Step 6: 提交**

```bash
git add marketing-admin scripts/smoke-test.sh
git commit -m "refactor(admin): 本形态不适用的能力改用 41010，41000 只留给业务失败"
```

---

### Task 10: smoke 链路 5（第十条核心链路）与登出位置调整

**Files:**
- Modify: `scripts/smoke-test.sh`（头部注释与 helper 区、`:174` 的 trap、`:306-308` 的登出后移、末尾新增链路 5）

**Interfaces:**
- Consumes: 链路 4 的 `$AAUTH` / `$VIEWER_TOKEN`、Task 4 的在线限流、Task 5 的 `/api/admin/config`、Task 6 的灰度列、Task 8 的 `DEPLOY_FORM`
- Produces: 基线断言数从 52 涨到 64（本段结束时 README 记这个数）

- [ ] **Step 1: 把链路 4 末尾的登出挪到全脚本最后**

删除 `scripts/smoke-test.sh` 的 `:306-308`（"登出后同一枚 token 立即失效"那三行），并在链路 5 之后（脚本 `echo` 汇总之前）原样贴回：

```bash
head2 "收尾：登出与会话吊销"
curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/auth/logout" >/dev/null
expect "登出后会话立即失效" '"code":40102' "$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/users")"
```

理由必须写进注释：链路 5 复用同一枚 token，再登录会撞 `LoginGuard` 的每 IP 10 次/分钟（母版事实 #4），而"因为限速所以测不了"是最坏的一种红。

- [ ] **Step 2: 加清理 trap 与两个 helper**

在 `audit_max_id()` 定义之后加：

```bash
# 链路 5 会改在线配置与灰度列。跑挂了也不许把 3/s 的阈值留给下一轮形态——
# 那会让下一次冒烟在链路 3 上莫名其妙地红，而排查方向被指向异步链路。
CFG_WRITES=()
config_cleanup() {
  for w in "${CFG_WRITES[@]:-}"; do
    [ -z "$w" ] && continue
    curl -s -m 10 -X DELETE -H "$AAUTH" \
      "$GW/api/admin/config?cfgKey=${w%%|*}&form=${w##*|}" >/dev/null
  done
  curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/config/rebroadcast" >/dev/null
}
# 连发 n 发秒杀查询，返回被限流（429）的次数
throttle_hits() {
  local n=$1 hits=0 code
  for _ in $(seq 1 "$n"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$GW/api/seckill/activities")
    [ "$code" = "429" ] && hits=$((hits+1))
  done
  echo "$hits"
}
# 等在线配置收敛（轮询默认 5s，给两倍余量）
wait_cfg() { sleep 8; }
redis_admin() { docker exec mkt-redis redis-cli "$@"; }
```

并把 `:174` 的 `trap 'rm -rf "$TMP"' EXIT` 改成：

```bash
trap 'rm -rf "$TMP"; config_cleanup' EXIT
```

- [ ] **Step 3: 写链路 5（12 条断言）**

在链路 4 之后、收尾登出之前插入：

```bash
head2 "链路 5：在线配置下发（不重启改阈值 → 快照丢失退 yml → 灰度真值在 DB）"
SECKILL_LIMIT="gateway.ratelimit.seckill-route.limit"
CFG='Content-Type: application/json'

# 0) 本进程解析到的形态：两档共库时它是唯一可信的"我在哪一档"
CFG_JSON=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/config")
OWN_FORM=$(echo "$CFG_JSON" | sed -n 's/.*"ownForm":"\([^"]*\)".*/\1/p')
OTHER_FORM="LITE"; [ "$OWN_FORM" = "LITE" ] && OTHER_FORM="FULL"
[ -n "$OWN_FORM" ] && ok "配置页报出自己的形态 form=$OWN_FORM" || bad "ownForm 缺失（Task 8 的 DEPLOY_FORM 没落地？）" "$CFG_JSON"

# 1) 改阈值不重启：GLOBAL 收到 3/s，连发 6 发必须看到 429
R=$(curl -s -m 15 -X PUT -H "$AAUTH" -H "$CFG" "$GW/api/admin/config" \
  -d "{\"cfgKey\":\"$SECKILL_LIMIT\",\"form\":\"GLOBAL\",\"value\":\"3\",\"remark\":\"smoke\"}")
expect "写 GLOBAL 阈值成功且生效值=3" '"effectiveValue":"3"' "$R"
CFG_WRITES+=("$SECKILL_LIMIT|GLOBAL")
wait_cfg
sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" -ge 1 ] && ok "阈值 3/s 不重启生效（6 发中 $HITS 发 429）" \
  || bad "阈值未生效（期望至少 1 发 429）" "hits=$HITS"

# 2) 非法输入在写侧就被拒，库里不留行
expect "越界值被拒（40000）" '"code":40000' "$(curl -s -m 10 -X PUT -H "$AAUTH" -H "$CFG" \
  "$GW/api/admin/config" -d "{\"cfgKey\":\"$SECKILL_LIMIT\",\"form\":\"GLOBAL\",\"value\":\"0\"}")"
expect "未声明的键被拒（40000）" '"code":40000' "$(curl -s -m 10 -X PUT -H "$AAUTH" -H "$CFG" \
  "$GW/api/admin/config" -d '{"cfgKey":"nope.key","form":"GLOBAL","value":"1"}')"

# 3) 写权限细筛：operator 能过网关（OPERATIONAL），但不能改阈值
OP_TOKEN=$(curl -s -m 25 -X POST "$GW/api/admin/auth/login" -H "$CFG" \
  -d '{"username":"operator","password":"demo123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
expect "operator 改阈值被拒（40300）" '"code":40300' "$(curl -s -m 10 -X PUT -H "Authorization: Bearer $OP_TOKEN" \
  -H "$CFG" "$GW/api/admin/config" -d "{\"cfgKey\":\"$SECKILL_LIMIT\",\"form\":\"GLOBAL\",\"value\":\"9\"}")"

# 4) 分形态不串：给另一档写一个极端值，本档生效值必须不动
curl -s -m 15 -X PUT -H "$AAUTH" -H "$CFG" "$GW/api/admin/config" \
  -d "{\"cfgKey\":\"$SECKILL_LIMIT\",\"form\":\"$OTHER_FORM\",\"value\":\"199999\"}" >/dev/null
CFG_WRITES+=("$SECKILL_LIMIT|$OTHER_FORM")
wait_cfg
# 先取值再比字符串：管道拼 JSON 的写法一旦 python 报错就会变假绿
EFFECTIVE=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/config" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print([x['effectiveValue'] for x in d['entries'] if x['key']=='$SECKILL_LIMIT'][0])" \
  2>/dev/null || echo "?")
[ "$EFFECTIVE" = "3" ] && ok "另一档（$OTHER_FORM）写 199999 不污染本档 form=$OWN_FORM" \
  || bad "跨形态覆盖串了：本档生效值=$EFFECTIVE（期望 3）" "other=$OTHER_FORM"

# 5) 快照丢失 → 退回本进程 yml 出厂值，且网关继续服务（既不过限也不 500）
redis_admin DEL mkt:cfg:snapshot:GLOBAL mkt:cfg:version:GLOBAL \
  mkt:cfg:snapshot:LITE mkt:cfg:version:LITE \
  mkt:cfg:snapshot:FULL mkt:cfg:version:FULL \
  mkt:cfg:snapshot:DEV mkt:cfg:version:DEV >/dev/null
wait_cfg
sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" = "0" ] && ok "快照被删后退回 yml 出厂值（6 发全通过，未过限也未拒绝服务）" \
  || bad "快照丢失后仍在限流或已不可服务" "hits=$HITS"

# 6) "重新广播"修好已落库未广播的窗口
expect "重新广播返回成功码" '"code":0' "$(curl -s -m 10 -X POST -H "$AAUTH" \
  "$GW/api/admin/config/rebroadcast")"
wait_cfg
sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" -ge 1 ] && ok "重广播后在线值重新生效（$HITS 发 429）" \
  || bad "重广播没有恢复在线阈值" "hits=$HITS"

# 7) 恢复出厂 = 删行（不写回原值）
expect "删除覆盖返回成功" '"code":0' "$(curl -s -m 10 -X DELETE -H "$AAUTH" \
  "$GW/api/admin/config?cfgKey=$SECKILL_LIMIT&form=GLOBAL")"
wait_cfg
sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" = "0" ] && ok "删行后回到出厂阈值（LITE/FULL 各自的 RL_* 值）" \
  || bad "删行没有恢复出厂" "hits=$HITS"

# 8) 灰度：真值在 DB 列，改 0 后 ≤8s 不再命中（链路 0 建的 smoke 活动此时是 FINISHED，
#    但 gray-hit 是独立只读口，不校验状态，正好用它能改的这份数据）
GRAY_UID_IN=70001     # 70001 % 100 = 1
GRAY_UID_OUT=70050    # 70050 % 100 = 50
if docker exec mkt-mysql mysql -umarketing -pmarketing123 -e \
     "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=5 WHERE activity_no='$ACT_NO'" >/dev/null 2>&1; then
  wait_cfg
  expect "灰度 5% 时尾号命中的用户放行" '"data":true' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=$GRAY_UID_IN")"
  expect "灰度 5% 时尾号不命中的用户被拒" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=$GRAY_UID_OUT")"
  # Redis 被清空也不能把"曾设 5%"变成意外全量——这是灰度走 DB 的全部理由
  redis_admin DEL mkt:cfg:snapshot:$OWN_FORM mkt:cfg:version:$OWN_FORM \
    mkt:cfg:snapshot:GLOBAL mkt:cfg:version:GLOBAL >/dev/null
  wait_cfg
  expect "删光 Redis 键后灰度仍是 5%（不是全量放行）" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=$GRAY_UID_OUT")"
else
  bad "无法直连 mkt-mysql 改灰度（链路 5 的灰度断言没跑）" "docker exec 失败"
fi
```

灰度那一步不留痕：`CFG_WRITES` 只登记配置键，灰度列在链路 5 末尾直接还原（它属于本轮新建的临时活动）：

```bash
docker exec mkt-mysql mysql -umarketing -pmarketing123 -e \
  "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=NULL WHERE activity_no='$ACT_NO'" >/dev/null 2>&1
```

- [ ] **Step 4: 数一遍断言总数并跑脚本静态检查**

```bash
bash -n scripts/smoke-test.sh
grep -c "^ *expect \|^ *&& ok \|^ *ok \"" scripts/smoke-test.sh
```
Expected: `bash -n` 无输出；粗数条数应比改动前多 12（`grep -n` 只作参考，真实数以实跑汇总行为准）。

- [ ] **Step 5: 在当前已就绪的 LITE 栈上实跑**

```bash
./scripts/smoke-test.sh 2>&1 | tee /tmp/smoke-5.log | tail -30
```
Expected: 汇总行 `通过 64 / 失败 0`（基线 52 条含链路 0-4，加链路 5 新增 12 条；实际数字以输出为准，但**失败必须为 0**）。若 `mkt-preview-*` 不是本段构建的产物，先 `./scripts/deploy-preview.sh` 重建再跑。

失败时**保留全量日志**再排查（本仓库的规矩：不吞日志）：`docker logs mkt-preview-standalone`、`docker logs mkt-preview-gateway`、`docker exec mkt-redis redis-cli KEYS 'mkt:cfg:*'`。

- [ ] **Step 6: 提交**

```bash
git add scripts/smoke-test.sh
git commit -m "test(smoke): 链路 5 在线配置下发 12 条断言，登出移到收尾以复用 token"
```

---

### Task 11: README 口径 + 五形态复跑

**Files:**
- Modify: `README.md`（环境变量表、API 表、"四条核心链路"计数、覆盖矩阵、新增一节"在线配置下发"）
- Modify: `docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md`（把段内 spec 确认的四条偏离回写成"实施偏离"小节）

- [ ] **Step 1: 环境变量表加两行**

在 `ADMIN_JWT_SECRET` / `RL_*` 所在的表里加：

```markdown
| `DEPLOY_FORM` | 空（=只认 GLOBAL 覆盖） | 形态标识 `LITE`/`FULL`/`DEV`：在线配置按它选 form 生效值 |
| `CONFIG_POLL_SECONDS` | `5` | 各进程比对配置版本的节拍；改阈值生效延迟上限 |
```

- [ ] **Step 2: 新增一节"在线配置下发（第 5 条链路）"**

放在"四条核心链路"小节之后，并把该小节标题改为 **五条核心链路**（README:397 附近的计数与 `docs/superpowers/specs/` 链接列表同步）。内容按这四块写，不许只写"支持在线改配置"：

1. **真值在哪**：`admin_config` 表（`cfg_key`+`form` 唯一）与 `activity.gray_percent/gray_whitelist` 列；**删行 = 恢复出厂**。
2. **生效路径表**：照段内 spec §5 那张六行表原样收录（限流 5 条 + discount 2 + seckill 3 的键、声明方、消费点、缺值回退）。
3. **形态隔离**：`DEPLOY_FORM` 决定读哪一份；未设置时只认 `GLOBAL`，**不部署新 env 就等于没有这套机制**。
4. **失败语义**：越界与未声明在写侧被 `40000` 拒；读侧逐条忽略退回 yml（记 `marketing.config.entry.ignored`）；落库未广播是 `41009` 并有"重新广播"动作。灰度不依赖 Redis。

- [ ] **Step 3: 更新 API 表**

`/api/admin/**` 那一段加四行（GET 配置总览 / PUT 写值 / DELETE 恢复出厂 / POST 重广播），并注明"写只有 `admin` 角色；`operator` 读得到但改不动阈值"。

- [ ] **Step 4: 刷新测试计数与覆盖矩阵**

```bash
source scripts/common.sh
echo "测试类数: $(find . -path '*/src/test/*' -name '*Test.java' | wc -l | tr -d ' ')"
echo "@Test 数: $(grep -rh "@Test" --include=*Test.java . | wc -l | tr -d ' ')"
```
Expected: 类数从 25 涨到 35（新增 10 个测试类），`@Test` 数从 106 涨到 ≈140（以实数为准写进 README，不许写约数）。覆盖矩阵新增 ⑤ 一行：`在线配置下发 | 链路 5（12 断言）+ 单测 10 类 | LITE/FULL进程/FULL容器/dev/每服务一库 | 已跑通`。

- [ ] **Step 5: 五形态复跑（每档都要 64/64，且 form 判读正确）**

按下面顺序跑，每档记录汇总行与 `ownForm`：

```bash
# A. LITE 容器档（常态服役档）
./scripts/deploy-preview.sh && ./scripts/smoke-test.sh | tail -3
./scripts/stop-preview.sh

# B. FULL 进程形态（含 admin，6 个 JVM）
./scripts/start-all.sh && ./scripts/smoke-test.sh | tail -3
./scripts/stop-all.sh

# C. FULL 容器形态（nacos + lb://，含链路 5 的网关侧生效）
export ADMIN_JWT_SECRET=$(cat .admin-jwt-secret 2>/dev/null || openssl rand -base64 32)
SKIP_BUILD=1 ./scripts/deploy-full.sh && ./scripts/smoke-test.sh | tail -3

# D. dev 档（2 JVM，内存最小）
./scripts/start-dev.sh && ./scripts/smoke-test.sh | tail -3
./scripts/stop-dev.sh

# E. 每服务一库档（admin 是第 5 个库，验证隔离档下读写都成立）
MYSQL_DB_PER_SERVICE=1 ./scripts/start-all.sh && ./scripts/smoke-test.sh | tail -3
./scripts/stop-all.sh
```

Expected 三条硬判据：① 五档都 `失败 0`；② A 档 `ownForm=LITE`、B/C 档 `FULL`、D 档 `DEV`；③ A 与 B/C 之间**不做任何 SQL 清理**连续跑（这正是"同一份数据原地双向切换"的口径，链路 5 的第 4 条断言就是为这个场景写的）。

跑 A 之后额外复核一次内存（母版约束：standalone 超 640 MiB 才动 `mem_limit`）：

```bash
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' | grep mkt-preview
```
Expected: `mkt-preview-standalone` 在 529-599 MiB 区间附近；显著上涨就要说明是哪个件带来的（本段新增了 1 个 daemon 线程 + 一个小 map，预期增幅 < 5 MiB）。

- [ ] **Step 6: 把偏离回写进母版 spec**

`docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md` 末尾（§12 之后）加：

```markdown
## 13. 实施偏离（⑤ 落地时确认，已回写）

1. §5.1 的 version 取自 `MAX(version)+1` → 实际取 `INCR mkt:cfg:seq`：并发写撞出同一版本号会让读方停在陈旧快照上（静默不一致）。
2. §5.3 的发布目标按"表里出现过的 form" → 实际按固定四形态遍历，合并为空时**删**键：否则删干净某形态的行之后，"恢复出厂"不生效。
3. §5.4/§5.5 的灰度"Redis 只当变更通知 + activity 声明一条 ConfigDefinition" → 实际改为 activity 每 5s 回源 DB，灰度不进 `admin_config` 也不声明：通知键需要有人 bump，而 bump 者（业务侧后台写端点）的审计归属在 ③ 才解决（段内 spec §4）。
4. §5.5 "各服务把自述写进 `mkt:cfg:schema:{service}`" 的 `{service}` 取 `spring.application.name`（进程名），而 `ConfigDefinitionProvider.service()` 是模块名——LITE 下模块自述挂在 `marketing-standalone` 上，载荷里额外带 `owner` 字段供后台显示归属。
```

- [ ] **Step 7: 提交**

```bash
git add README.md docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md \
        docs/superpowers/specs/2026-09-23-online-config-delivery-design.md \
        docs/superpowers/plans/2026-09-23-online-config-delivery.md
git commit -m "docs(readme): ⑤ 在线配置下发口径（真值来源/形态隔离/失败语义）+ 五形态实测计数"
```

---

## 自检（写完后逐条对照 spec）

**1. spec 覆盖**

| 段内 spec / 母版 §5 条目 | 落在哪个任务 |
|---|---|
| §5.1 表结构与 `uk_key_form`、两份 init + 迁移、隔离档建库 | Task 3 |
| §5.2 `DEPLOY_FORM` 与"未设置=只认 GLOBAL" | Task 1（`ConfigForm`）、Task 8 |
| §5.3 单键全量快照 + version + schema 三键 | Task 1（载荷）、Task 2（读）、Task 5（写） |
| §5.4 回退分类：限流 FALLBACK_YML | Task 4（`RateRuleResolver` + filter） |
| §5.4 回退分类：灰度真值必须落 DB 列 | Task 3 + Task 6 |
| §5.4 写顺序 + `41009` + 重新广播 | Task 5 |
| §5.5 SPI、声明长在所属模块、不进 `seckill.buckets` | Task 4/6/7（Task 7 Step 3 有专门的断言钉 buckets） |
| §5.5 新的不带 DataSource 条件的自动装配（事实 #8） | Task 2 |
| §5.5 schema 自述 + admin 不 import 业务模块 | Task 2（写）+ Task 5（`ConfigSchemaReader`） |
| §5.5 不一致时代码赢 + ORPHAN 清单 | Task 1（`ConfigValues` 忽略）+ Task 5（`orphans` 字段） |
| §5.5 权限：写=仅 admin，operator 只读 | Task 5 Step 11 细筛 + Task 10 Step 3 断言 3 |
| 母版风险 #4 的 `41010`（reheat 迁移 + 链路 4 两条） | Task 5 Step 1 + Task 9 |
| §9 验收：单测 +≈14（实际 10 个新类）、链路 5、五形态复跑 | Task 1-7（单测）、Task 10、Task 11 Step 5 |
| 事实 #4（登录限速）与全脚本共用一次登录 | Task 10 Step 1（登出后移）；链路 5 只多一次 operator 登录（总 4 次 < 10） |

**未被任何任务覆盖的 spec 条目**：无。母版 §6.0 的"业务侧后台写端点 + 轻量身份件"已明确划给 ③（段内 spec §4 记录了审计这条未解的结），不属于 ⑤。

**2. 占位符扫描**：全文无 `TBD` / `TODO` / "稍后补充" / "同上" / "类似 Task N"；所有代码步骤都是可编译的完整正文。执行前再跑一次 `grep -n 'PLACEHOLDER\|TBD\|TODO\|Similar to\|同上' docs/superpowers/plans/2026-09-23-online-config-delivery.md` 确认没有回潮（本仓库的规矩：计划里出现占位符就是计划失败，不是执行者的自由裁量空间）。

**3. 类型一致性**：`ConfigSnapshot.Entry(String,ConfigType,long)` 在 Task 1/4/5/7 的构造处一致；`ConfigValues.longOr(String,long)/intOr(String,int)` 在 Task 4（int）与 Task 7（seckill 用 long、discount 的 timeout 用 long、rules 用 int）一致；`publishAll(long)`、`nextSequence()` 与 `AdminConfigService` 内部调用一致；`ConfigDefinition.ofLong` 的出厂值参数在 Task 7 用 `600` 而 `SeckillProperties.tokenTtlSeconds` 默认也是 600（一致）；`ConfigSchemaRegistry.empty()`（Task 2 Step 8 要求补）在 Task 5/7 的测试里使用，务必先补该静态方法。

---
