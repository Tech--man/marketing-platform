package com.example.marketing.coupon.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户券实例（表 marketing_coupon.user_coupon）。
 *
 * <p>request_id 唯一索引 = 领券链路幂等兜底（MQ 至少一次投递下防止重复落库）。</p>
 */
@Data
@TableName("user_coupon")
public class UserCouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 券码（业务唯一） */
    private String couponCode;
    private Long templateId;
    private String templateNo;
    private String activityNo;
    private Long userId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    /** 面额快照（模板变更不影响已发券） */
    private BigDecimal faceValue;
    private BigDecimal thresholdAmount;
    private String couponType;
    private LocalDateTime validStart;
    private LocalDateTime expireAt;
    /** 核销订单号 */
    private String orderNo;
    /** 领券请求 ID（幂等键） */
    private String requestId;
    private LocalDateTime grantTime;
    private LocalDateTime useTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
