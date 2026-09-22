package com.example.marketing.common.cache;

/**
 * 缓存重预热原语：按权威源（DB / 流水）重算一个预扣缓存键的值。
 *
 * <p>存在的理由：预扣键是 SETNX 一次性预热出来的，运营改了 DB 不会自动生效；
 * 而预算键丢失后按全额重写又会让预算"回涨"（设计文档地雷 A/E）。
 * 重算公式属于各领域自己，因此实现放在各业务模块，注册进 {@link CacheReheatRegistry}。</p>
 */
public interface CacheReheater {

    /** 该 reheater 负责的缓存类型标识，全局唯一 */
    String type();

    /**
     * @param key   领域内可识别的键（活动号 / 模板号 / 活动号）
     * @param force true = 删掉重建（运营改完配置要显式走这条）；false = 只补缺，不覆盖既有值
     */
    Result reheat(String key, boolean force);

    record Result(String type, String key, long before, long after, String formula) {
    }
}
