package com.example.marketing.coupon.controller;

import com.example.marketing.common.api.Result;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.coupon.dto.ConsumeRequest;
import com.example.marketing.coupon.dto.GrantRequest;
import com.example.marketing.coupon.dto.GrantResultVO;
import com.example.marketing.coupon.dto.GrantTicket;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.service.CouponConsumeService;
import com.example.marketing.coupon.service.CouponGrantService;
import com.example.marketing.coupon.service.CouponTemplateService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ConsumerRequestIdentity identity;

    /**
     * 领券（异步受理，返回排队凭证）。
     *
     * <p>请求体里的 userId 一律丢弃、换成验过签名的那一个：留着它，脚本只要换一个数字
     * 就能替别人领券并吃掉对方的限领额度。</p>
     */
    @PostMapping("/grant")
    public Result<GrantTicket> grant(@Valid @RequestBody GrantRequest request, HttpServletRequest httpRequest) {
        long uid = identity.require(httpRequest).uid();
        return Result.ok(grantService.grant(
                new GrantRequest(request.requestId(), uid, request.templateNo())));
    }

    /** 领券结果轮询 */
    @GetMapping("/grant/result/{requestId}")
    public Result<GrantResultVO> grantResult(@PathVariable String requestId, HttpServletRequest httpRequest) {
        return Result.ok(grantService.queryResult(requestId, identity.require(httpRequest).uid()));
    }

    /** 核销 */
    @PostMapping("/consume")
    public Result<UserCouponEntity> consume(@Valid @RequestBody ConsumeRequest request,
                                            HttpServletRequest httpRequest) {
        long uid = identity.require(httpRequest).uid();
        return Result.ok(consumeService.consume(
                new ConsumeRequest(request.couponCode(), uid, request.orderNo())));
    }

    /** 用户可用券 */
    @GetMapping("/usable")
    public Result<List<UserCouponEntity>> usable(HttpServletRequest request) {
        return Result.ok(consumeService.listUserUsableCoupons(identity.require(request).uid()));
    }

    /** 模板剩余库存 */
    @GetMapping("/stock/{templateNo}")
    public Result<Long> stock(@PathVariable String templateNo) {
        return Result.ok(templateService.remainStock(templateNo));
    }
}
