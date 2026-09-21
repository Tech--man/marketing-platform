package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.IndexedRule;
import com.example.marketing.discount.domain.PromoRuleDsl;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则快照：某版本全量生效规则的不可变索引。
 *
 * <p>倒排索引 + long[] 位图：tag -> 该 tag 被哪些规则（ordinal 位）引用。
 * 候选剪枝 = 购物车全部标签的位图 OR ∪ 全品类规则位图，把上万条规则先裁到
 * 与购物车相关的几十条，再逐条精确校验。</p>
 */
@Getter
public class RuleSnapshot {

    /** 用户维度标签前缀：requiredTags 中 user: 开头的标签对 CalcInput.userTags 校验 */
    public static final String USER_TAG_PREFIX = "user:";

    private final long version;
    private final List<IndexedRule> rules;
    /** tag -> 位图（第 i 位=1 表示 rules[i] 引用了该 tag） */
    private final Map<String, long[]> invertedIndex;
    /** 无商品维度条件的规则位图（全品类规则） */
    private final long[] fullScopeBitmap;
    private final int words;

    private RuleSnapshot(long version, List<IndexedRule> rules, Map<String, long[]> invertedIndex,
                         long[] fullScopeBitmap, int words) {
        this.version = version;
        this.rules = rules;
        this.invertedIndex = invertedIndex;
        this.fullScopeBitmap = fullScopeBitmap;
        this.words = words;
    }

    /** 由 DSL 列表构建快照 */
    public static RuleSnapshot build(long version, List<PromoRuleDsl> dslList) {
        int n = dslList.size();
        int words = (n + 63) >>> 6;
        List<IndexedRule> rules = new ArrayList<>(n);
        Map<String, long[]> index = new HashMap<>();
        long[] fullBitmap = new long[words];
        for (int i = 0; i < n; i++) {
            PromoRuleDsl dsl = dslList.get(i);
            rules.add(new IndexedRule(dsl, i));
            boolean hasItemTag = false;
            Set<String> required = dsl.getRequiredTags() == null ? Set.of() : dsl.getRequiredTags();
            for (String tag : required) {
                if (tag.startsWith(USER_TAG_PREFIX)) {
                    continue; // 用户维度标签不进商品倒排
                }
                hasItemTag = true;
                index.computeIfAbsent(tag, k -> new long[words])[i >>> 6] |= 1L << (i & 63);
            }
            if (!hasItemTag) {
                fullBitmap[i >>> 6] |= 1L << (i & 63);
            }
        }
        return new RuleSnapshot(version, List.copyOf(rules), Map.copyOf(index), fullBitmap, words);
    }

    /**
     * 位图候选剪枝：返回可能与本购物车相关的规则（保持 ordinal 升序，结果确定）。
     */
    public List<IndexedRule> candidates(List<CalcItem> items) {
        long[] bits = new long[words];
        // 全品类规则直接进入候选
        for (int w = 0; w < words; w++) {
            bits[w] = fullScopeBitmap[w];
        }
        for (CalcItem item : items) {
            if (item.getTags() == null) {
                continue;
            }
            for (String tag : item.getTags()) {
                long[] bitmap = invertedIndex.get(tag);
                if (bitmap == null) {
                    continue;
                }
                for (int w = 0; w < words; w++) {
                    bits[w] |= bitmap[w];
                }
            }
        }
        List<IndexedRule> result = new ArrayList<>();
        for (int w = 0; w < words; w++) {
            long word = bits[w];
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word);
                int ordinal = (w << 6) + bit;
                if (ordinal < rules.size()) {
                    result.add(rules.get(ordinal));
                }
                word &= word - 1;
            }
        }
        return result;
    }

    /** 空快照（无生效规则） */
    public static RuleSnapshot empty(long version) {
        return build(version, List.of());
    }
}
