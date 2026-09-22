package com.example.marketing.admin.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台账号。表见 docker/mysql/init/01-schema.sql 的 admin_user。
 *
 * <p>{@code pwdVersion} 是"改密即踢全部会话"的支点：签发 token 时把它写进 claims，
 * 网关每次校验都和这一列比一次，旧 token 自然失效，不需要遍历会话表。</p>
 */
@Data
@TableName("admin_user")
public class AdminUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    /** admin / operator / read-only */
    private String role;
    /** ACTIVE / DISABLED */
    private String status;
    private int pwdVersion;
    private int failCount;
    private LocalDateTime lockUntil;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
