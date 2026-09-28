package com.example.marketing.account.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消费者账号参数。与后台的 {@code AdminProperties} 同一条纪律：这里只放数值型默认，
 * 字符串型密钥的默认值留给各形态的 compose/脚本，启动时由 AccountSecurityConfig 校验并告警。
 *
 * <p><b>密钥必须与 {@code ADMIN_JWT_SECRET} 是两个值</b>：一把密钥两种用途，
 * 后台签出的 token 就能被消费者侧接受（反之亦然），"两套凭证不互通"这件事
 * 就只剩 claim 形状这一层侥幸。独立密钥把它压到 HMAC 这一层。</p>
 */
@Data
@ConfigurationProperties(prefix = "marketing.account")
public class AccountProperties {

    /** HS256 密钥，必须与网关同值；空或等于占位符时启动告警 */
    private String jwtSecret = "";

    /** access token 寿命（秒）。短，因为它决定"Redis 被清空后旧 token 还能活多久"的上界 */
    private long accessTtlSeconds = 900;

    /**
     * refresh token 寿命（秒），默认 30 天。
     *
     * <p>这一位是消费者端与后台的关键差异：后台是桌面低频场景，15 分钟重登不是事件；
     * 消费者在结算中途被弹回登录框是产品事故。而"把 access token 拉长"不是替代方案 ——
     * 吊销位存在 Redis（{@code consumer:revoked:*} / {@code consumer:bump:*}），
     * Redis 被清空时旧 token 会活到自然过期，token 越长吊销窗口越长，长到 30 天等于没有吊销。
     * 所以长寿命只给 refresh，且 refresh 只能换 access、不能直接访问业务。</p>
     */
    private long refreshTtlSeconds = 30 * 24 * 3600;

    /** 时钟偏移容忍（秒），跨主机 FULL 档依赖 NTP */
    private long clockSkewSeconds = 30;

    /** 连续失败多少次进入锁定（按账号计，落在 DB 上，不依赖 Redis） */
    private int maxFailCount = 5;

    /** 锁定时长（分钟），到点自动放行 */
    private int lockMinutes = 15;

    /**
     * 每个 IP 在窗口内允许多少次登录尝试，<=0 关闭该限速。
     *
     * <p>默认比后台的 10 次/60s 更紧：消费者登录口是公网可撞的，而后台是内部运维。
     * 这一位与网关的 {@code account-route} 桶是两层不同的东西 —— 那层防"整个服务被打爆"，
     * 这层防"专打登录口的自动化撞库"，且它跑在 BCrypt 之前。</p>
     */
    private int loginIpLimit = 5;
    private int loginIpWindowSeconds = 60;

    /** 注册口每 IP 限额。注册比登录更需要防批量脚本，但阈值给得比登录宽一档 */
    private int registerIpLimit = 10;
    private int registerIpWindowSeconds = 3600;

    /** 同一账号最多保留多少个有效会话（超出则按签发时间淘汰最旧的），0 = 不限 */
    private int maxActiveSessions = 10;
}
