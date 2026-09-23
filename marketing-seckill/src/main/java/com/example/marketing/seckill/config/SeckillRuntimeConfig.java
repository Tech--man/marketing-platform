package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 三个 TTL 的唯一取值出口：在线值 &gt; {@link SeckillProperties}。
 *
 * <p>包一层而不是在 6 个调用点各写一遍，是因为漏掉一处就是"改了这个参数、那处行为没变"——
 * 那比不能在线改更糟：它给了你一个看起来生效了的假象。</p>
 */
@Component
@RequiredArgsConstructor
public class SeckillRuntimeConfig {

    private final ConfigValues values;
    private final SeckillProperties properties;

    public long tokenTtlSeconds() {
        return values.longOr(SeckillConfigDefinitions.TOKEN_TTL, properties.getTokenTtlSeconds());
    }

    public long payTimeoutSeconds() {
        return values.longOr(SeckillConfigDefinitions.PAY_TIMEOUT, properties.getPayTimeoutSeconds());
    }

    public long boughtMarkTtlSeconds() {
        return values.longOr(SeckillConfigDefinitions.BOUGHT_MARK_TTL, properties.getBoughtMarkTtlSeconds());
    }
}
