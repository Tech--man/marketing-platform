package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 三个 TTL 与分桶数的唯一取值出口：TTL 是"在线值 &gt; {@link SeckillProperties}"，
 * 分桶数只有 properties 一份（母版 §10 刻意不把它做成在线参数：改桶数要配套重预热，
 * 让运维在后台填个数字就改，等于把一致性交给运气）。
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

    /**
     * 默认分桶数。<b>它摆在这里就是为了消灭第二份默认值</b>：
     * {@code SeckillController.stock} 原先写死 16，而预热走的是这里的配置 ——
     * 一旦 {@code SECKILL_BUCKETS} 不是 16，余量查询就会读错数量的桶（看着像"少了几桶"）。
     */
    public int buckets() {
        return properties.getBuckets();
    }
}
