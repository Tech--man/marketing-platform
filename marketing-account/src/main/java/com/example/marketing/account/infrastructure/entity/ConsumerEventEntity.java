package com.example.marketing.account.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费者身份事件流水（注册/登录/刷新/登出/改密/吊销）。
 *
 * <p><b>为什么不并进 admin_audit_log</b>：那条跨进程总线 {@code MAXLEN=100000} 且
 * <b>满则丢最旧</b>（StreamKeys 的注释自己写明了这是有意的取舍），它是按"人肉点出来的
 * 后台写入"量级设计的；登录事件是机器量级，灌进去会把更早的后台审计静默挤掉 ——
 * 正是本仓"宁可回源也不静默丢数据"要拒绝的那类事。所以另起一条流 + 另起一张表。</p>
 *
 * <p>不含任何口令明文或 token 明文；{@code detail} 只放标识符与原因。</p>
 */
@Data
@TableName("consumer_event_log")
public class ConsumerEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** REGISTER / LOGIN / LOGIN_FAILED / REFRESH / REFRESH_REUSED / LOGOUT / PASSWORD_CHANGED / REVOKE_ALL */
    private String action;
    /** 可为空：注册失败/账号不存在时没有 uid */
    private Long userId;
    private String identifier;
    private String jti;
    private String ip;
    private String userAgent;
    /** SUCCESS / 失败原因（BAD_CREDENTIALS / LOCKED / DISABLED / …） */
    private String result;
    private LocalDateTime createTime;
}
