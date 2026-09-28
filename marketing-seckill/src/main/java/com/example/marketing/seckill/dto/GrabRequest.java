package com.example.marketing.seckill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 抢购请求。身份不再由请求体携带：账号体系上线后，userId 一律来自网关透传、
 * 业务侧自己验过签名的那枚 token（见 ConsumerRequestIdentity）。
 */
@Data
public class GrabRequest {

    @NotNull(message = "activityNo 不能为空")
    private String activityNo;
}
