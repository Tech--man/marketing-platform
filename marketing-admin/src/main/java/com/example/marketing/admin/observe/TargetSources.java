package com.example.marketing.admin.observe;

import java.util.List;
import java.util.Set;

/**
 * 按 target 名分派指标源：名单里的走本进程 registry，其余走 HTTP。
 *
 * <p>为什么不是"整张盘选一个源"：LITE 下四个业务模块与后台确实同进程（走本地最准也最便宜），
 * 但网关<b>在任何形态下都是独立进程</b>（母版事实 #2）。二选一的结果是 LITE 的运维面
 * 读不到网关的限流拒绝数——恰恰是单机服役档最该看到的那个数。</p>
 */
public class TargetSources {

    private final MetricSource local;
    private final MetricSource proxy;
    private final Set<String> localNames;

    public TargetSources(MetricSource local, MetricSource proxy, List<String> localTargets) {
        this.local = local;
        this.proxy = proxy;
        this.localNames = Set.copyOf(localTargets);
    }

    public MetricSource source(String target) {
        return localNames.contains(target) ? local : proxy;
    }

    /** 每个 target 实际走的哪条路（视图里逐条带上，运维才知道某个读数是本地还是抓来的） */
    public String sourceOf(String target) {
        return source(target).mode();
    }
}
