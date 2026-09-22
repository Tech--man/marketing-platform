package com.example.marketing.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PageQuery 归一化：越界参数被夹到合法区间并**如实回显在结果里**，
 * 而不是静默少给或干脆报错 —— 管理台的分页控件要能看出"你要 5000，我按 200 给"。
 */
class PageQueryTest {

    @Test
    void defaultsToFirstPageOfTwenty() {
        PageQuery q = PageQuery.of(null, null);

        assertThat(q.getPage()).isEqualTo(1);
        assertThat(q.getSize()).isEqualTo(20);
        assertThat(q.offset()).isZero();
    }

    @Test
    void computesOffsetFromNormalizedPageAndSize() {
        PageQuery q = PageQuery.of(3, 50);

        assertThat(q.offset()).isEqualTo(100);
    }

    @Test
    void clampsOversizedRequestToTheMaxPageSize() {
        PageQuery q = PageQuery.of(1, 5000);

        assertThat(q.getSize()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void clampsJunkPageAndSizeUpwardsNotDownwards() {
        PageQuery zeroPage = PageQuery.of(0, 0);

        assertThat(zeroPage.getPage()).isEqualTo(1);
        assertThat(zeroPage.getSize()).isEqualTo(1);
    }
}
