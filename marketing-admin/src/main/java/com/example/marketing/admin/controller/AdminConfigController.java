package com.example.marketing.admin.controller;

import com.example.marketing.common.web.ClientIp;
import com.example.marketing.admin.config.AdminConfigService;
import com.example.marketing.admin.dto.ConfigEntryView;
import com.example.marketing.admin.dto.ConfigOverviewView;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线配置面。
 *
 * <p>读任何后台角色都行；<b>写只有 admin</b>。operator 在网关那一层属于"可写运维角色"
 * （{@code AdminRoles.OPERATIONAL}，重预热/踢人归它），但阈值不是运维动作而是业务口径——
 * 缺了这层细筛，operator 就能在双十一当天改限流。</p>
 */
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final AdminConfigService configService;
    private final AdminIdentityService identityService;

    @GetMapping
    public Result<ConfigOverviewView> overview(HttpServletRequest request) {
        identityService.require(request);
        return Result.ok(configService.overview());
    }

    @PutMapping
    public Result<ConfigEntryView> set(HttpServletRequest request, @RequestBody ConfigSetRequest body) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        return Result.ok(configService.set(actor, body, ClientIp.of(request)));
    }

    @DeleteMapping
    public Result<Void> delete(HttpServletRequest request,
                               @RequestParam String cfgKey,
                               @RequestParam String form) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        configService.delete(actor, cfgKey, form, ClientIp.of(request));
        return Result.ok();
    }

    @PostMapping("/rebroadcast")
    public Result<Long> rebroadcast(HttpServletRequest request) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        return Result.ok(configService.rebroadcast(actor, ClientIp.of(request)));
    }
}
