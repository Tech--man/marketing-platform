package com.example.marketing.discount.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠引擎的在线可调参数。
 *
 * <p>{@code versionKey} 与 {@code snapshotCheckSeconds} 不进清单：它们是规则缓存自身的地基，
 * 在线改它们会让"读缓存"这件事依赖缓存里存着的另一个缓存参数。</p>
 */
@Component
public class DiscountConfigDefinitions implements ConfigDefinitionProvider {

    public static final String CALC_TIMEOUT_MS = "discount.calc-timeout-ms";
    public static final String MAX_RULES_PER_ORDER = "discount.max-rules-per-order";

    @Override
    public String service() {
        return "marketing-discount";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofInt(CALC_TIMEOUT_MS, 50, 1, 5000, "单次计算超时（毫秒），超时降级返回原价"),
                ConfigDefinition.ofInt(MAX_RULES_PER_ORDER, 5, 1, 20, "整单最多叠加规则数"));
    }
}
