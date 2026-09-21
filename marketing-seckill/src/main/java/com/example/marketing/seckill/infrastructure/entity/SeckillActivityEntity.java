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
 * 秒杀活动（表 marketing_seckill.seckill_activity）。
 *
 * <p>sold_stock 为 DB 侧已售计数（下单成功 +1，回补 -1），与 Redis 分桶余量
 * 由对账定时器校准；version 乐观锁防回补并发写冲突。</p>
 */
@Data
@TableName("seckill_activity")
public class SeckillActivityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 活动编号（业务唯一键） */
    private String activityNo;
    private Long itemId;
    private String itemName;
    /** 秒杀价（元） */
    private BigDecimal seckillPrice;
    private Integer totalStock;
    /** 已售（DB 侧） */
    private Integer soldStock;
    /** 预热时的分桶数（变更需重新预热） */
    private Integer buckets;
    /** ONLINE 可售 / OFFLINE 停用 */
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
