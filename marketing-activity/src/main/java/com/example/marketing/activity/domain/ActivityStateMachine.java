package com.example.marketing.activity.domain;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;

import java.util.Map;

/**
 * 活动状态机：声明式定义合法流转，非法流转直接拒绝。
 *
 * <pre>
 * DRAFT --SUBMIT--> AUDITING --APPROVE--> GRAY --PROMOTE--> ONLINE --OFFLINE--> OFFLINE
 *   ^                  |                    |               ^ ^     |         |
 *   +-----REJECT-------+                    |   GRAY 紧急下线 | |     +--RE_ONLINE+
 *                                          +---OFFLINE------&gt;+-+-+               |
 * ONLINE / GRAY / OFFLINE --FINISH--> FINISHED（终态）&lt;---------------------------+
 * </pre>
 *
 * <p>GRAY 可 OFFLINE（P2，2026-09-30 第二轮复审）：OFFLINE 是"预案开关"，灰度放量中
 * 发现资损缺陷时最需要立即下线的恰恰是 GRAY 阶段——原矩阵只给 GRAY 留了
 * PROMOTE/FINISH（FINISH 不可逆、丢掉 RE_ONLINE 退路；PROMOTE 反而扩大放量），
 * 紧急刹车在最需要它的时刻缺席。</p>
 */
public final class ActivityStateMachine {

    private static final Map<ActivityStatus, Map<ActivityEvent, ActivityStatus>> TRANSITIONS = Map.of(
            ActivityStatus.DRAFT, Map.of(ActivityEvent.SUBMIT, ActivityStatus.AUDITING),
            ActivityStatus.AUDITING, Map.of(
                    ActivityEvent.APPROVE, ActivityStatus.GRAY,
                    ActivityEvent.REJECT, ActivityStatus.DRAFT),
            ActivityStatus.GRAY, Map.of(
                    ActivityEvent.PROMOTE, ActivityStatus.ONLINE,
                    ActivityEvent.OFFLINE, ActivityStatus.OFFLINE,
                    ActivityEvent.FINISH, ActivityStatus.FINISHED),
            ActivityStatus.ONLINE, Map.of(
                    ActivityEvent.OFFLINE, ActivityStatus.OFFLINE,
                    ActivityEvent.FINISH, ActivityStatus.FINISHED),
            ActivityStatus.OFFLINE, Map.of(
                    ActivityEvent.RE_ONLINE, ActivityStatus.ONLINE,
                    ActivityEvent.FINISH, ActivityStatus.FINISHED),
            ActivityStatus.FINISHED, Map.of()
    );

    private ActivityStateMachine() {
    }

    /**
     * 计算下一状态，非法流转抛业务异常。
     */
    public static ActivityStatus next(ActivityStatus current, ActivityEvent event) {
        ActivityStatus target = TRANSITIONS.getOrDefault(current, Map.of()).get(event);
        if (target == null) {
            throw new BizException(ErrorCode.STATE_INVALID_TRANSITION,
                    "活动状态 " + current + " 不允许执行事件 " + event);
        }
        return target;
    }
}
