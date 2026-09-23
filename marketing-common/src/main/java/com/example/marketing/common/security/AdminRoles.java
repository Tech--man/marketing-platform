package com.example.marketing.common.security;

/**
 * 角色常量。只有这一列、没有角色表 / 权限点表：①②的粒度需求是"能不能改配置、能不能踢人"，
 * 三个值够用。RBAC 表留给真正出现"第四个角色且权限集不是包含关系"的时候。
 *
 * <p>网关按它做粗筛（route 级），admin 与四个业务服务按它做细筛（endpoint 级），
 * 全部用字符串比较。③ 起它是 <b>6 个进程</b>共用的唯一出处（原先在 admin 包里，业务侧 import
 * 不到，只能各抄一份字面量 —— 那正是本仓库在 {@code marketing.*} 上等值副本踩过一次的坑），
 * 所以值域改动要同时改 {@code application.yml} 的网关路由配置。</p>
 */
public final class AdminRoles {

    public static final String ADMIN = "admin";
    public static final String OPERATOR = "operator";
    public static final String READ_ONLY = "read-only";

    /** 写类运维动作（重预热、踢人、停用）放开的角色 */
    public static final String[] OPERATIONAL = {ADMIN, OPERATOR};

    private AdminRoles() {
    }
}
