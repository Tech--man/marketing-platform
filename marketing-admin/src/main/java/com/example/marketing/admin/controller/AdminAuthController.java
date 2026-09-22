package com.example.marketing.admin.controller;

import com.example.marketing.admin.dto.ChangePasswordRequest;
import com.example.marketing.admin.dto.LoginRequest;
import com.example.marketing.admin.dto.LoginView;
import com.example.marketing.admin.service.AdminAuthService;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.security.AdminClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 后台认证入口。login 是唯一不需要凭证的端点，由网关的 AdminAuthFilter 放行；
 * 这里对 /auth/login 也刻意不做任何角色判断，避免"登录要先登录"的死锁。
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;
    private final AdminIdentityService identityService;

    @PostMapping("/login")
    public Result<LoginView> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return Result.ok(authService.login(request.username(), request.password(),
                ClientIp.of(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization, "LOGOUT");
        return Result.ok();
    }

    /** 改自己的口令；成功后所有会话（含当前）作废，前端要跳回登录页 */
    @PostMapping("/password")
    public Result<Void> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(authorization, request.oldPassword(), request.newPassword());
        return Result.ok();
    }

    /** 我是谁 + token 还剩多久 —— 前端启动时用它做静默续期判断 */
    @GetMapping("/me")
    public Result<LoginView> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AdminClaims claims = identityService.resolve(authorization);
        long remain = Math.max(0, claims.exp() - Instant.now().getEpochSecond());
        return Result.ok(new LoginView(null, "Bearer", remain, claims.uid(), claims.sub(), null, claims.role()));
    }
}
