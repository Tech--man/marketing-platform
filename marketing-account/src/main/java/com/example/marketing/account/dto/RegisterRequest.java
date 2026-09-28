package com.example.marketing.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。identifier 用"字符集受限"而不是"必须是手机号/邮箱"的正则：
 * 接不接短信渠道是产品决策，把它写进校验就会变成将来加一种登录方式要改后端。
 */
public record RegisterRequest(
        @NotBlank(message = "登录名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_.@-]{4,64}$", message = "登录名需为 4-64 位字母、数字或 _ . @ -")
        String identifier,

        @NotBlank(message = "口令不能为空")
        @Size(min = 8, max = 64, message = "口令长度需在 8-64 位之间")
        String password,

        @Size(max = 32, message = "昵称不超过 32 字")
        String nickname) {
}
