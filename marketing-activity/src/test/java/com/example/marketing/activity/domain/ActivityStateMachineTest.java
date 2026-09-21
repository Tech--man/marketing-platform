package com.example.marketing.activity.domain;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动状态机流转规则验证。
 */
class ActivityStateMachineTest {

    @Test
    @DisplayName("完整生命周期：草稿 → 提审 → 过审灰度 → 全量上线 → 下线 → 结束")
    void happyPath() {
        ActivityStatus s = ActivityStatus.DRAFT;
        s = ActivityStateMachine.next(s, ActivityEvent.SUBMIT);
        assertEquals(ActivityStatus.AUDITING, s);
        s = ActivityStateMachine.next(s, ActivityEvent.APPROVE);
        assertEquals(ActivityStatus.GRAY, s);
        s = ActivityStateMachine.next(s, ActivityEvent.PROMOTE);
        assertEquals(ActivityStatus.ONLINE, s);
        s = ActivityStateMachine.next(s, ActivityEvent.OFFLINE);
        assertEquals(ActivityStatus.OFFLINE, s);
        s = ActivityStateMachine.next(s, ActivityEvent.FINISH);
        assertEquals(ActivityStatus.FINISHED, s);
    }

    @Test
    @DisplayName("审核驳回回到草稿，下线可重新上线")
    void rejectAndReOnline() {
        assertEquals(ActivityStatus.DRAFT,
                ActivityStateMachine.next(ActivityStatus.AUDITING, ActivityEvent.REJECT));
        assertEquals(ActivityStatus.ONLINE,
                ActivityStateMachine.next(ActivityStatus.OFFLINE, ActivityEvent.RE_ONLINE));
    }

    @Test
    @DisplayName("非法流转抛 STATE_INVALID_TRANSITION")
    void illegalTransitionsRejected() {
        // 草稿不能直接上线
        BizException e = assertThrows(BizException.class,
                () -> ActivityStateMachine.next(ActivityStatus.DRAFT, ActivityEvent.PROMOTE));
        assertEquals(ErrorCode.STATE_INVALID_TRANSITION.getCode(), e.getCode());
        // 终态不可再流转
        assertThrows(BizException.class,
                () -> ActivityStateMachine.next(ActivityStatus.FINISHED, ActivityEvent.RE_ONLINE));
        // 上线状态不允许重复提审
        assertThrows(BizException.class,
                () -> ActivityStateMachine.next(ActivityStatus.ONLINE, ActivityEvent.SUBMIT));
    }

    @Test
    @DisplayName("GRAY / ONLINE 可参与，其余状态不可参与")
    void participatableStates() {
        assertTrue(ActivityStatus.GRAY.participatable());
        assertTrue(ActivityStatus.ONLINE.participatable());
        assertFalse(ActivityStatus.DRAFT.participatable());
        assertFalse(ActivityStatus.FINISHED.participatable());
    }
}
