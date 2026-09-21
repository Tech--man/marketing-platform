package com.example.marketing.activity.domain;

/**
 * 活动状态：草稿 → 审核 → 灰度 → 上线 → 下线 → 结束。
 */
public enum ActivityStatus {

    DRAFT,
    AUDITING,
    GRAY,
    ONLINE,
    OFFLINE,
    FINISHED;

    /** 是否处于可参与状态（上线或灰度） */
    public boolean participatable() {
        return this == ONLINE || this == GRAY;
    }
}
