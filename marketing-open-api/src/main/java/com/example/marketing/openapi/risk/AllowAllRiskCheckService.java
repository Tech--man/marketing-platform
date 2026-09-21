package com.example.marketing.openapi.risk;

/**
 * 风控默认实现：全部放行（脚手架占位）。
 *
 * <p>保留统一调用位点与降级语义：任何异常按"放行"处理，保证非核心风控不阻塞核心链路。</p>
 */
public class AllowAllRiskCheckService implements RiskCheckService {

    @Override
    public RiskCheckResult check(String scene, Long userId, String activityNo) {
        return RiskCheckResult.allow();
    }
}
