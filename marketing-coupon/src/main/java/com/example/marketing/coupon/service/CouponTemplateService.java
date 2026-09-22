package com.example.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券模板管理与库存预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTemplateService implements CacheReheater {

    public static final String STATUS_ACTIVE = "ACTIVE";

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponStockService stockService;

    /**
     * 按模板编号查询并校验可领（状态 + 时间窗）。
     */
    public CouponTemplateEntity getRequiringGrantable(String templateNo) {
        CouponTemplateEntity template = templateMapper.selectOne(Wrappers.<CouponTemplateEntity>lambdaQuery()
                .eq(CouponTemplateEntity::getTemplateNo, templateNo));
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "券模板不存在: " + templateNo);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean grantable = STATUS_ACTIVE.equals(template.getStatus())
                && (template.getStartTime() == null || !now.isBefore(template.getStartTime()))
                && (template.getEndTime() == null || now.isBefore(template.getEndTime()));
        if (!grantable) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_ONLINE, "券模板不在可领取状态/时间窗内");
        }
        return template;
    }

    /**
     * 预热模板库存：remain = total_stock - 已发放数（Redis SETNX，幂等）。
     */
    public void warmStock(CouponTemplateEntity template) {
        if (stockService.isWarmed(template.getId())) {
            return;
        }
        stockService.warmIfAbsent(template, remainOf(template, issuedCount(template.getId())));
    }

    /** 重算口径：已发超时归零，绝不把负数写进 Redis */
    static long remainOf(CouponTemplateEntity template, long issued) {
        return Math.max(0L, template.getTotalStock() - issued);
    }

    private long issuedCount(Long templateId) {
        Long issued = userCouponMapper.selectCount(Wrappers.<UserCouponEntity>lambdaQuery()
                .eq(UserCouponEntity::getTemplateId, templateId));
        return issued == null ? 0L : issued;
    }

    /** 权威重算：只读 DB（Redis 此刻的值正是不可信的那个） */
    public long computeRemainByNo(String templateNo) {
        CouponTemplateEntity template = getRequiringExists(templateNo);
        return remainOf(template, issuedCount(template.getId()));
    }

    @Override
    public String type() {
        return "coupon-stock";
    }

    /**
     * 重预热券库存。
     *
     * @param force false = 只补缺（SETNX）；true = DEL 后按 DB 重建 ——
     *              运营改完 total_stock 必须走这条，否则键还在、改动作无用（地雷 A）。
     */
    @Override
    public CacheReheater.Result reheat(String templateNo, boolean force) {
        CouponTemplateEntity template = getRequiringExists(templateNo);
        Long current = stockService.remainStock(template.getId());
        long before = current == null ? -1L : current;
        long target = remainOf(template, issuedCount(template.getId()));
        if (force) {
            stockService.overwrite(template, target);
        } else {
            stockService.warmIfAbsent(template, target);
        }
        Long after = stockService.remainStock(template.getId());
        return new CacheReheater.Result(type(), templateNo, before,
                after == null ? (force ? target : before) : after,
                "total_stock - COUNT(user_coupon WHERE template_id)");
    }

    /** 启动/巡检时对所有 ACTIVE 模板补预热 */
    public void warmAllActive() {
        List<CouponTemplateEntity> actives = templateMapper.selectList(
                Wrappers.<CouponTemplateEntity>lambdaQuery().eq(CouponTemplateEntity::getStatus, STATUS_ACTIVE));
        actives.forEach(this::warmStock);
        log.info("[coupon] 启动预热完成，ACTIVE 模板数={}", actives.size());
    }

    /** 剩余库存查询（Redis 实时口径，未预热回源 DB 计算） */
    public Long remainStock(String templateNo) {
        CouponTemplateEntity template = getRequiringExists(templateNo);
        Long remain = stockService.remainStock(template.getId());
        if (remain == null) {
            warmStock(template);
            remain = stockService.remainStock(template.getId());
        }
        return remain;
    }

    public CouponTemplateEntity getRequiringExists(String templateNo) {
        CouponTemplateEntity template = templateMapper.selectOne(Wrappers.<CouponTemplateEntity>lambdaQuery()
                .eq(CouponTemplateEntity::getTemplateNo, templateNo));
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "券模板不存在: " + templateNo);
        }
        return template;
    }
}
