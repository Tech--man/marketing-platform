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

    /**
     * 规则变更后推进版本号，并让本实例立即失效。
     *
     * <p><b>H8（2026-09-29 架构审查）三处收口</b>：
     * 1) 版本号从毫秒时间戳换成 Redis {@code INCR} 单调序号——时间戳同毫秒撞号会让
     *    两次变更共用一个版本号，后一次永久丢失（admin 配置中心为同款问题改 INCR，
     *    见 {@code mkt:cfg:seq} 的教训，规则侧此前没跟上）；
     * 2) 只允许从事务提交后调用（{@code RuleAdminService} 用 afterCommit 注册）——
     *    提交前 bump，其他实例在窗口内重建会拿到"新版本号 + 旧数据"的快照，
     *    之后版本比对恒相等、永不重建；
     * 3) bump 写失败时本实例立即按 DB 重建——此时数据必已提交（afterCommit 语义），
     *    旧实现只打日志宣称"本实例仍生效"，实际 readRedisVersion 失败返回 -1，
     *    连写入实例自己也继续用旧快照，永不自愈。</p>
     */
    public long bumpVersion() {
        localCheckedAt = 0;
        try {
            Long version = redisTemplate.opsForValue().increment(properties.getVersionKey());
            return version == null ? 0L : version;
        } catch (Exception e) {
            log.warn("[discount] 版本号写 Redis 失败（本实例已按 DB 重建，其他实例等下次成功的 bump）: {}",
                    e.getMessage());
            forceRebuildFromDb();
            return local == null ? 0L : local.getVersion();
        }
    }

    /** 不看版本号、直接按 DB 当前已提交数据重建本地快照（bump 写失败的自愈路径） */
    private synchronized void forceRebuildFromDb() {
        long current = readRedisVersion();
        if (current < 0) {
            current = local == null ? 0L : local.getVersion();
        }
        // 置空绕过 rebuild 的版本 double-check——这条路径要的就是"无视版本号重建"。
        // 与并发读者的竞态无害：它们看到 null 会进 synchronized 的 rebuild 等锁。
        local = null;
        rebuild(current);
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
        // H8 双检：构建期间版本号又推进了（连续保存），标记让下一次 snapshot 立即重比，
        // 杜绝"构建开始后才提交的数据"被版本相等掩护在快照外
        if (readRedisVersion() > version) {
            localCheckedAt = 0;
        }
        log.info("[discount] 规则快照重建完成 version={}, rules={}", version, snapshot.getRules().size());
        return snapshot;
    }
}
