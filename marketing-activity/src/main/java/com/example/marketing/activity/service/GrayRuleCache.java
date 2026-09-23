package com.example.marketing.activity.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 灰度规则的进程内缓存：真值在 {@code activity} 的两个列，每 {@code refreshSeconds} 回源一次。
 *
 * <p>为什么不走在线配置那套快照广播：Redis 只能当变更通知，而"未配规则=全量放行"的语义
 * 一旦被 Redis 被清空触发，就是把刚修掉的静默不一致换个地方复发（曾设 5% 的活动会意外全量）。
 * 回源 DB 把这一整类风险消掉，代价是 5s 收敛窗口与一条 {@code WHERE gray_percent IS NOT NULL}
 * 的小查询（种子几十行，可忽略）。</p>
 *
 * <p>读失败保住上一次规则：DB 抖一下不该把所有活动的灰度打回"全量放行"。</p>
 */
@Slf4j
@Component
public class GrayRuleCache {

    public record Rule(int percent, Set<Long> whitelist) {
    }

    private final JdbcTemplate jdbc;
    private final long refreshSeconds;
    private volatile Map<String, Rule> rules = Map.of();
    private volatile ScheduledExecutorService scheduler;

    public GrayRuleCache(JdbcTemplate jdbc,
                         @Value("${marketing.gray.refresh-seconds:${GRAY_REFRESH_SECONDS:5}}") long refreshSeconds) {
        this.jdbc = jdbc;
        this.refreshSeconds = Math.max(1L, refreshSeconds);
    }

    @PostConstruct
    public void start() {
        refreshNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-gray-rule-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshNow, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    public Optional<Rule> rule(String activityNo) {
        return Optional.ofNullable(rules.get(activityNo));
    }

    /** 包内可见给单测。失败时不覆盖 rules —— 清空等价于"全部回到全量放行"，那是最坏的降级。 */
    void refreshNow() {
        Map<String, Rule> next = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT activity_no, gray_percent, gray_whitelist FROM activity "
                    + "WHERE gray_percent IS NOT NULL", rs -> {
                next.put(rs.getString(1), new Rule(clamp(rs.getInt(2)), parseWhitelist(rs.getString(3))));
            });
        } catch (Exception e) {
            log.warn("[gray] 灰度规则回源失败，沿用上一份（{} 条）: {}", rules.size(), e.toString());
            return;
        }
        rules = Map.copyOf(next);
    }

    private static int clamp(int raw) {
        if (raw < 0) {
            log.warn("[gray] gray_percent={} 越界，钳到 0", raw);
            return 0;
        }
        if (raw > 100) {
            log.warn("[gray] gray_percent={} 越界，钳到 100", raw);
            return 100;
        }
        return raw;
    }

    private static Set<Long> parseWhitelist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> out = new HashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(trimmed));
            } catch (NumberFormatException bad) {
                log.warn("[gray] 白名单里有非数字项，已跳过: {}", trimmed);
            }
        }
        return Set.copyOf(out);
    }
}
