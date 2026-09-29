package com.example.marketing.admin.dto;

/**
 * 配置写请求。
 *
 * <p>{@code form} 必填：留空会被当成 GLOBAL，而"我以为改的是 LITE，实际改了全部档"
 * 是不可见的事故，所以宁可在入参就拒。</p>
 *
 * <p>{@code expectedVersion}（2026-09-29 审查第五批）：带列表里该 (key,form) 行的
 * version 做 CAS，冲突回 41008——两个 operator 并发改同一键时后写静默覆盖先写的
 * 日子到此为止。null = 不比较（脚本/首写路径；UI 一律带值）。</p>
 */
public record ConfigSetRequest(String cfgKey, String form, String value, String remark,
                               Long expectedVersion) {
}
