package com.example.marketing.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 上下线：ONLINE 补建缺失分桶（不覆盖已有进度），OFFLINE 只改状态 */
public record SeckillStatusRequest(
        @NotBlank @Pattern(regexp = "ONLINE|OFFLINE", message = "status 只能是 ONLINE 或 OFFLINE") String status,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
