package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockHttpServletRequest;

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
 *
 * <p>"报错"用 41010 而不是 41000：41000 是业务失败，运营看到它会去找业务方排查，
 * 而这里真正该做的动作是换一个形态执行。另外被拒的运维动作同样要留痕——
 * 只记成功会让"有人试过刷这个键"在审计里彻底隐形。</p>
 *
 * <p>注册表用真对象而不是桩：它是个类（不可 Proxy），而且"从 Bean 里收集 reheater"
 * 正是这段逻辑要验的东西，造一个真的比模拟更贴近运行时。</p>
 */
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

    private static AdminIdentityService identity() {
        AdminIdentityService identityService = mock(AdminIdentityService.class);
        // require(request, String... roles) 的 varargs 位置：Mockito 的 any() 只匹配单个元素，
        // 而 OPERATIONAL 恰好是 {admin, operator} 两个，所以这里显式写两个 anyString()
        when(identityService.require(any(MockHttpServletRequest.class), anyString(), anyString()))
                .thenReturn(new AdminPrincipal(1L, "admin", "admin", "jti"));
        return identityService;
    }

    @Test
    @DisplayName("本进程没有 reheater 时报 41010，并留下拒绝痕迹")
    void emptyRegistryReportsFormNotApplicable() {
        AuditService auditService = mock(AuditService.class);
        AdminCacheController controller =
                new AdminCacheController(new CacheReheatRegistry(List.of()), identity(), auditService);

        BizException e = assertThrows(BizException.class,
                () -> controller.reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true));
        assertEquals(ErrorCode.FORM_NOT_APPLICABLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("③"), "文案要点名下一个把它接起来的段: " + e.getMessage());
        verify(auditService).record(ArgumentMatchers.<AuditRecord>argThat(r ->
                r.resultCode() == ErrorCode.FORM_NOT_APPLICABLE.getCode()
                        && "cache.reheat".equals(r.action())));
    }

    @Test
    @DisplayName("本进程有 reheater 时正常分发（LITE 路径不受影响），成功也留痕")
    void dispatchesWhenAvailable() {
        AuditService auditService = mock(AuditService.class);
        AdminCacheController controller = new AdminCacheController(
                new CacheReheatRegistry(List.of(BUDGET_REHEATER)), identity(), auditService);

        com.example.marketing.common.api.Result<CacheReheater.Result> response =
                controller.reheat(new MockHttpServletRequest(), "budget", "ACT2026001", true);

        assertEquals(0, response.getCode());
        assertEquals(7000L, response.getData().after());
        verify(auditService).record(ArgumentMatchers.<AuditRecord>argThat(r ->
                r.resultCode() == 0 && "ACT2026001".equals(r.resourceId())));
    }
}
