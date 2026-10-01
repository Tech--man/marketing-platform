package com.example.marketing.common.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动期 schema 迁移断言（2026-10-01 审计 P1-1）。
 *
 * <p><b>为什么需要它</b>：第八批起新代码硬依赖 {@code idempotent_record.claim_token} 与
 * {@code admin_audit_log.source_id} 两列，而这两列只存在于迁移脚本（2026-10-08-*.sql）里——
 * 新建的卷由 init DDL 直接带上，但<b>既有卷必须有人跑 migrate.sh</b>。此前唯一的闸是
 * "操作者记得跑"：忘了跑的卷上新 jar 一启动，C 端每一次幂等写（领券/秒杀/预算扣减）与
 * 每一次后台审计落库都 {@code Unknown column} → 全线 500。部署入口虽已串进 migrate.sh，
 * 仍需要一道进程侧的兜底——把"上线后第一批请求炸"提前成"启动即失败"，报错还能直接
 * 指到该跑的命令上。</p>
 *
 * <p><b>声明式配置</b>：各服务在 yml 里只声明自己代码路径真正要写的列——</p>
 * <pre>
 * marketing:
 *   schema-guard:
 *     required-columns: idempotent_record.claim_token,admin_audit_log.source_id
 * </pre>
 * <p>当前声明分布（N-10② 修正后）：coupon 声明 {@code idempotent_record.claim_token}
 * （C 端幂等写的唯一业务方），seckill 声明 {@code seckill_order.active}（取消置 NULL、
 * 回放过滤 eq(active,1)、三列唯一索引都硬依赖它），admin 声明
 * {@code admin_audit_log.source_id}，standalone（单库聚合全部写方）声明两项。
 * activity/discount/account 当前没有任何迁移列依赖、不声明。声明必须是本服务
 * <b>真正要写/要读的列</b>——"随大流声明"会把无关服务的启动绑到不属于自己的迁移上，
 * 将来别的库不建那张表它就起不来。声明为空时本守卫零查询直接放行（网关干脆没有
 * DataSource，自动装配整体跳过）。</p>
 *
 * <p><b>判定口径</b>：按<b>当前连接的库</b>（MySQL {@code SELECT DATABASE()} / H2
 * {@code SELECT SCHEMA()}）查 {@code information_schema.columns}——不查全实例，否则
 * 每服务一库的隔离档里"别的库有这列"会误判成本库已迁移。H2 可查是刻意的：
 * 单测用真 SQL 验证本守卫，而不是 mock JdbcTemplate 把判定逻辑 stub 掉。</p>
 *
 * <p><b>失败语义</b>：任何一张声明的表整表缺失、或任一声明列缺失，抛
 * {@link IllegalStateException} 让启动失败（fail-fast）。宁可起不来，也不带着
 * "第一批写请求必炸"的状态对外服务——与 AdminSecurityConfig 空密钥同一取舍。</p>
 */
public class SchemaMigrationGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationGuard.class);

    private final JdbcTemplate jdbcTemplate;
    private final List<String> requiredColumns;

    public SchemaMigrationGuard(JdbcTemplate jdbcTemplate, List<String> requiredColumns) {
        this.jdbcTemplate = jdbcTemplate;
        this.requiredColumns = requiredColumns == null ? List.of() : requiredColumns;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // "table.column" → 按表分组，一趟一条 SQL 查齐，报错也能按表说清
        Map<String, Set<String>> byTable = new LinkedHashMap<>();
        for (String raw : requiredColumns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int dot = entry.indexOf('.');
            if (dot <= 0 || dot == entry.length() - 1) {
                throw new IllegalStateException(
                        "marketing.schema-guard.required-columns 格式应为 table.column，收到: " + entry);
            }
            byTable.computeIfAbsent(entry.substring(0, dot).toLowerCase(), t -> new LinkedHashSet<>())
                    .add(entry.substring(dot + 1).toLowerCase());
        }
        if (byTable.isEmpty()) {
            log.debug("[schema-guard] 未声明必需列，跳过（account 等无相关表的服务属正常）");
            return;
        }

        String schema = currentSchema();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : byTable.entrySet()) {
            String table = e.getKey();
            List<String> existing = jdbcTemplate.queryForList(
                    "SELECT LOWER(column_name) FROM information_schema.columns "
                            + "WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = LOWER(?)",
                    String.class, schema, table);
            if (existing.isEmpty()) {
                problems.add("表 " + table + " 在库 " + schema + " 里不存在");
                continue;
            }
            for (String column : e.getValue()) {
                if (!existing.contains(column)) {
                    problems.add("列 " + table + "." + column + " 在库 " + schema + " 里不存在");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("[schema-guard] 缺 schema 迁移，拒绝启动：\n  - "
                    + String.join("\n  - ", problems)
                    + "\n对该库执行 ALL_DBS=1 ./scripts/migrate.sh（幂等，已应用的跳过）后重启。"
                    + "不补迁移就上线，幂等写入与审计落库会全线 Unknown column。");
        }
        log.info("[schema-guard] schema 迁移断言通过：库 {} 的 {} 张声明表列齐备（{}）",
                schema, byTable.size(), requiredColumns);
    }

    /**
     * 当前连接的库名。MySQL 用 DATABASE()（库名即 schema 名）；H2 没有 DATABASE()，
     * 用 SCHEMA()（单测跑的就是这条路径）。产品名识别失败时先试 MySQL 语法再退 H2，
     * 两条都炸说明 information_schema 本身不可用——这属于"守卫失明"，同样该启动失败。
     */
    private String currentSchema() {
        String product;
        try {
            product = jdbcTemplate.execute((ConnectionCallback<String>) con ->
                    con.getMetaData().getDatabaseProductName());
        } catch (DataAccessException e) {
            product = null;
        }
        boolean mysql = product != null && product.toLowerCase().contains("mysql");
        try {
            return jdbcTemplate.queryForObject(mysql ? "SELECT DATABASE()" : "SELECT SCHEMA()", String.class);
        } catch (DataAccessException e) {
            // 产品名识别失败或方言意外：换另一条再试一次
            String fallback;
            try {
                fallback = jdbcTemplate.queryForObject(
                        mysql ? "SELECT SCHEMA()" : "SELECT DATABASE()", String.class);
            } catch (DataAccessException unreachable) {
                // N-13（复审）：两条方言都炸，最常见原因是数据库本身不可达（数据层没起/
                // 发布顺序反了）。此前这里把裸 CannotGetJdbcConnectionException 直接抛出
                // 顶层，精心写的迁移指引文案反而到不了运维眼前——不可达与"列缺"同样
                // 需要指向明确的处置动作。
                throw new IllegalStateException("[schema-guard] 数据库不可达，无法执行迁移断言："
                        + "先确认数据层已启动（LITE/FULL 看 docker compose ps，DEV 看 data.yml 容器）"
                        + "与本服务连接配置，再重启；确认迁移已执行：ALL_DBS=1 ./scripts/migrate.sh", unreachable);
            }
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalStateException("[schema-guard] 连 information_schema 的当前库都读不出来，守卫失明", e);
        }
    }
}
