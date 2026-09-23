package com.example.marketing.activity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 灰度命中判断：白名单直通，否则按 userId 稳定取模放量。
 *
 * <p>同一 userId 在比例不变时命中结果稳定（一致性灰度），避免用户"闪进闪出"。</p>
 *
 * <p><b>未配灰度 = 全量放行</b>，参与资格由活动状态 ONLINE 控制。这条语义是链路 0 的断言，
 * 也是"新建活动默认可参与"的约定，所以"没有规则"与"规则是 0%"必须区分开：前者不落库（列为 NULL），
 * 后者是显式的 0。</p>
 *
 * <p>规则来源是 {@link GrayRuleCache}（真值在 DB 列，每 5s 回源）。yml 里的
 * {@code marketing.gray} 那份已经删掉：它既改不动（全仓零 {@code @RefreshScope}，
 * 改完必须重启）也留不住（Redis 与重启都会让它漂）。</p>
 */
@Service
@RequiredArgsConstructor
public class GrayService {

    private final GrayRuleCache cache;

    /**
     * 判断用户是否可参与活动。
     *
     * @param activityNo 活动编号；未配灰度规则视为全量（由活动状态 ONLINE 控制）
     */
    public boolean hit(String activityNo, Long userId) {
        Optional<GrayRuleCache.Rule> rule = cache.rule(activityNo);
        if (rule.isEmpty()) {
            return true;
        }
        if (userId != null && rule.get().whitelist().contains(userId)) {
            return true;
        }
        long uid = userId == null ? 0L : userId;
        return Math.floorMod(uid, 100L) < rule.get().percent();
    }
}
