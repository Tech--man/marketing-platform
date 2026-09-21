package com.example.marketing.activity.service;

import com.example.marketing.activity.config.GrayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 灰度命中判断：白名单直通，否则按 userId 稳定取模放量。
 *
 * <p>同一 userId 在比例不变时命中结果稳定（一致性灰度），避免用户"闪进闪出"。</p>
 */
@Service
@RequiredArgsConstructor
public class GrayService {

    private final GrayProperties grayProperties;

    /**
     * 判断用户是否可参与活动。
     *
     * @param activityNo 活动编号；未配置灰度规则视为全量（由活动状态 ONLINE 控制）
     */
    public boolean hit(String activityNo, Long userId) {
        GrayProperties.GrayRule rule = grayProperties.getGray().get(activityNo);
        if (rule == null) {
            return true;
        }
        if (userId != null && rule.getWhitelist().contains(userId)) {
            return true;
        }
        long uid = userId == null ? 0L : userId;
        return Math.floorMod(uid, 100L) < rule.getPercent();
    }
}
