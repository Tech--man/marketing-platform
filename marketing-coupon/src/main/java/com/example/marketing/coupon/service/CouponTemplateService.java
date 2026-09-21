package com.example.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
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
public class CouponTemplateService {

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
            throw new BizException(ErrorCode.BIZ_ERROR, "券模板不存在: " + templateNo);
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
        long issued = userCouponMapper.selectCount(Wrappers.<UserCouponEntity>lambdaQuery()
                .eq(UserCouponEntity::getTemplateId, template.getId()));
        long remain = Math.max(0, template.getTotalStock() - issued);
        stockService.warmIfAbsent(template, remain);
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
            throw new BizException(ErrorCode.BIZ_ERROR, "券模板不存在: " + templateNo);
        }
        return template;
    }
}
