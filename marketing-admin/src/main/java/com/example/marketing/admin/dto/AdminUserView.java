package com.example.marketing.admin.dto;

import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 账号视图：刻意不含 password_hash —— 一旦把它交给 JSON 序列化，
 * 后台列一次表就等于把全库口令散贴到运营屏幕上。
 */
@Builder
public record AdminUserView(
        Long id,
        String username,
        String displayName,
        String role,
        String status,
        boolean locked,
        LocalDateTime lastLoginTime,
        LocalDateTime createTime) {

    public static AdminUserView of(AdminUserEntity entity, boolean locked) {
        return AdminUserView.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .displayName(entity.getDisplayName())
                .role(entity.getRole())
                .status(entity.getStatus())
                .locked(locked)
                .lastLoginTime(entity.getLastLoginTime())
                .createTime(entity.getCreateTime())
                .build();
    }
}
