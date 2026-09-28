package com.example.marketing.account.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费者账号。表见 docker/mysql/init/01-schema.sql 的 consumer_user。
 *
 * <p>{@code user_coupon.user_id} 等列今天还是"调用方随手填的整数"，这张表就是让那些列
 * 第一次有了指向的对象：一人一单的唯一键、单人限领、灰度分桶，全都要等身份可信之后才成立。</p>
 *
 * <p>没有 role 列：消费者只有"是不是本人"这一种判定。{@code nickname} 与
 * {@code avatarKey} 是展示字段，不参与任何鉴权判定。</p>
 */
@Data
@TableName("consumer_user")
public class ConsumerUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 登录名；将来接手机号只是换一个值域，identifier 这个名字不绑死渠道 */
    private String identifier;
    private String passwordHash;
    private String nickname;
    /** ACTIVE / DISABLED。停用不抹行：券与订单要能追到人 */
    private String status;
    /** 改密即 +1，配合 consumer:bump:{uid} 作废全部会话 */
    private int pwdVersion;
    private int failCount;
    private LocalDateTime lockUntil;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
