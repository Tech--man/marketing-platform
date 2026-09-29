package com.example.marketing.activity.controller;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.activity.service.BudgetDeductGuard;
import com.example.marketing.activity.service.BudgetService;
import com.example.marketing.activity.service.GrayService;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动中心对外接口（经网关 /api/activity/** 转发）。
 */
@Validated
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final BudgetService budgetService;
    private final GrayService grayService;
    private final BudgetDeductGuard budgetDeductGuard;
    private final ConsumerRequestIdentity identity;

    /** 查询活动 */
    @GetMapping("/{activityNo}")
    public Result<ActivityEntity> get(@PathVariable String activityNo) {
        return Result.ok(activityService.getByNo(activityNo));
    }

    // ③：创建与状态流转已搬到 /api/admin/activities（长在 owning 进程上）。
    // 这里刻意**不保留转发式别名**：两个入口都能改预算，就是地雷 A 的成因。

    /** 活动是否可参与（下游校验位点） */
    @GetMapping("/{activityNo}/participatable")
    public Result<Boolean> participatable(@PathVariable String activityNo) {
        return Result.ok(activityService.participatable(activityNo));
    }

    /**
     * 灰度命中判断。userId 来自验过签名的 token，不再是查询参数 ——
     * 灰度桶决定这个人能不能进活动，让它由调用方自报就等于把分流开关交给调用方。
     */
    @GetMapping("/{activityNo}/gray-hit")
    public Result<Boolean> grayHit(@PathVariable String activityNo, HttpServletRequest request) {
        return Result.ok(grayService.hit(activityNo, identity.require(request).uid()));
    }

    /**
     * 扣减预算（幂等键作用域 = 活动 + bizKey；data 区分真扣 DEDUCTED 与重复回放 REPLAYED）。
     *
     * <p>H10（2026-09-29 架构审查收口）：必须登录（uid 参与限频），且过
     * {@link BudgetDeductGuard} 的单笔金额与每用户频次两道闸——bizKey 由调用方自报，
     * 换键即真扣，没有这两道闸时任何一个登录用户都能把活动预算逐笔抽干。
     * 金额硬闸（余额不足）仍在 BudgetService，这里拦的是滥用不是余额。</p>
     */
    @PostMapping("/{activityNo}/budget/deduct")
    public Result<BudgetService.DeductOutcome> deductBudget(@PathVariable String activityNo,
                                                            HttpServletRequest httpRequest,
                                                            @Valid @RequestBody DeductRequest request) {
        long uid = identity.require(httpRequest).uid();
        budgetDeductGuard.checkAmount(request.amountCents());
        budgetDeductGuard.checkRate(activityNo, uid);
        return Result.ok(budgetService.deduct(activityNo, request.amountCents(), request.bizKey()));
    }

    /** 查询剩余预算（分） */
    @GetMapping("/{activityNo}/budget/remain")
    public Result<Long> remainBudget(@PathVariable String activityNo) {
        return Result.ok(budgetService.remainCents(activityNo));
    }

    /** 预算扣减请求体 */
    public record DeductRequest(@NotNull(message = "amountCents 必填") Long amountCents,
                                @NotBlank(message = "bizKey 必填") String bizKey) {
    }
}
