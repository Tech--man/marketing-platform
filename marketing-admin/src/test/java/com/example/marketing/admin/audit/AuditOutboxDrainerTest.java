package com.example.marketing.admin.audit;

import com.example.marketing.admin.infrastructure.entity.AdminAuditLogEntity;
import com.example.marketing.admin.infrastructure.mapper.AdminAuditLogMapper;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.audit.AuditPayloadCodec;
import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * drain 的三条硬约束：确认、时刻、脏数据不卡队列。
 *
 * <p>搬运是整条留痕链路里唯一会<b>静默</b>丢数据的一环（业务进程连不上这张表），
 * 所以这三条不能只靠"看起来实现了"。</p>
 */
@SuppressWarnings("unchecked")
class AuditOutboxDrainerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AuditOutboxDrainer drainer;

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(stream);
        drainer = new AuditOutboxDrainer(redis, new AuditService(auditMapper), meters, 5);
    }

    private MapRecord<String, Object, Object> record(String id, String json) {
        return MapRecord.<String, Object, Object>create(StreamKeys.auditPending(),
                        Map.<Object, Object>of(AuditPayloadCodec.FIELD, json))
                .withId(RecordId.of(id));
    }

    private void stubRead(List<MapRecord<String, Object, Object>> batch) {
        when(stream.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset[].class))).thenReturn(batch);
    }

    private AuditPayload payload() {
        return new AuditPayload(1L, "admin", "admin", "activity.budget.set", "activity", "ACT9001",
                "PUT", "/api/admin/activities/ACT9001/budget", "from=100.00, to=80.00",
                0, "", "10.0.0.7", 5L, 1_790_000_000L);
    }

    @Test
    @DisplayName("读到就落表并 XACK：不确认的条目会永远留在 PEL 里")
    void acksAfterPersisting() {
        stubRead(List.of(record("1-0", AuditPayloadCodec.write(payload()))));

        drainer.drainOnce();

        verify(auditMapper).insert(any(AdminAuditLogEntity.class));
        verify(stream).acknowledge(eq(StreamKeys.auditPending()), eq(StreamKeys.ADMIN_DRAIN_GROUP),
                any(RecordId[].class));
        verify(stream).delete(eq(StreamKeys.auditPending()), any(RecordId[].class));
    }

    @Test
    @DisplayName("create_time 用业务侧的时刻，不是搬运时刻（admin 停十分钟会让整条时间线位移）")
    void preservesBusinessTimestamp() {
        stubRead(List.of(record("1-0", AuditPayloadCodec.write(payload()))));

        drainer.drainOnce();

        ArgumentCaptor<AdminAuditLogEntity> captor = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(captor.capture());
        LocalDateTime expected = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(1_790_000_000L), ZoneId.systemDefault());
        assertNotNull(captor.getValue().getCreateTime());
        assertEquals(expected, captor.getValue().getCreateTime());
        assertEquals("from=100.00, to=80.00", captor.getValue().getRequestSummary());
        assertEquals(1.0, meters.counter("marketing.audit.drained").count(),
                "搬运动作本身也要可观测（④ 直接读这个计数）");
    }

    @Test
    @DisplayName("脏载荷跳过并计数，但仍要确认：一条坏数据不能把整条审计线卡住")
    void skipsUndecodableAndStillAcks() {
        stubRead(List.of(record("2-0", "{not json"), record("2-1", AuditPayloadCodec.write(payload()))));

        drainer.drainOnce();

        assertEquals(1.0, meters.counter("marketing.audit.drain.skipped").count());
        verify(auditMapper, times(1)).insert(any(AdminAuditLogEntity.class));
        verify(stream, times(2)).acknowledge(eq(StreamKeys.auditPending()),
                eq(StreamKeys.ADMIN_DRAIN_GROUP), any(RecordId[].class));
    }

    @Test
    @DisplayName("Redis 抛异常时本轮放弃、不崩线程：条目留在 PEL 里等下一轮")
    void redisFailureIsSwallowed() {
        when(stream.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset[].class))).thenThrow(new IllegalStateException("redis down"));

        drainer.drainOnce();

        assertEquals(1.0, meters.counter("marketing.audit.drain.error").count());
        verify(auditMapper, times(0)).insert(any(AdminAuditLogEntity.class));
    }

    @Test
    @DisplayName("建组撞上 BUSYGROUP（已有消费组）是正常情况，不报警")
    void busyGroupOnCreateGroupIsFine() {
        when(stream.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"));

        drainer.ensureGroup();

        verify(stream).createGroup(eq(StreamKeys.auditPending()), any(ReadOffset.class),
                eq(StreamKeys.ADMIN_DRAIN_GROUP));
    }

    @Test
    @DisplayName("建组必须从 0 开始：默认\'最新消息\'会让先投递后建组的审计永远不被读")
    void groupStartsFromZeroNotFromTail() {
        drainer.ensureGroup();

        ArgumentCaptor<ReadOffset> offset = ArgumentCaptor.forClass(ReadOffset.class);
        verify(stream).createGroup(eq(StreamKeys.auditPending()), offset.capture(),
                eq(StreamKeys.ADMIN_DRAIN_GROUP));
        assertEquals("0", offset.getValue().getOffset(),
                "从最新消息建组 = 静默丢掉建组之前投递的所有审计");
    }

    @Test
    @DisplayName("H5：落库失败绝不 ACK/XDEL——条目留 PEL 等回收，DB 故障窗口的审计不能被销毁")
    void insertFailureDefersToPel() {
        stubRead(List.of(record("3-0", AuditPayloadCodec.write(payload()))));
        when(auditMapper.insert(any(AdminAuditLogEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        drainer.drainOnce();

        verify(stream, org.mockito.Mockito.never()).acknowledge(eq(StreamKeys.auditPending()),
                eq(StreamKeys.ADMIN_DRAIN_GROUP), any(RecordId[].class));
        verify(stream, org.mockito.Mockito.never()).delete(eq(StreamKeys.auditPending()),
                any(RecordId[].class));
        assertEquals(1.0, meters.counter("marketing.audit.drain.deferred").count(),
                "deferred 计数是 ④ 面板区分「落库失败在重试」与「搬运故障」的依据");
    }

    @Test
    @DisplayName("H5：滞留 PEL 的过期条目被认领重试（死实例/上轮落库失败都靠这条路自愈）")
    void reclaimsStalePendingEntries() {
        // pending 里一条过期（空闲 60s >> 门槛 15s）、一条新鲜（不该被抢）
        org.springframework.data.redis.connection.stream.Consumer deadConsumer =
                org.springframework.data.redis.connection.stream.Consumer
                        .from(StreamKeys.ADMIN_DRAIN_GROUP, "admin-deadbeef");
        org.springframework.data.redis.connection.stream.PendingMessage stale =
                new org.springframework.data.redis.connection.stream.PendingMessage(
                        RecordId.of("9-0"), deadConsumer, java.time.Duration.ofSeconds(60), 1);
        org.springframework.data.redis.connection.stream.PendingMessage fresh =
                new org.springframework.data.redis.connection.stream.PendingMessage(
                        RecordId.of("9-1"), deadConsumer, java.time.Duration.ofSeconds(1), 1);
        when(stream.pending(eq(StreamKeys.auditPending()), eq(StreamKeys.ADMIN_DRAIN_GROUP),
                any(org.springframework.data.domain.Range.class), eq(100L)))
                .thenReturn(new org.springframework.data.redis.connection.stream.PendingMessages(
                        StreamKeys.ADMIN_DRAIN_GROUP, java.util.List.of(stale, fresh)));
        when(stream.claim(eq(StreamKeys.auditPending()), eq(StreamKeys.ADMIN_DRAIN_GROUP),
                anyString(), any(java.time.Duration.class), any(RecordId[].class)))
                .thenReturn(List.of(record("9-0", AuditPayloadCodec.write(payload()))));

        drainer.drainOnce();

        // 只认领过期的 9-0；新鲜的 9-1 没进 claim 的返回
        org.mockito.ArgumentCaptor<RecordId[]> ids = org.mockito.ArgumentCaptor.forClass(RecordId[].class);
        verify(stream).claim(eq(StreamKeys.auditPending()), eq(StreamKeys.ADMIN_DRAIN_GROUP),
                anyString(), any(java.time.Duration.class), ids.capture());
        assertEquals(1, ids.getValue().length);
        assertEquals("9-0", ids.getValue()[0].getValue());
        verify(auditMapper).insert(any(AdminAuditLogEntity.class));
        verify(stream).acknowledge(eq(StreamKeys.auditPending()), eq(StreamKeys.ADMIN_DRAIN_GROUP),
                any(RecordId[].class));
    }
}
