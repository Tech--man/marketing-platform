package com.example.marketing.admin.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prometheus 文本格式解析。④ 的所有读数（本地与跨进程）最后都过这一道，
 * 所以它的错误模式必须是"<b>说清楚读不到</b>"而不是"给个 0"。
 *
 * <p>只认 {@code name{tags} value [timestamp]} 这一种行，且只支持 ④ 需要的三种派生线后缀
 * （见 {@link #DERIVED_SUFFIXES}）。不引 prometheus 官方 parser：那会把整套 model 变成
 * marketing-admin 的直接依赖，而这里要的只是"取几个数"。</p>
 */
public final class PrometheusTextParser {

    /**
     * timer/histogram 的派生线后缀。列出来而不是"任何 {@code _xxx} 都算"，
     * 是为了让 {@link #byName} 不会把 {@code a_b_seconds_extra} 这种<b>另一个指标</b>
     * 当成 {@code a_b_seconds} 的派生线吞进来。
     */
    private static final Set<String> DERIVED_SUFFIXES = Set.of("count", "sum", "max", "bucket", "created");

    /** 来源标识，不是业务维度：留着它，按 target 分组与按 tag 分组会重复计数。 */
    private static final String APPLICATION_TAG = "application";

    /**
     * @param malformedLines 被跳过的行数。<b>必须</b>能被调用方看到：一行的损坏程度足以让
     *                       "整个进程的指标"看起来都是空的，静默跳过等于静默少报
     */
    public record ParseResult(List<MetricSample> samples, int malformedLines) {

        public boolean isEmpty() {
            return samples.isEmpty();
        }
    }

    public static ParseResult parse(String text) {
        List<MetricSample> samples = new ArrayList<>();
        int malformed = 0;
        if (text != null) {
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                MetricSample sample = parseLine(trimmed);
                if (sample == null) {
                    malformed++;
                } else {
                    samples.add(sample);
                }
            }
        }
        return new ParseResult(List.copyOf(samples), malformed);
    }

    /**
     * 按<b>基名</b>取样本。基名可以按代码里的写法给（{@code marketing.audit.drained}），
     * 也可以按线格式给（下划线）—— 两者等价，因为 Micrometer 的 Prometheus 命名约定就是把
     * 点换成下划线，而这条约定只在这里实现一次。
     *
     * <p>但 timer 的基名<b>不等于</b>代码里的 meter 名：Micrometer 会按基准单位给它加后缀
     * （{@code mkt.discount.calc} 渲染成 {@code mkt_discount_calc_seconds_*}）。所以取 timer
     * 要写 {@code mkt.discount.calc.seconds}。这一点不打算在这里猜着补——猜"加 _seconds 还是
     * _milliseconds"会造出更难查的错，取错名字的后果就是空列表，而空列表在 ④ 里是显式的
     * "读不到"。</p>
     *
     * <p>匹配三种线：基名本身、{@code 基名_total}（counter）、以及后缀在
     * {@link #DERIVED_SUFFIXES} 里的派生线。<b>取不到就是空列表</b>，调用方据此报
     * "读不到"；这里返回 0 会把"进程没这个指标"说成"这个指标是 0"。</p>
     */
    public static List<MetricSample> byName(List<MetricSample> all, String baseName) {
        String base = baseName.replace('.', '_');
        List<MetricSample> hits = new ArrayList<>();
        for (MetricSample sample : all) {
            if (matches(sample.name(), base)) {
                hits.add(sample);
            }
        }
        return hits;
    }

    private static boolean matches(String name, String base) {
        if (name.equals(base) || name.equals(base + "_total")) {
            return true;
        }
        String prefix = base + "_";
        return name.startsWith(prefix) && DERIVED_SUFFIXES.contains(name.substring(prefix.length()));
    }

    /**
     * 取单个值：{@code tagKey} 为 null 时要求命中恰好一条，否则按 tag 筛后要恰好一条。
     * 抛异常而不是返回 0 是刻意的——0 是合法读数，分不清"真的是 0"与"取错了/取不到"。
     */
    public static double valueOf(List<MetricSample> samples, String tagKey, String tagValue) {
        List<MetricSample> hits = tagKey == null ? samples
                : samples.stream().filter(s -> tagValue.equals(s.tag(tagKey))).toList();
        if (hits.size() != 1) {
            throw new IllegalStateException("期望恰好一条样本，实际 " + hits.size()
                    + (tagKey == null ? " 条（无 tag 约束）" : " 条（" + tagKey + "=" + tagValue + "）"));
        }
        return hits.get(0).value();
    }

    private static MetricSample parseLine(String line) {
        String name;
        Map<String, String> tags = new LinkedHashMap<>();
        int rest;
        int brace = line.indexOf('{');
        int space = line.indexOf(' ');
        if (brace < 0) {
            if (space <= 0) {
                return null;
            }
            name = line.substring(0, space);
            rest = space;
        } else {
            int close = closingBrace(line, brace);
            if (close < 0 || brace == 0) {
                return null;
            }
            name = line.substring(0, brace);
            if (!parseTags(line.substring(brace + 1, close), tags)) {
                return null;
            }
            rest = close + 1;
        }
        if (!isMetricName(name)) {
            return null;
        }
        String tail = line.substring(rest).trim();
        if (tail.isEmpty()) {
            return null;
        }
        String[] parts = tail.split("\\s+");
        if (parts.length > 2) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        tags.remove(APPLICATION_TAG);
        return new MetricSample(name, Map.copyOf(tags), value);
    }

    /** 引号内的 '}' 不是结束（标签值里出现大括号是合法的） */
    private static int closingBrace(String line, int open) {
        boolean inQuote = false;
        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '}' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    /** 逗号只在引号外才是分隔符——tag 值里带逗号（uri 标签常见）不能把一条样本切成两条 */
    private static boolean parseTags(String text, Map<String, String> into) {
        if (text.isEmpty()) {
            return true;
        }
        int i = 0;
        while (i < text.length()) {
            int eq = text.indexOf('=', i);
            if (eq < 0) {
                return false;
            }
            String key = text.substring(i, eq).trim();
            if (!isLabelName(key) || eq + 1 >= text.length() || text.charAt(eq + 1) != '"') {
                return false;
            }
            int end = text.indexOf('"', eq + 2);
            if (end < 0) {
                return false;
            }
            into.put(key, text.substring(eq + 2, end));
            i = end + 1;
            while (i < text.length() && (text.charAt(i) == ',' || text.charAt(i) == ' ')) {
                i++;
            }
        }
        return true;
    }

    private static boolean isMetricName(String name) {
        return !name.isEmpty() && name.chars().allMatch(PrometheusTextParser::isNameChar)
                && !Character.isDigit(name.charAt(0));
    }

    private static boolean isNameChar(int c) {
        return c == ':' || c == '_' || c == '.' || Character.isLetterOrDigit(c);
    }

    private static boolean isLabelName(String key) {
        return !key.isEmpty() && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                && key.chars().allMatch(c -> c == '_' || Character.isLetterOrDigit(c));
    }

    private PrometheusTextParser() {
    }
}
