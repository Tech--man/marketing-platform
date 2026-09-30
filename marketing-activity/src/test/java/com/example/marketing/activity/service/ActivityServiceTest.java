package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 错误码规约：查不到东西是 40400，不是笼统的 41000"业务处理失败"。
 *
 * <p>对管理后台尤其要紧：列表点进详情、活动被删/改号之后，前端要能区分
 * "资源不在了"与"状态不允许这个操作"，前者该刷新列表，后者该提示状态机。</p>
 */
class ActivityServiceTest {

    private static ActivityMapper mapperReturning(ActivityEntity entity) {
        return (ActivityMapper) Proxy.newProxyInstance(
                ActivityMapper.class.getClassLoader(),
                new Class<?>[]{ActivityMapper.class},
                (proxy, method, args) -> "selectOne".equals(method.getName()) ? entity : null);
    }

    @Test
    @DisplayName("活动不存在返回 40400 NOT_FOUND")
    void missingActivityIsNotFound() {
        ActivityService service = new ActivityService(mapperReturning(null), null,
                org.mockito.Mockito.mock(ActivityGatePublisher.class));

        BizException e = assertThrows(BizException.class, () -> service.getByNo("ACT0000000"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode(),
                "查不到资源不该混在 41000 业务失败里");
    }

    @Test
    @DisplayName("活动编号重复仍是 41000（那是业务冲突，不是 404）")
    void duplicateActivityNoStaysBizError() {
        ActivityMapper existsMapper = (ActivityMapper) Proxy.newProxyInstance(
                ActivityMapper.class.getClassLoader(),
                new Class<?>[]{ActivityMapper.class},
                (proxy, method, args) -> "exists".equals(method.getName()) ? Boolean.TRUE : null);
        ActivityService service = new ActivityService(existsMapper, null,
                org.mockito.Mockito.mock(ActivityGatePublisher.class));

        BizException e = assertThrows(BizException.class,
                () -> service.create(new com.example.marketing.activity.dto.CreateActivityRequest(
                        "ACT2026001", "重复活动", null, null, null, null)));
        assertEquals(ErrorCode.BIZ_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("W2.4：startTime >= endTime 的创建直接拒（40000）——时间窗交叉此前无任何校验")
    void createRejectsInvertedTimeWindow() {
        ActivityService service = new ActivityService(mapperReturning(null), null,
                org.mockito.Mockito.mock(ActivityGatePublisher.class));
        java.time.LocalDateTime start = java.time.LocalDateTime.now();
        java.time.LocalDateTime end = start.minusDays(1);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new com.example.marketing.activity.dto.CreateActivityRequest(
                        "ACT9009", "倒窗口", start, end, java.math.BigDecimal.TEN, null)));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        // 相等也不行（窗口长度为 0 的活动没有参与意义）
        BizException equal = assertThrows(BizException.class,
                () -> service.create(new com.example.marketing.activity.dto.CreateActivityRequest(
                        "ACT9010", "零窗口", start, start, java.math.BigDecimal.TEN, null)));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), equal.getCode());
    }
}
