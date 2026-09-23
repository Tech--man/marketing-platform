package com.example.marketing.admin.dto;

/**
 * 配置写请求。
 *
 * <p>{@code form} 必填：留空会被当成 GLOBAL，而"我以为改的是 LITE，实际改了全部档"
 * 是不可见的事故，所以宁可在入参就拒。</p>
 */
public record ConfigSetRequest(String cfgKey, String form, String value, String remark) {
}
