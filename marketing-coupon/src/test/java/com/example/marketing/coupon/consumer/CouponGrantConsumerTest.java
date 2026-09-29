package com.example.marketing.coupon.consumer;

import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.CouponGrantEvent;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H6 回归（2026-09-29 架构审查）：{@code user_coupon} 的两个唯一索引撞哪一个，
 * 处置完全不同——{@code uk_request_id} 是重复投递（幂等回放后 confirm），
 * {@code uk_coupon_code} 是券码随机撞码（换码重试）。
 *
 * <p>原实现把两者混在同一个 catch 里一律"幂等忽略 + confirm"：撞码时券没落库、
 * 补偿链路却被确认闭环、预扣的库存与限领计数永不归还——静默丢券，日发 10 万张时
 * 单日撞码概率约 42%。</p>
 */
class CouponGrantConsumerTest {

    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
    private final LocalMessageService localMessageService = mock(LocalMessageService.class);
    private final CouponGrantConsumer consumer = new CouponGrantConsumer(
            userCouponMapper, templateMapper, localMessageService, new SimpleMeterRegistry());

    private CouponGrantEvent event() {
        CouponGrantEvent event = new CouponGrantEvent();
        event.setRequestId("REQ-H6");
        event.setUserId(70001L);
        event.setTemplateId(1L);
        return event;
    }

    private void templateExists() {
        CouponTemplateEntity template = new CouponTemplateEntity();
        template.setId(1L);
        template.setTemplateNo("CT2026001");
        template.setActivityNo("ACT2026001");
        template.setCouponType("FULL_CUT");
        template.setValidDays(7);
        when(templateMapper.selectById(1L)).thenReturn(template);
    }

    @Test
    @DisplayName("撞券码（requestId 查无券）→ 换码重试后落库成功，confirm 只有一次")
    void codeCollisionRetriesWithNewCode() {
        templateExists();
        // 第一次 insert 撞 uk_coupon_code；回查 requestId 查无券（= 不是重复投递）
        when(userCouponMapper.insert(any(UserCouponEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_coupon_code"))
                .thenReturn(1);
        when(userCouponMapper.selectOne(any())).thenReturn(null);

        consumer.handle(JsonUtils.toJson(event()));

        verify(userCouponMapper, times(2)).insert(any(UserCouponEntity.class));
        verify(localMessageService, times(1)).confirm(anyString(), anyString());
    }

    @Test
    @DisplayName("真重复投递（requestId 查得到券）→ 不再插入，直接 confirm")
    void requestReplayConfirmsWithoutInsert() {
        templateExists();
        when(userCouponMapper.insert(any(UserCouponEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_request_id"));
        when(userCouponMapper.selectOne(any())).thenReturn(new UserCouponEntity());

        consumer.handle(JsonUtils.toJson(event()));

        verify(userCouponMapper, times(1)).insert(any(UserCouponEntity.class));
        verify(localMessageService, times(1)).confirm(anyString(), anyString());
    }

    @Test
    @DisplayName("重试上限内仍撞码 → 外抛交重投，绝不 confirm（闭环断了才有人知道出事了）")
    void exhaustedCollisionThrowsWithoutConfirm() {
        templateExists();
        when(userCouponMapper.insert(any(UserCouponEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_coupon_code"));
        when(userCouponMapper.selectOne(any())).thenReturn(null);

        assertThrows(DuplicateKeyException.class, () -> consumer.handle(JsonUtils.toJson(event())));

        verify(userCouponMapper, times(3)).insert(any(UserCouponEntity.class));
        verify(localMessageService, times(0)).confirm(anyString(), anyString());
    }
}
