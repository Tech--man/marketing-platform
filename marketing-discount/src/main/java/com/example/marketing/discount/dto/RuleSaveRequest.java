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
    /**
     * 乐观锁版本（P1，2026-09-30 第二轮复审）：null = 新建；编辑/启停既有规则必带
     * 行上 version，与库里不相等回 41008。原实现挂在 query param 上而 UI 从不携带，
     * 等于既有规则的编辑/启停在管理界面恒 41008——并入 body 与券/活动同形。
     */
    private Integer version;
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
