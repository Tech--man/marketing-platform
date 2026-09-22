package com.example.marketing.common.cache;

import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重预热注册表：按 type 分发到各领域自己实现的 {@link CacheReheater}。
 * 未知 type 必须显式报错而不是静默无操作 —— 静默会让运维以为"已经刷新了"。
 */
class CacheReheatRegistryTest {

    /** 测试替身：CacheReheater 有两个抽象方法（type + reheat），不是 lambda 目标。 */
    private static CacheReheater reheater(String type, long after) {
        return new CacheReheater() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Result reheat(String key, boolean force) {
                return new Result(type, key, 0L, after, "fake:" + (force ? "force" : "fill-missing"));
            }
        };
    }

    @Test
    void dispatchesToTheReheaterOwningThatType() {
        CacheReheatRegistry registry = new CacheReheatRegistry(List.of(reheater("budget", 7000L)));

        CacheReheater.Result result = registry.reheat("budget", "ACT2026001", true);

        assertThat(result.type()).isEqualTo("budget");
        assertThat(result.after()).isEqualTo(7000L);
        assertThat(result.formula()).contains("force");
    }

    @Test
    void unknownTypeFailsLoudlyInsteadOfReturningNoop() {
        CacheReheatRegistry registry = new CacheReheatRegistry(List.of(reheater("budget", 1L)));

        assertThatThrownBy(() -> registry.reheat("nope", "K", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void exposesRegisteredTypesForTheOpsListing() {
        CacheReheatRegistry registry = new CacheReheatRegistry(
                List.of(reheater("budget", 1L), reheater("coupon-stock", 1L)));

        assertThat(registry.types()).containsExactlyInAnyOrder("budget", "coupon-stock");
    }

    @Test
    void duplicateTypeRegistrationIsAWiringBug() {
        assertThatThrownBy(() -> new CacheReheatRegistry(
                List.of(reheater("budget", 1L), reheater("budget", 2L))))
                .isInstanceOf(IllegalStateException.class);
    }
}
