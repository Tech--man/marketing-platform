package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;

/**
 * 乐观锁的两道关口之一：客户端带回来的 version 必须与库里当前值一致。
 *
 * <p>放 common 是因为 ③ 之后四个业务模块 + admin 的字段编辑端点都要做同一件事、
 * 且报同一个码同一段文案。各写一份正是本仓库在 {@code marketing.*} 等值副本上踩过一次的坑，
 * 而这里会错的地方恰好是"某一处的提示或码不一样"。</p>
 *
 * <p>另一道关口是 {@code updateById} 的影响行数（防比对之后被人插队），
 * 那道由各模块的 mapper 调用点直接判，因为它的语义与"影响 0 行"绑在 ORM 上。</p>
 */
public final class VersionGuard {

    /**
     * @param resource 资源名（"活动"/"券模板"/"规则"…），只出现在报错文案里
     * @return 校验通过时原样返回 expected，便于链式写法
     */
    public static Integer requireEqual(Integer expected, Integer actual, String resource) {
        if (expected == null || !expected.equals(actual)) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT,
                    resource + "已被他人修改（你看到的 version=" + expected
                            + "，当前 " + actual + "），请刷新后重试");
        }
        return expected;
    }

    /** 冲突（updateById 返回 0）用的同码文案：与上面区分开，因为原因不同 */
    public static BizException conflict(String resource) {
        return BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, resource + "已被他人修改，请刷新后重试");
    }

    private VersionGuard() {
    }
}
