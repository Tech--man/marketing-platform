package com.example.marketing.coupon.service;

import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5-1 回归（2026-09-29 审查第五批）：活动状态与灰度在领券服务端强制。
 *
 * <p>原先这层只拦 H5 入口（前端调 participatable/gray-hit 决定是否展示按钮），
 * 直接 POST /api/coupon/grant 完全绕过——活动下线后券照发、灰度对任何登录用户无效。
 * 判定公式必须与 activity 侧 GrayService.hit 逐字一致（floorMod(uid,100)&lt;percent），
 * 两处漂移等于两种人看到两个活动。</p>
 */
class ActivityGateTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ActivityGate gate = new ActivityGate(redis);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private void status(String value) {
        when(ops.get("activity:gate:status:ACT1")).thenReturn(value);
    }

    private void gray(String value) {
        when(ops.get("activity:gate:gray:ACT1")).thenReturn(value);
    }

    @Test
    @DisplayName("活动 OFFLINE → 41007 拒绝：预案下线必须在服务端停发券，不只是收起入口")
    void offlineActivityRejected() {
        status("OFFLINE");

        BizException e = assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70001L));
        assertEquals(41007, e.getCode());
    }

    @Test
    @DisplayName("状态键缺失 → 放行（fail-open：activity 侧未部署/未发布时行为与旧版一致）")
    void missingKeysFailOpen() {
        status(null);
        gray(null);

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));
    }

    @Test
    @DisplayName("ONLINE + 未配灰度（percent=-）→ 全量放行")
    void onlineWithoutGrayPasses() {
        status("ONLINE");
        gray("-|");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));
    }

    @Test
    @DisplayName("灰度 40%：uid%100<40 命中、≥40 拒——公式与 GrayService.hit 逐字一致")
    void grayPercentMathMatchesActivitySide() {
        status("ONLINE");
        gray("40|");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 100L));       // 100%100=0 < 40 命中
        BizException e = assertThrows(BizException.class,
                () -> gate.checkGrantable("ACT1", 70041L));               // 70041%100=41 ≥40
        assertEquals(41000, e.getCode());
    }

    @Test
    @DisplayName("白名单直通：灰度 0% 也放行名单内用户")
    void whitelistBypassesPercent() {
        status("ONLINE");
        gray("0|70001");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70002L));
    }

    @Test
    @DisplayName("模板未挂活动（activityNo 空/null）→ 本闸不管，由模板自身状态控制")
    void blankActivityNoSkipsGate() {
        assertDoesNotThrow(() -> gate.checkGrantable(null, 70001L));
        assertDoesNotThrow(() -> gate.checkGrantable(" ", 70001L));
        verify(ops, never()).get(anyString());
    }

    @Test
    @DisplayName("灰度键形状不认识 → 放行（格式演化不该把领券全堵死），状态键仍强制")
    void malformedGrayIgnoredButStatusStillEnforced() {
        status("FINISHED");
        gray("not-a-valid-shape");

        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70001L),
                "灰度形状不认识可以放行，状态键的强制不受影响");
    }

    @Test
    @DisplayName("P1：GRAY 状态 = 可参与——命中的用户放行、未命中的拒（对齐 participatable 含 GRAY）")
    void grayStatusParticipatesViaGrayRule() {
        status("GRAY");
        gray("40|");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 100L),
                "灰度放量阶段命中用户必须能领（原实现只认 ONLINE，灰度期全拒）");
        BizException e = assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70041L));
        assertEquals(41000, e.getCode());
    }

    @Test
    @DisplayName("P1：GRAY + 白名单直通——内测账号在灰度期可用")
    void grayStatusWhitelistPasses() {
        status("GRAY");
        gray("0|70001");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));
    }

    @Test
    @DisplayName("P1：白名单带空格（activity 侧容错接受的格式）→ 逐项 trim，不炸全活动")
    void whitelistWithSpacesTolerated() {
        status("ONLINE");
        gray("0| 70001 , 70002 ");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70002L),
                "带空格的 CSV 与 GrayRuleCache 同口径 trim，不能 NFE→50000");
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70003L));
    }

    @Test
    @DisplayName("W2.1：状态值新形状 status|version——取状态段判定，发布侧 CAS 防旧快照回滚")
    void versionedStatusValueParsedByStatusSegment() {
        status("OFFLINE|9");
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70001L),
                "OFFLINE|9 应按 OFFLINE 拒绝（值带版本段，状态判定只看 | 前段）");

        status("ONLINE|1");
        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));

        status("GRAY|4");
        gray("0|70001");
        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L),
                "GRAY|version 的灰度语义与裸 GRAY 一致");
    }

    @Test
    @DisplayName("W2.1：旧形状（裸 status，迁移期 activity 旧代码写的值）照常工作")
    void legacyBareStatusStillWorks() {
        status("OFFLINE");
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70001L));
    }

    @Test
    @DisplayName("W2.4：灰度三段形状 percent|w1,w2|version——白名单只取中段，尾号不被 |version 吃掉")
    void threeSegmentGrayWhitelistMiddleSegmentOnly() {
        status("ONLINE");
        gray("0|70001,70002|7");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70002L),
                "70002 是白名单尾号：不剥 version 段它会变成 '70002|7' 而 NFE 被跳过，尾号静默失效");
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70003L));
    }

    @Test
    @DisplayName("W2.4：三段形状的 percent 判定照常（空白名单 + 40%）")
    void threeSegmentGrayPercentStillApplies() {
        status("GRAY");
        gray("40||5");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 100L));
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70041L));
    }

    @Test
    @DisplayName("W2.4：两段旧形状（迁移期灰度值，无 version 段）白名单照常解析")
    void legacyTwoSegmentGrayWhitelistStillWorks() {
        status("ONLINE");
        gray("0|70001");

        assertDoesNotThrow(() -> gate.checkGrantable("ACT1", 70001L));
        assertThrows(BizException.class, () -> gate.checkGrantable("ACT1", 70002L));
    }
}
