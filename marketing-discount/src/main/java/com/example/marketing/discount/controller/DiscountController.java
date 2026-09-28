package com.example.marketing.discount.controller;

import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.service.DiscountCalcService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠引擎的 C 端接口：只剩购物车计算。
 *
 * <p>③ 之后这里不再有规则管理（见下面的注释与 {@link DiscountAdminController}）：
 * C 端只需算价，规则是配置类读写。
 */
@RestController
@RequestMapping("/api/discount")
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountCalcService calcService;
    private final ConsumerRequestIdentity identity;

    /** 优惠计算：输入购物车，输出命中规则 + 分摊明细；超时自动降级为原价 */
    @PostMapping("/calculate")
    public Result<CalcResult> calculate(@RequestBody @Valid CalcInput input,
                                        HttpServletRequest httpRequest) {
        if (input.getItems() == null || input.getItems().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "购物车不能为空");
        }
        // userId 在 CalcInput 上是 @JsonIgnore：反序列化根本不收，这里填进去的
        // 只能是验过签名的那一个。命中的门槛规则、每人限领、会员等级都按它算，
        // 让它由调用方自报等于让调用方挑一套对自己最便宜的规则。
        input.setUserId(identity.require(httpRequest).uid());
        return Result.ok(calcService.calculate(input));
    }

    // ③：规则的读写已搬到 /api/admin/discount/rules（owning 进程发布）。
    // 刻意不保留 C 端别名：GET 那条原本把整套规则 DSL（门槛、互斥组、每人限领）
    // 交给共享 demo token 的任何人，注释里写着"管理端"却挂在 C 路径上。
}
