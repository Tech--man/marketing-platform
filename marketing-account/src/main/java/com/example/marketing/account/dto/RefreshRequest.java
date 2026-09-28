package com.example.marketing.account.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank(message = "refresh token 不能为空") String refreshToken) {
}
