package com.example.marketing.openapi.distribution;

import java.math.BigDecimal;

/**
 * 分销领域接口（占位）：关系链绑定、佣金计算、T+1 结算。
 *
 * <p>规划的事件驱动链路：订单完成事件 → 关系链回溯（层级限制、防窜）→
 * 佣金计算（图关系存储建议 Neo4j/关系表+缓存）→ T+1 批量结算 → ClickHouse 分析。</p>
 */
public interface DistributionService {

    /** 绑定邀请关系（邀请码/二维码，需校验层级上限与自邀/环） */
    void bindRelation(Long userId, Long inviterId);

    /** 按订单计算佣金（事件驱动，返回佣金金额） */
    BigDecimal calculateCommission(String orderNo, BigDecimal orderAmount);
}
