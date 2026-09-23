package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀的在线可调参数。
 *
 * <p><b>{@code seckill.buckets} 故意不在清单里</b>：它同时是 {@code seckill_activity.buckets}
 * 列（{@code SeckillController} 还有硬编码兜底 16），在线改全局值与行值会变成两套真相。</p>
 *
 * <p>{@link #service()} 是模块名而不是进程名：LITE 下这些参数跑在 marketing-standalone 进程里，
 * 但归属仍是 seckill。</p>
 */
@Component
public class SeckillConfigDefinitions implements ConfigDefinitionProvider {

    public static final String TOKEN_TTL = "seckill.token-ttl-seconds";
    public static final String PAY_TIMEOUT = "seckill.pay-timeout-seconds";
    public static final String BOUGHT_MARK_TTL = "seckill.bought-mark-ttl-seconds";

    @Override
    public String service() {
        return "marketing-seckill";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofLong(TOKEN_TTL, 600, 30, 86400, "排队 token 结果保留时长（秒），前端轮询窗口"),
                ConfigDefinition.ofLong(PAY_TIMEOUT, 300, 30, 86400, "未支付订单回补库存的超时（秒）"),
                ConfigDefinition.ofLong(BOUGHT_MARK_TTL, 86400, 60, 2592000, "防重购标记 TTL（秒），要覆盖活动全程 + 回补窗口"));
    }
}
