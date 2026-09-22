package com.example.marketing.coupon.service;

import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;

import java.lang.reflect.Proxy;

/**
 * Mapper 桩：BaseMapper 抽象方法太多，用动态代理只拦需要的那一个
 * （与 discount 模块 RuleCacheManagerTest 同一手法）。
 */
final class CouponTestSupport {

    private CouponTestSupport() {
    }

    static CouponTemplateMapper templateMapperReturning(CouponTemplateEntity template) {
        return (CouponTemplateMapper) Proxy.newProxyInstance(
                CouponTemplateMapper.class.getClassLoader(),
                new Class<?>[]{CouponTemplateMapper.class},
                (proxy, method, args) -> "selectOne".equals(method.getName()) ? template : null);
    }

    static UserCouponMapper userCouponMapperReturning(long issued) {
        return (UserCouponMapper) Proxy.newProxyInstance(
                UserCouponMapper.class.getClassLoader(),
                new Class<?>[]{UserCouponMapper.class},
                (proxy, method, args) -> "selectCount".equals(method.getName()) ? issued : null);
    }
}
