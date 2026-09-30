package com.example.marketing.admin.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台审计日志。actor_id 允许为空：登录失败时可能根本没有账号。
 */
@Data
@TableName("admin_audit_log")
public class AdminAuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String actorName;
    private String role;
    private String action;
    private String resourceType;
    private String resourceId;
    private String method;
    private String path;
    private String requestSummary;
    private Integer resultCode;
    private String errorMsg;
    private String ip;
    /**
     * 来源 Stream 消息 ID（W1.2 幂等，2026-09-30 第二轮复审）：drain 至少一次投递
     * 的去重键，uk_source 撞键按已落库处理。null = 存量行 / HTTP 直写路径。
     */
    private String sourceId;
    private Long costMs;
    private LocalDateTime createTime;
}
