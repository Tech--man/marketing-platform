package com.example.marketing.coupon.controller;

import com.example.marketing.common.api.Result;
import com.example.marketing.coupon.dto.ConsumeRequest;
import com.example.marketing.coupon.dto.GrantRequest;
import com.example.marketing.coupon.dto.GrantResultVO;
import com.example.marketing.coupon.dto.GrantTicket;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.service.CouponConsumeService;
import com.example.marketing.coupon.service.CouponGrantService;
import com.example.marketing.coupon.service.CouponTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 券中心接口：领券 / 结果查询 / 核销 / 可用券 / 库存查询。
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponGrantService grantService;
    private final CouponConsumeService consumeService;
    private final CouponTemplateService templateService;

    /** 领券（异步受理，返回排队凭证） */
    @PostMapping("/grant")
    public Result<GrantTicket> grant(@Valid @RequestBody GrantRequest request) {
        return Result.ok(grantService.grant(request));
    }

    /** 领券结果轮询 */
    @GetMapping("/grant/result/{requestId}")
    public Result<GrantResultVO> grantResult(@PathVariable String requestId) {
        return Result.ok(grantService.queryResult(requestId));
    }

    /** 核销 */
    @PostMapping("/consume")
    public Result<UserCouponEntity> consume(@Valid @RequestBody ConsumeRequest request) {
        return Result.ok(consumeService.consume(request));
    }

    /** 用户可用券 */
    @GetMapping("/usable")
    public Result<List<UserCouponEntity>> usable(@RequestParam Long userId) {
        return Result.ok(consumeService.listUserUsableCoupons(userId));
    }

    /** 模板剩余库存 */
    @GetMapping("/stock/{templateNo}")
    public Result<Long> stock(@PathVariable String templateNo) {
        return Result.ok(templateService.remainStock(templateNo));
    }
}
