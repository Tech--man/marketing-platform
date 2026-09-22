package com.example.marketing.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改自己的口令。必须带旧口令：否则一次 token 泄露就从"读接口"升级成"永久接管账号"。
 * 新口令给 8 位下限是后台侧的最低门槛，账号创建/重置策略留给后续段。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "旧口令不能为空") @Size(max = 64) String oldPassword,
        @NotBlank(message = "新口令不能为空") @Size(min = 8, max = 64) String newPassword) {
}
