package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ReheatReceipt;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.reheat.ReheatCodec;
import com.example.marketing.common.reheat.ReheatPayloads;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.transport.StreamKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只在某档可用的能力必须显式报错，不能静默成功（母版风险 #4）；③ T8 更进一步：
 * 能交给有能力的进程做就别报错，只做不了时才报错。
 *
 * <p>三条分支各自的可观测要求不同，所以分开验：</p>
 * <ul>
 *   <li>本进程有 reheater → 同步 DONE，绝不能顺手投一条给"自己"（会执行两次）；</li>
 *   <li>本进程没有但集群里有消费组 → DISPATCHED，投递必须留下"已投递"标记，
 *       否则回执端只能报 UNKNOWN，把"还在排队"说成"没这回事"；</li>
 *   <li>连消费组都没有 → 41010。文案要点名"没人认领"，因为运营该做的动作是把 owning
 *       服务起起来，而不是去找业务方排查（41000 会误导成后者）。</li>
 * </ul>
 *
 * <p>注册表用真对象而不是桩：它是个类（不可 Proxy），而且"从 Bean 里收集 reheater"
 * 正是这段逻辑要验的东西，造一个真的比模拟更贴近运行时。</p>
 */
@SuppressWarnings("unchecked")
class AdminCacheControllerTest {

    private static final CacheReheater BUDGET_REHEATER = new CacheReheater() {
        @Override
        public String type() {
            return "budget";
        }

        @Override
        public Result reheat(String key, boolean force) {
            return new Result(type(), key, 0L, 7000L, "budget_amount*100 - usedCents");
        }
    };

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AuditService auditService = mock(AuditService.class);

    private AdminCacheController controller(CacheReheatRegistry registry) {
        when(redis.opsForStream()).thenReturn(stream);
        when(redis.opsForValue()).thenReturn(values);
        return new AdminCacheController(registry, identity(), auditService, redis);
    }

    private static AdminIdentityService identity() {
        AdminIdentityService identityService = mock(AdminIdentityService.class);
        // require(request, String... roles) 的 varargs 位置：Mockito 的 any() 只匹配单个元素，
        // 而 OPERATIONAL 恰好是 {admin, operator} 两个，所以这里显式写两个 anyString()
        when(identityService.require(any(MockHttpServletRequest.class), anyString(), anyString()))
                .thenReturn(new AdminPrincipal(1L, "admin", "admin", "jti"));
        // 只读端点（types / ack）不带角色参数，varargs 为空
        when(identityService.require(any(MockHttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin", "admin", "jti"));
        return identityService;
    }

    private void givenGroups(int count) {
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        when(groups.size()).thenReturn(count);
        when(stream.groups(StreamKeys.reheatPending("budget"))).thenReturn(groups);
    }

    @Test
    @DisplayName("本进程没有 reheater 且集群无人认领时报 41010，并留下拒绝痕迹")
    void emptyRegistryWithNoConsumerReportsFormNotApplicable() {
        givenGroups(0);
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));

        BizException e = assertThrows(BizException.class,
                () -> controller.reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true));
        assertEquals(ErrorCode.FORM_NOT_APPLICABLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("没人") || e.getMessage().contains("没有"),
                "文案要说明是'没人认领'这个原因: " + e.getMessage());
        verify(auditService).record(ArgumentMatchers.<AuditRecord>argThat(r ->
                r.resultCode() == ErrorCode.FORM_NOT_APPLICABLE.getCode()
                        && "cache.reheat".equals(r.action())
                        && r.requestSummary().contains("rejected")));
        verify(stream, never()).add(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("XINFO 报错（流还不存在）等同于无人认领：不能投给一条没人读的流")
    void missingStreamCountsAsNoConsumer() {
        when(stream.groups(StreamKeys.reheatPending("budget")))
                .thenThrow(new IllegalStateException("no such key"));
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));

        assertThrows(BizException.class,
                () -> controller.reheat(new MockHttpServletRequest(), "budget", "K", true));
        verify(stream, never()).add(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("集群里有消费组时投递并回执 id：先写标记再 XADD")
    void dispatchesWhenSomebodyOwnsTheType() {
        givenGroups(1);
        when(values.increment(StreamKeys.REHEAT_SEQUENCE)).thenReturn(7L);
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));

        ReheatReceipt receipt = controller
                .reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true).getData();

