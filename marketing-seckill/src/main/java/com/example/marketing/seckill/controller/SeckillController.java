package com.example.marketing.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.dto.GrabRequest;
import com.example.marketing.seckill.dto.GrabTicket;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.service.SeckillGrabService;
import com.example.marketing.seckill.service.SeckillOrderService;
import com.example.marketing.seckill.service.SeckillStockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 秒杀对外接口：抢购 / 结果轮询 / 活动查询 / 模拟支付回调 / 库存余量。
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillGrabService grabService;
    private final SeckillOrderService orderService;
    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final SeckillRuntimeConfig runtimeConfig;
    private final ConsumerRequestIdentity identity;

    /** 抢购：占名额成功即返回排队 token，订单结果异步产生 */
    @PostMapping("/grab")
    public Result<GrabTicket> grab(@RequestBody @Valid GrabRequest request, HttpServletRequest httpRequest) {
        // 身份只从验过签名的 token 取。请求体里那个 userId 字段已经删掉了：
        // 留着它，任何人改一个数字就能替别人占秒杀名额
        return Result.ok(grabService.grab(request.getActivityNo(), identity.require(httpRequest).uid()));
    }

    /** 轮询抢购结果：ACCEPTED / SUCCESS:{orderNo} / FAIL:{reason} / NOT_FOUND */
    @GetMapping("/grab/result/{token}")
    public Result<Map<String, String>> result(@PathVariable String token) {
        String value = grabService.queryResult(token);
        return Result.ok(Map.of("result", value == null ? "NOT_FOUND" : value));
    }

    /** 在线秒杀活动列表 */
    @GetMapping("/activities")
    public Result<List<SeckillActivityEntity>> activities() {
        return Result.ok(activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getStatus, "ONLINE")));
    }

    /** 分桶实时余量（对账/演示用） */
    @GetMapping("/stock/{activityNo}")
    public Result<List<Long>> stock(@PathVariable String activityNo) {
        SeckillActivityEntity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getActivityNo, activityNo));
        if (activity == null) {
            // ①② spec §10 的漏网：不存在返回空数组，调用方分不清"没这个活动"与"活动在售但售罄"
            throw new BizException(ErrorCode.NOT_FOUND, "秒杀活动不存在: " + activityNo);
        }
        int buckets = activity.getBuckets() == null ? runtimeConfig.buckets() : activity.getBuckets();
        return Result.ok(stockService.currentBucketStocks(activityNo, buckets));
    }

    /**
     * 模拟支付回调（生产由支付网关异步通知触发）。
     *
     * <p>带本人校验：这条路径的语义是"把一张 CREATED 的订单标成已付款"，
     * 只按 orderNo 认单的话，任何人猜到单号就能替别人把订单置成已付 ——
     * 而单号在同一活动下是连续生成的，猜得到。</p>
     */
    @PostMapping("/pay/{orderNo}")
    public Result<SeckillOrderEntity> pay(@PathVariable String orderNo, HttpServletRequest request) {
        return Result.ok(orderService.pay(orderNo, identity.require(request).uid()));
    }
}
