package com.example.marketing.account.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "登录名不能为空") String identifier,
        @NotBlank(message = "口令不能为空") String password) {
}
