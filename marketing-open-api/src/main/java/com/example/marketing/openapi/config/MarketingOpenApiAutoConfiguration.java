package com.example.marketing.openapi.config;

import com.example.marketing.openapi.risk.AllowAllRiskCheckService;
import com.example.marketing.openapi.risk.RiskCheckService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * open-api 自动装配：默认注册放行风控实现，业务方可用 @Bean 覆盖。
 */
@AutoConfiguration
public class MarketingOpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RiskCheckService.class)
    public RiskCheckService riskCheckService() {
        return new AllowAllRiskCheckService();
    }
}
