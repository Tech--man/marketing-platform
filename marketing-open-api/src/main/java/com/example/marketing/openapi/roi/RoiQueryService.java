package com.example.marketing.openapi.roi;

import java.util.Map;

/**
 * ROI 分析领域接口（占位）：曝光 → 点击 → 领券 → 核销 → GMV → 成本 → ROI。
 *
 * <p>规划链路：行为事件经 Flink 实时聚合同步 ClickHouse，本接口为查询侧抽象；
 * 脚手架阶段以 Prometheus 指标 + 手工 SQL 演示，详见 README 演进路线。</p>
 */
public interface RoiQueryService {

    /** 查询活动维度的实时漏斗与 ROI */
    Map<String, Object> activityFunnel(String activityNo);
}
