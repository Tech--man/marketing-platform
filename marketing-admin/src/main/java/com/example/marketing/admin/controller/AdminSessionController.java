package com.example.marketing.admin.controller;

import com.example.marketing.admin.infrastructure.entity.AdminSessionEntity;
import com.example.marketing.admin.security.AdminRoles;
import com.example.marketing.admin.service.AdminAuthService;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.admin.service.AdminSessionService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.security.AdminClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话面：在线列表与强制下线。
 *
 * <p>{@code mine=true} 让普通后台用户只看自己的会话（改密前自查在哪些设备上登录过），
 * 不带该参数则是全局视图，只有 admin 能取。</p>
 */
@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final AdminSessionService sessionService;
    private final AdminIdentityService identityService;
    private final AdminAuthService authService;

    @GetMapping
    public Result<PageResult<AdminSessionEntity>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "false") boolean mine) {
        // 看别人的会话是管理动作，看自己的只是查自己 —— 两条路径各自只验一次凭证
        AdminClaims claims = mine
                ? identityService.resolve(authorization)
                : identityService.require(authorization, AdminRoles.ADMIN);
        Long userId = mine ? claims.uid() : null;
        return Result.ok(sessionService.listOnline(PageQuery.of(page, size), userId));
    }

    @DeleteMapping("/{jti}")
    public Result<Void> forceLogout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String jti) {
        authService.forceLogout(authorization, jti);
        return Result.ok();
    }
}
