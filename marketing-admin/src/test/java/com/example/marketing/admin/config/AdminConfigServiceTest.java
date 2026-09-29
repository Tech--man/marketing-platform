package com.example.marketing.admin.config;

import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ConfigOverviewView;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.common.security.AdminPrincipal;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台配置写路径的裁决。每条都对应一个现实后果：
 * 未声明的键写了没人消费（静默按钮）、越界值进了 DB（下次大促炸）、
 * 广播失败被当成功（静默不一致）、删行不生效（恢复出厂是假的）。
 */
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
        when(schemaReader.declared()).thenReturn(List.of(DEF));
        when(schemaReader.find(KEY)).thenReturn(Optional.of(DEF));
        when(schemaReader.find("nope.key")).thenReturn(Optional.empty());
        when(schemaReader.serviceByKey()).thenReturn(java.util.Map.of(KEY, "marketing-gateway"));
        ConfigValues values = new ConfigValues(ConfigSchemaRegistry.empty(), new SimpleMeterRegistry());
        auditService = mock(AuditService.class);
        service = new AdminConfigService(store, new ConfigSnapshotPublisher(redis, store, schemaReader),
                schemaReader, values, auditService, "LITE");
    }

    @Test
    @DisplayName("写成功：行落库带 seq 版本、LITE 快照与版本都发出、审计记下 before/after")
    void happyPathWritesThenBroadcasts() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "大促收口", null), "127.0.0.1");
        assertEquals("120", store.findValue(KEY, "LITE"));
        assertEquals("11", store.detail().get(0).version());
        assertEquals("admin", store.detail().get(0).updatedBy());
        verify(ops).set(eq(ConfigKeys.snapshot("LITE")), anyString());
        verify(ops).set(eq(ConfigKeys.version("LITE")), eq("11"));
        // 其余三个形态合并后为空 → 删键而不是留旧值（否则"恢复出厂"不生效）
        verify(redis, times(3)).delete(anyCollection());
        verify(auditService).record(argThat(r -> "config.set".equals(r.action())
                && r.requestSummary().contains("to=120") && "admin".equals(r.actorName())
                && r.resultCode() == 0));
    }

    @Test
    @DisplayName("未声明的键直接拒（不做「接受了但没人消费」的静默按钮）")
    void undeclaredKeyRejected() {
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest("nope.key", "LITE", "1", "", null), "ip"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(store.rows().isEmpty(), "被拒的写不许在库里留行");
        verify(ops, never()).increment(ConfigKeys.SEQUENCE);
    }

    @Test
    @DisplayName("越界值在碰 DB、序号与 Redis 之前就拒")
    void outOfRangeRejectedBeforeTouchingAnything() {
        assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "LITE", "0", "", null), "ip"));
        assertTrue(store.rows().isEmpty());
        verify(ops, never()).set(anyString(), anyString());
        verify(ops, never()).increment(ConfigKeys.SEQUENCE);
    }

    @Test
    @DisplayName("非法 form 拒：写进一个没人读的形态就是幽灵配置")
    void unknownFormRejected() {
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "PREVIEW", "120", "", null), "ip"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("GLOBAL"), "报错要把允许的取值说清楚: " + e.getMessage());
    }

    @Test
    @DisplayName("广播失败 → 41009，行仍在库里，且这次拒绝也被审计")
    void broadcastFailureSurfacesAs41009AndKeepsRow() {
        doThrow(new IllegalStateException("redis down")).when(ops).set(anyString(), anyString());
        BizException e = assertThrows(BizException.class, () -> service.set(ADMIN,
                new ConfigSetRequest(KEY, "LITE", "120", "", null), "ip"));
        assertEquals(ErrorCode.CONFIG_NOT_BROADCAST.getCode(), e.getCode());
        assertEquals("120", store.findValue(KEY, "LITE"),
                "已落库是事实，回滚只会让审计与库里状态对不上");
        verify(auditService).record(argThat(r -> r.resultCode() == ErrorCode.CONFIG_NOT_BROADCAST.getCode()));
    }

    @Test
    @DisplayName("删除命中才重广播；未命中 40400 且不占序号")
    void deleteIsRestoreToFactory() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "", null), "ip");
        org.mockito.Mockito.clearInvocations(ops, redis);
        service.delete(ADMIN, KEY, "LITE", "ip");
        assertEquals(null, store.findValue(KEY, "LITE"));
        verify(redis, times(4)).delete(anyCollection());

        when(ops.increment(ConfigKeys.SEQUENCE)).thenReturn(12L);
        assertThrows(BizException.class, () -> service.delete(ADMIN, KEY, "LITE", "ip"));
        // 没命中的删除不占序号：clearInvocations 之后只有第一次删除消耗过一次 INCR
        verify(ops, times(1)).increment(ConfigKeys.SEQUENCE);
    }

    @Test
    @DisplayName("overview 报出自己的形态、生效值与来源，GLOBAL 行对 LITE 可见而 FULL 行不可见")
    void overviewExposesSourceOfTruth() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "GLOBAL", "150", "", null), "ip");
        service.set(ADMIN, new ConfigSetRequest(KEY, "FULL", "9999", "", null), "ip");
        ConfigOverviewView view = service.overview();
        assertEquals("LITE", view.ownForm());
        var entry = view.entries().stream().filter(e -> e.key().equals(KEY)).findFirst().orElseThrow();
        assertEquals("150", entry.effectiveValue(), "LITE 该看到 GLOBAL 的值而不是 FULL 的 9999");
        assertEquals("GLOBAL", entry.source());
        assertEquals(2, entry.rows().size());
        assertEquals("marketing-gateway", entry.service());
    }

    @Test
    @DisplayName("重新广播是幂等的：只按 DB 现状重发并推进版本")
    void rebroadcastRepublishesFromDb() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "", null), "ip");
        when(ops.increment(ConfigKeys.SEQUENCE)).thenReturn(21L);
        org.mockito.Mockito.clearInvocations(ops, redis);
        assertEquals(21L, service.rebroadcast(ADMIN, "ip"));
        verify(ops).set(eq(ConfigKeys.snapshot("LITE")), anyString());
        verify(ops).set(eq(ConfigKeys.version("LITE")), eq("21"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("B5-2：expectedVersion 过期 → 41008 且不落库、不广播")
    void staleExpectedVersionRejected() {
        // 第一笔落 version=11（H2 起始 seq），拿旧 version=3 再写：CAS 落空
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "", null), "ip");
        org.mockito.Mockito.clearInvocations(ops, redis);
        BizException e = assertThrows(BizException.class,
                () -> service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "200", "", 3L), "ip"));
        assertEquals(41008, e.getCode());
        assertEquals("120", store.findValue(KEY, "LITE"), "冲突写不许覆盖别人的值");
        verify(ops, org.mockito.Mockito.never()).set(anyString(), anyString());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("B5-2：expectedVersion 与行上一致 → 正常写入")
    void matchingExpectedVersionWrites() {
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "120", "", null), "ip");
        Long current = store.findRowVersion(KEY, "LITE");
        service.set(ADMIN, new ConfigSetRequest(KEY, "LITE", "200", "", current), "ip");
        assertEquals("200", store.findValue(KEY, "LITE"));
    }
}
