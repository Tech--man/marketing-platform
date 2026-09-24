package com.example.marketing.admin.observe;

/**
 * ④ 的读数来源抽象。两个实现按属性二选一（不是两个都装然后挑：同时装会让 LITE 去抓
 * 根本不存在的兄弟进程，报出一堆假 error）。
 *
 * <p>{@link #scrape} 的契约是这段的核心：<b>抓不到必须抛</b>。返回空列表在调用方看来
 * 和"这个进程一个指标都没有"没有区别，而后者会渲染成一张看着完全正常的大盘。</p>
 */
public interface MetricSource {

    /** {@code local} | {@code proxy}：响应里必须带上它，否则运维无法判断这张大盘读的是谁 */
    String mode();

    PrometheusTextParser.ParseResult scrape(String target);

    /** 本实现能否服务这个 target。不能就别说"抓不到"——那是两种不同的诊断 */
    boolean serves(String target);
}
