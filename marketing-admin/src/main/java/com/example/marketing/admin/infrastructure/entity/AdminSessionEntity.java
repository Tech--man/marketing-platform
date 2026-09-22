package com.example.marketing.admin.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台会话。以本表为准，Redis 里的 admin:session:{jti} 只是在线列表快路径。
 */
@Data
@TableName("admin_session")
public class AdminSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jti;
    private Long userId;
    private String username;
    private String loginIp;
    private String userAgent;
    private LocalDateTime expireAt;
    /** PASSWORD_CHANGED / DISABLED / FORCE_LOGOUT / LOGOUT，null = 仍有效 */
    private String revokeReason;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
}
