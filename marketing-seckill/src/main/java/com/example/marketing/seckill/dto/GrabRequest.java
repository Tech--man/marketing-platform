package com.example.marketing.seckill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 抢购请求（demo 约定 userId 由请求体携带，生产从鉴权上下文解析）。
 */
@Data
public class GrabRequest {

    @NotNull(message = "activityNo 不能为空")
    private String activityNo;
    @NotNull(message = "userId 不能为空")
    private Long userId;
}
