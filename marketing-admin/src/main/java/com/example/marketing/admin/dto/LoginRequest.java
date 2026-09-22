package com.example.marketing.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录请求。口令不做长度下限：下限会把历史弱口令账号变成"改不进去"的死角。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
        @NotBlank(message = "口令不能为空") @Size(max = 64) String password) {
}
