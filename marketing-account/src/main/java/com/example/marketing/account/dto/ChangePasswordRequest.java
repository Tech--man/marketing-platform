package com.example.marketing.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "原口令不能为空") String oldPassword,
        @NotBlank(message = "新口令不能为空")
        @Size(min = 8, max = 64, message = "口令长度需在 8-64 位之间") String newPassword) {
}
