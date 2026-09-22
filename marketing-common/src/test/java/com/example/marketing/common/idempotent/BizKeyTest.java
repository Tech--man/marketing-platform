package com.example.marketing.common.idempotent;

import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * bizKey 命名空间：幂等/流水表的唯一索引是全局的（见 README 的接口速查与地雷 B/D），
 * 不同场景复用同一段业务编号会互相吞掉写入，所以拼装规则必须由代码统一保证。
 */
class BizKeyTest {

    @Test
    void joinsSceneScopeAndRawWithColon() {
        assertThat(BizKey.of("grant", "CT2026001", "REQ-1"))
                .isEqualTo("grant:CT2026001:REQ-1");
    }

    @Test
    void rejectsBlankPartsBecauseTheyWouldCollideWithOthers() {
        assertThatThrownBy(() -> BizKey.of("", "scope", "raw"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BizKey.of("scene", "scope", "  "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void neverExceedsTheColumnWidthOf128() {
        String huge = "x".repeat(400);

        assertThat(BizKey.of("seckill", "SK2026001", huge)).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void overlongKeysAreTruncatedDeterministicallyAndStayDistinct() {
        String a = BizKey.of("seckill", "SK2026001", "y".repeat(400) + "-A");
        String b = BizKey.of("seckill", "SK2026001", "y".repeat(400) + "-B");

        assertThat(a).isEqualTo(BizKey.of("seckill", "SK2026001", "y".repeat(400) + "-A"));
        assertThat(a).isNotEqualTo(b);
    }
}
