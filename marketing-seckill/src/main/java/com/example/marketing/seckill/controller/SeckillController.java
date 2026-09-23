package com.example.marketing.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.dto.GrabRequest;
import com.example.marketing.seckill.dto.GrabTicket;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.service.SeckillGrabService;
import com.example.marketing.seckill.service.SeckillOrderService;
import com.example.marketing.seckill.service.SeckillStockService;
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

    /** 抢购：占名额成功即返回排队 token，订单结果异步产生 */
    @PostMapping("/grab")
    public Result<GrabTicket> grab(@RequestBody @Valid GrabRequest request) {
        return Result.ok(grabService.grab(request.getActivityNo(), request.getUserId()));
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

    /** 模拟支付回调（生产由支付网关异步通知触发） */
    @PostMapping("/pay/{orderNo}")
    public Result<SeckillOrderEntity> pay(@PathVariable String orderNo) {
        return Result.ok(orderService.pay(orderNo));
    }
}
