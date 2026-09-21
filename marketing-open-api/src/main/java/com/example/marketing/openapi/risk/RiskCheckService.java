package com.example.marketing.openapi.risk;

/**
 * 风控校验领域接口。
 *
 * <p>脚手架提供 {@link AllowAllRiskCheckService} 默认放行实现；生产接入时替换为
 * 独立风控中心调用，扩展点：</p>
 * <ul>
 *   <li>Sentinel 热点参数限流 + 设备指纹；</li>
 *   <li>Redis+Lua 行为计数（同 IP/设备/用户 领券频次）滑动窗口；</li>
 *   <li>黑名单（用户/设备/IP）布隆过滤器；</li>
 *   <li>大促预案：复杂行为分析规则可一键降级为仅黑名单校验。</li>
 * </ul>
 */
public interface RiskCheckService {

    /**
     * 校验一次营销动作是否放行。
     *
     * @param scene    场景标识，如 COUPON_GRANT / SECKILL_ORDER
     * @param userId   用户 ID
     * @param activityNo 活动编号
     * @return 校验结果（放行/拦截 + 原因）
     */
    RiskCheckResult check(String scene, Long userId, String activityNo);

    /** 校验结果 */
    record RiskCheckResult(boolean pass, String reason) {

        public static RiskCheckResult allow() {
            return new RiskCheckResult(true, null);
        }

        public static RiskCheckResult reject(String reason) {
            return new RiskCheckResult(false, reason);
        }
    }
}
