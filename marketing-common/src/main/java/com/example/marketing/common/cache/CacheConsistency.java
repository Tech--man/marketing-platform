package com.example.marketing.common.cache;

/**
 * "缓存与账对不对得上"的自检契约。实现方是各业务模块自己——只有它知道自己的键形与口径。
 *
 * <p>为什么公式不能搬到 ④：{@code marketing-admin} 不依赖任何业务模块，跨不过模块边界；
 * 而"在 ④ 里再写一遍预算对账 SQL"正是 ①② 反复防的那类错（两份口径早晚分叉，
 * 分叉的表现是自检说一切正常而账其实不对）。④ 只读这里注册的读数。</p>
 *
 * <p>与 {@link CacheReheater} 一样按 {@code type} 归一：同一个模块的"怎么算"与
 * "现在差多少"必须来自同一处，前者是修、后者是发现。</p>
 */
public interface CacheConsistency {

    /** 与对应 {@link CacheReheater#type()} 同值（budget / coupon-stock / seckill-stock） */
    String type();

    /**
     * 抽样内不符的条数。<b>-1 表示无法判定</b>（Redis 不可达等）——0 是"查过且都一致"，
     * 两者绝不能混：把"看不见"报成"健康"是本段存在的理由所要防的那件事。
     *
     * <p>抽样而非全量：每条约两次读（DB 权威值 + Redis 当前值），全量扫会在弱主机上
     * 把一次后台读放大成几百次查询。上限由各模块自己定并写在自己的注释里。</p>
     */
    int mismatchCount();
}
