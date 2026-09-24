package com.example.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.cache.CacheConsistency;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.exception.VersionGuard;
import com.example.marketing.coupon.dto.StockUpdateRequest;
import com.example.marketing.coupon.dto.StatusUpdateRequest;
import com.example.marketing.coupon.dto.TemplateCreateRequest;
import com.example.marketing.coupon.dto.TemplateView;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券模板管理与库存预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTemplateService implements CacheReheater, CacheConsistency {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

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
     * 新建券模板（③ 净新增能力：今天模板只能靠 init.sql 种子写进库）。
     *
     * <p>必须显式 warm：领券走 Redis 预扣，未预热时 {@code deduct} 返回 NOT_WARMED，
     * 由 {@code CouponGrantService} 补热再重试一次 —— 不预热不会失败，但第一笔领券白跑一趟。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateEntity create(TemplateCreateRequest request) {
        if (templateMapper.selectOne(Wrappers.<CouponTemplateEntity>lambdaQuery()
                .eq(CouponTemplateEntity::getTemplateNo, request.templateNo())) != null) {
            // 唯一键冲突是业务冲突（41000），不是并发覆盖（41008）：
            // 混用的话后台会提示"刷新后重试"，而刷新根本不解决问题——该改编号
            throw new BizException(ErrorCode.BIZ_ERROR, "券模板编号已存在: " + request.templateNo());
        }
        CouponTemplateEntity entity = new CouponTemplateEntity();
        entity.setTemplateNo(request.templateNo());
        entity.setActivityNo(request.activityNo());
        entity.setName(request.name());
        entity.setCouponType(request.couponType());
        entity.setFaceValue(request.faceValue());
        entity.setThresholdAmount(request.thresholdAmount());
        entity.setTotalStock(request.totalStock());
        entity.setPerUserLimit(request.perUserLimit());
        entity.setValidDays(request.validDays());
        entity.setStatus(STATUS_ACTIVE);
        entity.setStartTime(request.startTime());
        entity.setEndTime(request.endTime());
        entity.setVersion(0);
        templateMapper.insert(entity);
        warmStock(entity);
        log.info("[coupon] 新建券模板 {} totalStock={}", entity.getTemplateNo(), entity.getTotalStock());
        return entity;
    }

    /**
     * 后台改总库存。<b>必须走 force 重建</b>：键里存的是剩余量，且 {@code warmStock} 是 SETNX，
     * 不 DEL 就永远生效不了（地雷 A）。余量算的是 {@link #remainOf} 那一份公式，不另算。
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateEntity updateTotalStock(String templateNo, StockUpdateRequest body) {
        CouponTemplateEntity template = getRequiringExists(templateNo);
        VersionGuard.requireEqual(body.version(), template.getVersion(), "券模板");
        long issued = issuedCount(template.getId());
        if (body.totalStock() < issued) {
            // 允许改小，但不能改到已发数以下：remainOf 会把它钳成 0，
            // 于是"改了库存"看起来像"券卖光了"——一个会把人引向错误处置的显示
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "总库存不能小于已发出数（已发 " + issued + "，试图设为 " + body.totalStock() + "）");
        }
        int before = template.getTotalStock();
        template.setTotalStock(body.totalStock());
        if (templateMapper.updateById(template) == 0) {
            throw VersionGuard.conflict("券模板");
        }
        // 走 reheat 而不是自己 DEL+算一遍：公式与路径都只有一份，
        // 且它返回的 before/after 就是"键里到底变成了多少"，日志与 ④ 都能用
        CacheReheater.Result rebuilt = reheat(templateNo, true);
        log.info("[coupon] 库存变更 {} totalStock {} -> {}，{}",
                templateNo, before, template.getTotalStock(), rebuilt.formula());
        return template;
    }

    /**
     * 上下线。<b>只补热不重建</b>：停用时不动键（在途的领券该让它跑完），
     * 重新上线时键可能从没建过 —— 那是 SETNX 的场景，不是 DEL 的场景。
     */
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateEntity updateStatus(String templateNo, StatusUpdateRequest body) {
        if (!STATUS_ACTIVE.equals(body.status()) && !STATUS_INACTIVE.equals(body.status())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "status 只能是 ACTIVE 或 INACTIVE，当前 " + body.status());
        }
        CouponTemplateEntity template = getRequiringExists(templateNo);
        VersionGuard.requireEqual(body.version(), template.getVersion(), "券模板");
        template.setStatus(body.status());
        if (templateMapper.updateById(template) == 0) {
            throw VersionGuard.conflict("券模板");
        }
        if (STATUS_ACTIVE.equals(body.status())) {
            stockService.warmIfAbsent(template, remainOf(template, issuedCount(template.getId())));
        }
        log.info("[coupon] 模板 {} 状态 -> {}", templateNo, body.status());
        return template;
    }

    /** 后台列表：状态可选过滤 */
    public PageResult<TemplateView> list(PageQuery query, String status) {
        Page<CouponTemplateEntity> page = templateMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                Wrappers.<CouponTemplateEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), CouponTemplateEntity::getStatus, status)
                        .orderByAsc(CouponTemplateEntity::getTemplateNo));
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream().map(TemplateView::from).toList());
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

    /** 自检抽样上限：每条两次读（DB 权威余量 + Redis 当前值），所以刻意小 */
    static final int CONSISTENCY_SAMPLE = 5;

    /**
     * 抽样内"Redis 余量 != total_stock - 已发数"的 ACTIVE 模板数。
     * 余量算的是 {@link #remainOf} 那一份公式，不重写第二份。-1 = 判定不了，不是 0。
     */
    @Override
    public int mismatchCount() {
        try {
            List<CouponTemplateEntity> actives = templateMapper.selectList(
                    Wrappers.<CouponTemplateEntity>lambdaQuery()
                            .eq(CouponTemplateEntity::getStatus, STATUS_ACTIVE)
                            .last("LIMIT " + CONSISTENCY_SAMPLE));
            int mismatch = 0;
            for (CouponTemplateEntity template : actives) {
                Long cached = stockService.remainStock(template.getId());
                if (cached == null || cached != remainOf(template, issuedCount(template.getId()))) {
                    mismatch++;
                }
            }
            return mismatch;
        } catch (RuntimeException e) {
            log.warn("[coupon] 一致性自检判定不了: {}", e.toString());
            return -1;
        }
    }
}
