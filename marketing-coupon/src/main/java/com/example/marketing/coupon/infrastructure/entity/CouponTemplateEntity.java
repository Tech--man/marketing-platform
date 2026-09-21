package com.example.marketing.coupon.infrastructure.entity;

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
 * 券模板（表 marketing_coupon.coupon_template）。
 */
@Data
@TableName("coupon_template")
public class CouponTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 模板编号（业务唯一键） */
    private String templateNo;
    private String activityNo;
    private String name;
    /** 券类型：FULL_REDUCTION 满减 / REDUCTION 立减 / DISCOUNT 折扣 */
    private String couponType;
    /** 面额（满减/立减为元；折扣为折扣率，如 8.50 表示 85 折） */
    private BigDecimal faceValue;
    /** 使用门槛（元），0 表示无门槛 */
    private BigDecimal thresholdAmount;
    private Integer totalStock;
    private Integer perUserLimit;
    /** 领取后有效天数 */
    private Integer validDays;
    /** ACTIVE 可领 / INACTIVE 停用 */
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Version
    private Integer version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
