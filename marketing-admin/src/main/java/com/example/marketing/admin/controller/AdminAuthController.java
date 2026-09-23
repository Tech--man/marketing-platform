package com.example.marketing.admin.controller;

import com.example.marketing.admin.dto.ChangePasswordRequest;
import com.example.marketing.admin.dto.LoginRequest;
import com.example.marketing.admin.dto.LoginView;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.admin.service.AdminAuthService;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(identityService.resolve(request), "LOGOUT");
        return Result.ok();
    }

    /** 改自己的口令；成功后所有会话（含当前）作废，前端要跳回登录页 */
    @PostMapping("/password")
    public Result<Void> changePassword(HttpServletRequest request,
                                       @Valid @RequestBody ChangePasswordRequest body) {
        AdminPrincipal actor = identityService.resolve(request);
        authService.changePassword(actor, body.oldPassword(), body.newPassword());
        return Result.ok();
    }

    /** 我是谁 —— 前端启动时用它判断"这枚 token 还能不能用"，四header 里不含过期时刻，故不返回剩余寿命 */
    @GetMapping("/me")
    public Result<AdminPrincipal> me(HttpServletRequest request) {
        return Result.ok(identityService.resolve(request));
    }
}
