package com.example.marketing.account.dto;

import java.time.LocalDateTime;

/** 会话列表项。{@code current} 让前端能标出"这就是我现在这台设备"，不用它自己猜 jti。 */
public record ConsumerSessionVO(
        String jti,
        String ip,
        String userAgent,
        LocalDateTime createTime,
        LocalDateTime expireAt,
        boolean current) {
}
