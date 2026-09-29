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
    /**
     * 是否有效单：新建默认 1（依赖列默认值，insert 不显式带），超时取消置 0。
     * H7（2026-09-29 架构审查）：唯一索引 uk_activity_user 带 active，只约束「每人
     * 每活动一张<b>有效</b>单」——取消过的用户可以重新抢。原索引不含 active 时，
     * 「取消后允许重抢（refill 删防重标记）」与「一人一单兜底」互相矛盾：重抢的
     * insert 必撞旧 CANCELLED 行，幂等回放把已取消单号当 SUCCESS 写回，用户拿到
     * 一个永远付不了款的单号，且本次重扣的名额无主（账实恒等式被破坏）。
     */
    private Integer active;
    private LocalDateTime payTime;

    @Version
    private Integer version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
