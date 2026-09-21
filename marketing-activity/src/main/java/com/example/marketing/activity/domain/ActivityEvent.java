package com.example.marketing.activity.domain;

/**
 * 活动状态流转事件。
 */
public enum ActivityEvent {

    /** 草稿 → 提审 */
    SUBMIT,
    /** 审核通过 → 灰度 */
    APPROVE,
    /** 审核驳回 → 回草稿 */
    REJECT,
    /** 灰度放量完成 → 全量上线 */
    PROMOTE,
    /** 上线 → 主动下线（预案开关） */
    OFFLINE,
    /** 下线 → 重新上线 */
    RE_ONLINE,
    /** 任意可参与状态 → 结束（不可逆） */
    FINISH
}
