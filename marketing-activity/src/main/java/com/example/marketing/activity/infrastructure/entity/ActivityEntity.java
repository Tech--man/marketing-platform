package com.example.marketing.activity.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动主表实体（表 marketing_activity.activity）。
 */
@Data
@TableName("activity")
public class ActivityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 活动编号（业务唯一键） */
    private String activityNo;
    private String name;
    /** 状态，见 ActivityStatus */
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 总预算（元） */
    private BigDecimal budgetAmount;
    /** 已用预算（元），DB 兜底，实时以 Redis 为准 */
    private BigDecimal usedAmount;
    private String remark;

    @Version
    private Integer version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
