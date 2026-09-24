package com.example.marketing.admin.controller;

import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.admin.observe.OpsSnapshotService;
import com.example.marketing.admin.observe.OpsSnapshotView;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ④ 的唯一端点：一张只读运维快照。
 *
 * <p>刻意<b>没有</b> {@code /ops/metrics?target=} 这种定点读：加了它就要在请求期再判一次
 * "这个 target 在不在清单里"，那是新增一道 SSRF 面去换一点便利——快照本身已经按白名单过滤过了。
 * 也不写审计：本仓的只读 GET（{@code /audits}、{@code /config}、{@code /users}）都不留痕，
 * 只读面一旦开始写审计，读得越勤的运维反而把自己的审计表灌满。</p>
 *
 * <p>角色：任何已登录的后台角色可读（含 read-only）。④ 的价值恰恰是让"看一眼"这件事
 * 不需要写权限；能力上它只读，权限上也只给读。</p>
 */
@RestController
@RequestMapping("/api/admin/ops")
@RequiredArgsConstructor
public class AdminOpsController {

    private final OpsSnapshotService snapshotService;
    private final AdminIdentityService identityService;

    @GetMapping
    public Result<OpsSnapshotView> snapshot(HttpServletRequest request) {
        identityService.require(request);
        return Result.ok(snapshotService.snapshot());
    }
}
