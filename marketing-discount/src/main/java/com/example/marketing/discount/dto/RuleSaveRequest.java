package com.example.marketing.discount.dto;

import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 规则保存请求（管理端 upsert，按 ruleNo 幂等覆盖）。
 */
@Data
public class RuleSaveRequest {

    @NotBlank(message = "ruleNo 不能为空")
    private String ruleNo;
    @NotBlank(message = "name 不能为空")
    private String name;
    private String activityNo;
    @NotNull(message = "type 不能为空")
    private RuleType type;
    private Set<String> requiredTags;
    private Set<String> excludeTags;
    private BigDecimal threshold;
    private BigDecimal discountValue;
    private BigDecimal discountRate;
    private List<PromoRuleDsl.LadderStep> ladderSteps;
    private String mutexGroup;
    private int priority;
    private int perUserLimit = 1;
    /** ENABLED / DISABLED，默认生效 */
    private String status = "ENABLED";
}
