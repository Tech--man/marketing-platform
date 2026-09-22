package com.example.marketing.admin.audit;

/**
 * 审计出口。后台的所有写动作与登录结果都从这里出去。
 *
 * <p>只有 admin 一个消费者，所以先放在本模块而不是 common ——
 * 等③⑤真的出现第二个发审计的模块再提升为跨模块契约，那时才知道接口该长什么样。</p>
 */
public interface AuditSink {

    /** 实现必须自己吞掉落库失败：审计是旁路，不能把一次正常的重预热变成 500 */
    void record(AuditRecord record);
}
