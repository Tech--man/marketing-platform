package com.example.marketing.admin.dto;

import java.util.List;

/**
 * 配置页总览。
 *
 * @param ownForm            本进程解析到的形态（未设置时为 GLOBAL）
 * @param appliedVersion     本进程当前应用的快照版本（0 = 没有任何在线覆盖生效）
 * @param degradedKeys       本进程最近一次刷新里被忽略的键（未声明/越界）
 * @param unreportedServices 固定候选集里没上报自述的服务（要显式说，而不是少一项）
 */
public record ConfigOverviewView(String ownForm, long appliedVersion, List<ConfigEntryView> entries,
                                 List<ConfigOrphanView> orphans, List<String> unreportedServices,
                                 List<String> degradedKeys) {
}
