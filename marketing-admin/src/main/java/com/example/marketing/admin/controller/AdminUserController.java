package com.example.marketing.admin.controller;

import com.example.marketing.admin.dto.AdminUserView;
import com.example.marketing.admin.security.AdminRoles;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.admin.service.AdminUserService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号面。列表任何后台角色可读（含 read-only），启停只有 admin ——
 * 停用是"让别人登不上"的动作，operator 与 read-only 都不该有。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService userService;
    private final AdminIdentityService identityService;

    @GetMapping
    public Result<PageResult<AdminUserView>> page(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword) {
        identityService.require(authorization);
        return Result.ok(userService.page(PageQuery.of(page, size), keyword));
    }

    @GetMapping("/{id}")
    public Result<AdminUserView> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable long id) {
        identityService.require(authorization);
        return Result.ok(userService.get(id));
    }

    @PutMapping("/{id}/status")
    public Result<AdminUserView> setStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id,
            @RequestParam String status) {
        identityService.require(authorization, AdminRoles.ADMIN);
        return Result.ok(userService.setStatus(id, status));
    }
}
