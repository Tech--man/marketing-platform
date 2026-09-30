package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 领券请求。requestId 由客户端生成（UUID），是全链路幂等键。
 *
 * <p>userId 不由客户端填：{@code CouponController} 用验过签名的身份重建这个记录，
 * 客户端送来的那一个在进入服务层之前就已经被替换掉了。</p>
 *
 * <p><b>P1（2026-09-30 第二轮复审）requestId 必须限长限形</b>：幂等表/本地消息表的
 * biz_key 由 {@code BizKey.of} 折叠到 128，但事件载荷带的是<b>原始值</b>，消费端原样写
 * {@code user_coupon.request_id VARCHAR(128)}——超长请求会以 DataIntegrityViolation
 * （截断，非撞键）无限重投：每次受理烧 1 份库存且券永不落库。空白同样要拒：
 * BizKey 会 trim，幂等键与载荷原文不一致会造成"同 requestId 两处键"。</p>
 */
public record GrantRequest(
        @NotBlank(message = "requestId 必填")
        @Size(max = 128, message = "requestId 最长 128 字符（与 user_coupon.request_id 列宽一致）")
        @Pattern(regexp = "[\\x21-\\x7e]+", message = "requestId 只能是可见 ASCII 字符（不含空格/控制符）")
        String requestId,
        /** 服务端填充：控制器用验过签名的身份重建本记录。这里不加 @NotNull ——
         * 约束跑在反序列化之后、控制器填值之前，加了就等于拒绝所有合法请求。 */
        Long userId,
        @NotBlank(message = "templateNo 必填") String templateNo) {
}
