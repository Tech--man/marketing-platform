package com.example.marketing.discount.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 促销规则（表 marketing_discount.promo_rule）。
 *
 * <p>规则正文以 JSON DSL 存储于 rule_json，条件/动作/互斥组全部包含在内；
 * 冗余 type/mutex_group/priority 列便于运营检索与按类型管理。</p>
 */
@Data
@TableName("promo_rule")
public class PromoRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 规则编号（业务唯一键） */
    private String ruleNo;
    private String name;
    private String activityNo;
    /** FULL_REDUCTION / DISCOUNT / LADDER */
    private String ruleType;
    /** 互斥组名（冗余列，可空） */
    private String mutexGroup;
    private Integer priority;
    /** ENABLED 生效 / DISABLED 停用 */
    private String status;
    /** 规则 JSON DSL 全文 */
    private String ruleJson;

    @Version
    private Integer version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
