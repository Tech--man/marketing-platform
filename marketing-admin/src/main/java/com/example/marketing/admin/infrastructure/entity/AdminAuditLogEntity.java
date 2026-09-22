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
    private Long costMs;
    private LocalDateTime createTime;
}
