package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.exception.VersionGuard;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.dto.SeckillActivityCreateRequest;
import com.example.marketing.seckill.dto.SeckillActivityView;
import com.example.marketing.seckill.dto.SeckillStatusRequest;
import com.example.marketing.seckill.dto.StockEditRequest;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀活动与库存的后台写路径（③）。
 *
 * <p>分桶的重建一律交给 {@link SeckillWarmUpService#reheat}：那里已有"仅 ONLINE 且未过结束时间
 * 才允许 force 重建"的守卫，本类不复制第二份判定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillAdminService {

    private static final String ONLINE = "ONLINE";
    private static final String OFFLINE = "OFFLINE";

    private final SeckillActivityMapper activityMapper;
    private final SeckillWarmUpService warmUpService;
    private final SeckillRuntimeConfig runtime;

    public PageResult<SeckillActivityView> list(PageQuery query, String status) {
        Page<SeckillActivityEntity> page = activityMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                Wrappers.<SeckillActivityEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), SeckillActivityEntity::getStatus, status)
                        .orderByAsc(SeckillActivityEntity::getActivityNo));
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream().map(SeckillActivityView::from).toList());
    }

    /**
     * 新建活动，一律 OFFLINE 且<b>不预热</b>：价格与库存还没人复核，
     * 把分桶按总量建起来就是先开闸再确认。
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillActivityEntity create(SeckillActivityCreateRequest request) {
        if (activityMapper.selectOne(Wrappers.<SeckillActivityEntity>lambdaQuery()
                .eq(SeckillActivityEntity::getActivityNo, request.activityNo())) != null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "秒杀活动编号已存在: " + request.activityNo());
        }
        SeckillActivityEntity entity = new SeckillActivityEntity();
        entity.setActivityNo(request.activityNo());
        entity.setItemId(request.itemId());
        entity.setItemName(request.itemName());
        entity.setSeckillPrice(request.seckillPrice());
        entity.setTotalStock(request.totalStock());
        entity.setSoldStock(0);
        entity.setBuckets(runtime.buckets());
        entity.setStatus(OFFLINE);
        entity.setStartTime(request.startTime());
        entity.setEndTime(request.endTime());
        entity.setVersion(0);
        activityMapper.insert(entity);
        log.info("[seckill] 新建活动 {} totalStock={} status=OFFLINE（待上线）",
                entity.getActivityNo(), entity.getTotalStock());
        return entity;
    }

    /**
     * 改总库存。<b>只有 ONLINE 才重建分桶</b>：
     * 给停用中的活动 force 重置，等于绕过上下线动作悄悄把闸门打开（已售进度也会被冲掉）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillActivityEntity updateStock(String activityNo, StockEditRequest body) {
        SeckillActivityEntity activity = getRequiringExists(activityNo);
        VersionGuard.requireEqual(body.version(), activity.getVersion(), "秒杀活动");
        int sold = activity.getSoldStock() == null ? 0 : activity.getSoldStock();
        if (body.totalStock() < sold) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "总库存不能小于已售数（已售 " + sold + "，试图设为 " + body.totalStock() + "）");
        }
        int before = activity.getTotalStock();
        activity.setTotalStock(body.totalStock());
        if (activityMapper.updateById(activity) == 0) {
            throw VersionGuard.conflict("秒杀活动");
        }
        if (ONLINE.equals(activity.getStatus())) {
            warmUpService.reheat(activityNo, true);
            log.info("[seckill] 库存变更 {} totalStock {} -> {}，分桶已重建",
                    activityNo, before, activity.getTotalStock());
        } else {
            log.info("[seckill] 库存变更 {} totalStock {} -> {}（{} 状态，不动分桶）",
                    activityNo, before, activity.getTotalStock(), activity.getStatus());
        }
        return activity;
    }

    /**
     * 上下线。上线用 {@code force=false}：只补建缺失的桶，
     * 已经在跑的进度不许被这次动作冲掉；下线不碰桶（在途的抢购该让它跑完）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillActivityEntity updateStatus(String activityNo, SeckillStatusRequest body) {
        if (!ONLINE.equals(body.status()) && !OFFLINE.equals(body.status())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "status 只能是 ONLINE 或 OFFLINE，当前 " + body.status());
        }
        SeckillActivityEntity activity = getRequiringExists(activityNo);
        VersionGuard.requireEqual(body.version(), activity.getVersion(), "秒杀活动");
        activity.setStatus(body.status());
        if (activityMapper.updateById(activity) == 0) {
            throw VersionGuard.conflict("秒杀活动");
        }
        if (ONLINE.equals(body.status())) {
            warmUpService.reheat(activityNo, false);
        }
        log.info("[seckill] 活动 {} 状态 -> {}", activityNo, body.status());
        return activity;
    }

    /** 给 controller 取"改之前"的样子（审计要 from） */
    public SeckillActivityEntity existing(String activityNo) {
        return getRequiringExists(activityNo);
    }

    private SeckillActivityEntity getRequiringExists(String activityNo) {
        SeckillActivityEntity activity = activityMapper.selectOne(Wrappers.<SeckillActivityEntity>lambdaQuery()
                .eq(SeckillActivityEntity::getActivityNo, activityNo));
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "秒杀活动不存在: " + activityNo);
        }
        return activity;
    }
}
