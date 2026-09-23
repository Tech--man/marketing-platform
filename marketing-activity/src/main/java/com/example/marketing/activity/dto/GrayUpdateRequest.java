package com.example.marketing.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 后台改灰度（⑤ 把真值放进了这两列，本任务是它的写入口）。
 *
 * @param grayPercent   null = 清除灰度，回到"未配灰度 = 全量放行"（GrayService 的既有语义）
 * @param grayWhitelist userId 的 CSV；null 或空 = 无白名单
 */
public record GrayUpdateRequest(
        @Min(value = 0, message = "灰度百分比不能为负")
        @Max(value = 100, message = "灰度百分比不能超过 100") Integer grayPercent,
        String grayWhitelist,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
