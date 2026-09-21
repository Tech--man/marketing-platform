package com.example.marketing.seckill.infrastructure.entity;

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
 * 秒杀订单（表 marketing_seckill.seckill_order）。
 *
 * <p>unique(activity_no, user_id) 是防重复购买的最终兜底：MQ 重复投递、
 * 用户并发抢购都靠它拦截（消费端捕获 DuplicateKeyException 幂等处理）。</p>
 */
@Data
@TableName("seckill_order")
public class SeckillOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String activityNo;
    private Long userId;
    private Long itemId;
    /** 成交金额（元） */
    private BigDecimal amount;
    /** CREATED / PAID / CANCELLED */
    private String status;
    /** 抢购 token（幂等追踪） */
    private String token;
    /** 命中的库存分桶（超时回补用） */
    private Integer bucket;
    private LocalDateTime payTime;

    @Version
    private Integer version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
