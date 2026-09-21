package com.example.marketing.discount.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.engine.RuleSnapshot;
import com.example.marketing.discount.infrastructure.entity.PromoRuleEntity;
import com.example.marketing.discount.infrastructure.mapper.PromoRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则三级缓存：Caffeine 风格的本地快照（volatile 单例）→ Redis 版本号 → DB 全量。
 *
 * <p>读路径：本地快照在 {@code snapshotCheckSeconds} 内直接使用（零开销）；
 * 到期后仅比对 Redis 版本号，未变化则续期，变化才回源 DB 重建。
 * 写路径（规则管理接口）调用 {@link #bumpVersion()} 推进版本号，多实例间秒级生效。
 * Redis 不可用时沿用本地快照（降级读旧版本），保证计算链路可用性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCacheManager {

    private final PromoRuleMapper promoRuleMapper;
    private final StringRedisTemplate redisTemplate;
    private final DiscountProperties properties;

    private volatile RuleSnapshot local;
    private volatile long localCheckedAt;

    /** 获取当前生效规则快照（永不返回 null） */
    public RuleSnapshot snapshot() {
        RuleSnapshot cached = local;
        long now = System.currentTimeMillis();
        if (cached != null && now - localCheckedAt < properties.getSnapshotCheckSeconds() * 1000L) {
            return cached;
        }
        long redisVersion = readRedisVersion();
        if (cached != null && (redisVersion < 0 || cached.getVersion() == redisVersion)) {
            // 版本未变 或 Redis 不可用（降级用旧快照）
            localCheckedAt = now;
            return cached;
        }
        return rebuild(redisVersion < 0 ? 0 : redisVersion);
    }

    /** 规则变更后推进版本号（时间戳），并让本实例立即失效 */
    public long bumpVersion() {
        long version = System.currentTimeMillis();
        try {
            redisTemplate.opsForValue().set(properties.getVersionKey(), String.valueOf(version));
        } catch (Exception e) {
            log.warn("[discount] 版本号写 Redis 失败（本实例仍生效，其他实例延迟感知）: {}", e.getMessage());
        }
        localCheckedAt = 0;
        return version;
    }

    /** 返回 Redis 当前版本号；key 不存在返回 0；异常返回 -1 */
    private long readRedisVersion() {
        try {
            String value = redisTemplate.opsForValue().get(properties.getVersionKey());
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("[discount] 读版本号失败，降级使用本地快照: {}", e.getMessage());
            return -1L;
        }
    }

    /** 回源 DB 重建快照（synchronized 防并发重建风暴） */
    private synchronized RuleSnapshot rebuild(long version) {
        // double check：等锁期间可能已被其他线程重建；version 可为 0（Redis 无版本 key）
        if (local != null && local.getVersion() == version) {
            localCheckedAt = System.currentTimeMillis();
            return local;
        }
        List<PromoRuleEntity> entities = promoRuleMapper.selectList(
                new LambdaQueryWrapper<PromoRuleEntity>().eq(PromoRuleEntity::getStatus, "ENABLED")
                        .orderByAsc(PromoRuleEntity::getRuleNo));
        List<PromoRuleDsl> dslList = new ArrayList<>(entities.size());
        for (PromoRuleEntity entity : entities) {
            try {
                PromoRuleDsl dsl = JsonUtils.parse(entity.getRuleJson(), PromoRuleDsl.class);
                // 冗余列兜底：DSL 缺失字段以表列为准
                if (dsl.getRuleNo() == null) {
                    dsl.setRuleNo(entity.getRuleNo());
                }
                dslList.add(dsl);
            } catch (Exception e) {
                log.error("[discount] 规则 DSL 解析失败已跳过, ruleNo={}", entity.getRuleNo(), e);
            }
        }
        // 稳定排序保证多实例 ordinal 一致
        dslList.sort(Comparator.comparing(PromoRuleDsl::getRuleNo));
        RuleSnapshot snapshot = RuleSnapshot.build(version, dslList);
        this.local = snapshot;
        this.localCheckedAt = System.currentTimeMillis();
        log.info("[discount] 规则快照重建完成 version={}, rules={}", version, snapshot.getRules().size());
        return snapshot;
    }
}
