package com.example.marketing.activity.controller;

import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.activity.service.BudgetDeductGuard;
import com.example.marketing.activity.service.BudgetService;
import com.example.marketing.activity.service.GrayService;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.exception.GlobalExceptionHandler;
import com.example.marketing.common.security.ConsumerClaims;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.common.security.ConsumerTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H10 回归（2026-09-29 架构审查）：C 端预算扣减端点必须有登录身份、过两道滥用闸。
 *
 * <p>收口前这个端点不 require 身份——任意一枚消费者 token 就能对任意活动、任意金额、
 * 自报幂等键地重复扣减（每换一个 bizKey 就是一次真扣）。</p>
 */
class ActivityControllerTest {

    private static final String SECRET = "unit-test-consumer-secret";

    private final ActivityService activityService = mock(ActivityService.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final GrayService grayService = mock(GrayService.class);
    private final BudgetDeductGuard guard = mock(BudgetDeductGuard.class);
    private final ConsumerRequestIdentity identity =
            new ConsumerRequestIdentity(SECRET, java.time.Duration.ofSeconds(30));

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ActivityController(activityService, budgetService, grayService,
                    guard, identity))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String access(long uid) {
        long now = java.time.Instant.now().getEpochSecond();
        return new ConsumerTokenCodec(SECRET, java.time.Duration.ofSeconds(30))
                .issue(new ConsumerClaims(uid, "u" + uid, "jti-" + uid,
                        ConsumerClaims.TYPE_ACCESS, now, now + 900));
    }

    @Test
    @DisplayName("H10：没登录就扣预算 → 40100（W9 后同时映射 HTTP 401），连滥用闸都不用到")
    void anonymousDeductRejected() throws Exception {
        mvc.perform(post("/api/activity/ACT1/budget/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":100,\"bizKey\":\"r-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        verify(budgetService, never()).deduct(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("登录后正常扣减：闸放行 → 进 BudgetService（幂等键语义不变）")
    void loggedInDeductPassesGuards() throws Exception {
        when(budgetService.deduct(eq("ACT1"), eq(100L), eq("r-1")))
                .thenReturn(BudgetService.DeductOutcome.DEDUCTED);

        mvc.perform(post("/api/activity/ACT1/budget/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Token", access(70001L))
                        .content("{\"amountCents\":100,\"bizKey\":\"r-1\"}"))
                .andExpect(jsonPath("$.code").value(0));

        verify(guard).checkAmount(100L);
        verify(guard).checkRate("ACT1", 70001L);
        verify(budgetService).deduct("ACT1", 100L, "r-1");
    }

    @Test
    @DisplayName("单笔超上限：闸先拦下（40000），一分钱都不进 BudgetService")
    void amountCapShortCircuits() throws Exception {
        doThrow(BizException.of(com.example.marketing.common.api.ErrorCode.BAD_REQUEST, "单笔上限"))
                .when(guard).checkAmount(999_999L);

        mvc.perform(post("/api/activity/ACT1/budget/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Token", access(70001L))
                        .content("{\"amountCents\":999999,\"bizKey\":\"r-2\"}"))
                .andExpect(jsonPath("$.code").value(40000));

        verify(budgetService, never()).deduct(anyString(), anyLong(), anyString());
    }
}
