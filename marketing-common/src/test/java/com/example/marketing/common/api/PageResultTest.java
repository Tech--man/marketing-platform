package com.example.marketing.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PageResult 契约：分页元信息随数据一起回给调用方，且 map 不得丢元信息。
 */
class PageResultTest {

    @Test
    void keepsTotalPageAndSizeWhenBuiltFromRecords() {
        PageResult<String> page = PageResult.of(42L, 3, 20, List.of("a", "b"));

        assertThat(page.getTotal()).isEqualTo(42L);
        assertThat(page.getPage()).isEqualTo(3);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getRecords()).containsExactly("a", "b");
    }

    @Test
    void mapTransformsRecordsButKeepsPagingMeta() {
        PageResult<Integer> mapped = PageResult.of(42L, 3, 20, List.of("10", "20"))
                .map(Integer::valueOf);

        assertThat(mapped.getRecords()).containsExactly(10, 20);
        assertThat(mapped.getTotal()).isEqualTo(42L);
        assertThat(mapped.getPage()).isEqualTo(3);
        assertThat(mapped.getSize()).isEqualTo(20);
    }

    @Test
    void emptyPageCarriesTotalSoFrontEndCanTellEmptyFromMissing() {
        PageResult<String> page = PageResult.of(0L, 1, 20, List.of());

        assertThat(page.isEmpty()).isTrue();
        assertThat(page.getTotal()).isZero();
    }

    @Test
    void rejectsNegativeTotalBecauseItIsAlwaysADataBug() {
        assertThatThrownBy(() -> PageResult.of(-1L, 1, 20, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
