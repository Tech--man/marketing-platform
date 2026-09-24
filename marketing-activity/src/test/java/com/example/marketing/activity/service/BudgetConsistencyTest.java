package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预算自检：Redis 预扣值与对账口径（总预算 + SUM(流水)）比一下。
 *
 * <p>三种输出必须互不混淆：{@code 0} 是"查过且一致"、{@code >=1} 是"要重预热了"、
 * {@code -1} 是"判定不了"。把第三种写成 0 就等于在最需要读数的时候骗人。</p>
 */
class BudgetConsistencyTest {

    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ActivityMapper activityMapper;
    private BudgetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        activityMapper = mock(ActivityMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new BudgetService(redis, jdbc, activityMapper);

        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("ACT1"));
        ActivityEntity activity = new ActivityEntity();
        activity.setBudgetAmount(new BigDecimal("100.00"));
        when(activityMapper.selectOne(any())).thenReturn(activity);
        // 口径：100 元 - 已扣 30 元 = 7000 分
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(-3000L);
    }

    @Test
    @DisplayName("一致 → 0（不是 -1，也不是抽样数）")
    void matchIsZero() {
        when(values.get("activity:budget:ACT1")).thenReturn("7000");

        assertEquals(0, service.mismatchCount());
    }

    @Test
    @DisplayName("缓存值偏了 → 1：地雷 E（缺键后按全额重建导致预算回涨）就是这种形状")
    void driftedValueIsMismatch() {
        when(values.get("activity:budget:ACT1")).thenReturn("10000");

        assertEquals(1, service.mismatchCount());
    }

    @Test
    @DisplayName("键根本不在 → 也要算不符：缺失不等于预算为 0，它要的是 warm/reheat")
    void missingKeyIsMismatch() {
        when(values.get("activity:budget:ACT1")).thenReturn(null);

        assertEquals(1, service.mismatchCount());
    }

    @Test
    @DisplayName("Redis 读失败 → -1，绝不报成健康")
    void redisFailureIsUnknown() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertEquals(-1, service.mismatchCount());
    }

    @Test
    @DisplayName("自检读的就是那个约定形状的键：activity:budget:{no}")
    void keyShapeIsTheAgreedOne() {
        when(values.get(anyString())).thenReturn("7000");

        service.mismatchCount();

        assertTrue(keysProbed().contains("activity:budget:ACT1"),
                "键形与冒烟/运维侧的约定漂了: " + keysProbed());
    }

    private List<String> keysProbed() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values).get(captor.capture());
        return captor.getAllValues();
    }
}