        assertEquals("DISPATCHED", receipt.status());
        assertEquals("7", receipt.id());
        assertEquals("budget", receipt.type());
        // 顺序本身是要验的：反过来会有一段"已入队但标记还没写"的窗口，
        // 那段时间里查回执得到 UNKNOWN，等于把"还在排队"报成"根本没这回事"
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(values, stream);
        order.verify(values).set(eq(StreamKeys.reheatSent("budget", "7")), eq("ACT2026001"),
                eq(StreamKeys.REHEAT_RECEIPT_TTL));
        order.verify(stream).add(eq(StreamKeys.reheatPending("budget")),
                ArgumentMatchers.<Map<String, String>>argThat(m ->
                        ReheatCodec.readRequest(m.get(ReheatCodec.FIELD))
                                .filter(r -> "7".equals(r.id()) && "ACT2026001".equals(r.key())
                                        && r.force() && "admin".equals(r.actor()))
                                .isPresent()));
        // 审计里要能顺着 id 找到回执，否则"投出去了"和"投成了什么"是两条对不上的账
        verify(auditService).record(ArgumentMatchers.<AuditRecord>argThat(r ->
                r.resultCode() == 0 && r.requestSummary().contains("dispatched id=7")));
    }

    @Test
    @DisplayName("本进程有 reheater 时同步执行（LITE 路径不受影响），且不重复投递")
    void dispatchesWhenAvailable() {
        AdminCacheController controller = controller(
                new CacheReheatRegistry(List.of(BUDGET_REHEATER)));

        ReheatReceipt receipt = controller
                .reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true).getData();

        assertEquals("DONE", receipt.status());
        assertEquals(7000L, receipt.after());
        assertTrue(receipt.note().contains("budget_amount"),
                "公式要带回来，运营才知道刷成了什么口径: " + receipt.note());
        verify(stream, never()).add(anyString(), any(Map.class));
        verify(auditService).record(ArgumentMatchers.<AuditRecord>argThat(r ->
                r.resultCode() == 0 && "ACT2026001".equals(r.resourceId())
                        && r.requestSummary().contains("sync")));
    }

    @Test
    @DisplayName("执行侧抛了 → FAILED 回执原样带回原因，不降级成 UNKNOWN")
    void ackSurfacesFailureReason() {
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));
        ReheatPayloads.Ack ack = new ReheatPayloads.Ack("7", "budget", "ACT2026001", "FAILED",
                -1L, -1L, "java.lang.IllegalStateException: redis down", 1_790_000_000L);
        when(values.get(StreamKeys.reheatAck("budget", "7"))).thenReturn(ReheatCodec.writeAck(ack));

        ReheatReceipt receipt = controller.ack(new MockHttpServletRequest(), "budget", "7").getData();

        assertEquals("FAILED", receipt.status());
        assertTrue(receipt.error().contains("redis down"),
                "执行侧的异常要原样带回来: " + receipt.error());
    }

    @Test
    @DisplayName("回执不在、投递标记在 = 还在排队（并把 key 回显）；两个都不在 = UNKNOWN")
    void ackDistinguishesQueuedFromUnknown() {
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));
        when(values.get(anyString())).thenReturn(null);

        when(values.get(StreamKeys.reheatSent("budget", "7"))).thenReturn("ACT2026001");
        ReheatReceipt queued = controller.ack(new MockHttpServletRequest(), "budget", "7").getData();
        assertEquals("DISPATCHED", queued.status());
        assertEquals("ACT2026001", queued.key());

        when(values.get(StreamKeys.reheatSent("budget", "8"))).thenReturn(null);
        assertEquals("UNKNOWN", controller.ack(new MockHttpServletRequest(), "budget", "8")
                .getData().status());
    }

    @Test
    @DisplayName("回执内容损坏时降级 UNKNOWN 而不是抛：查询端点不能把后台打挂")
    void ackToleratesUndecodableReceipt() {
        AdminCacheController controller = controller(new CacheReheatRegistry(List.of()));
        when(values.get(StreamKeys.reheatAck("budget", "9"))).thenReturn("{not json");

        assertEquals("UNKNOWN", controller.ack(new MockHttpServletRequest(), "budget", "9")
                .getData().status());
        assertFalse(controller.ack(new MockHttpServletRequest(), "budget", "9").getData()
                .note().isBlank());
    }

    @Test
    @DisplayName("回执 TTL 与投递标记同源：过期后两边都该消失")
    void receiptTtlIsTenMinutes() {
        assertEquals(Duration.ofMinutes(10), StreamKeys.REHEAT_RECEIPT_TTL);
    }
}
