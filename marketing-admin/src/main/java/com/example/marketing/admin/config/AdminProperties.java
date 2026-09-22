package com.example.marketing.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理后台参数。全部走环境变量下发，默认值是 dev 档能跑起来的值，不是生产可用值 ——
 * 口令策略与密钥一旦进代码就成了"看不见的常量"，所以这里只给数值型默认，
 * 字符串型密钥的默认值留给各形态的 compose/脚本，启动时由 AdminSecurityConfig 校验并告警。
 */
@Data
@ConfigurationProperties(prefix = "marketing.admin")
public class AdminProperties {

    /** HS256 密钥，必须与网关同值；空或等于占位符时启动告警 */
    private String jwtSecret = "";

    /** access token 寿命（秒）。短寿命 + 无 refresh 是①②的取舍，refresh 留给后续 */
    private long accessTtlSeconds = 900;

    /** 时钟偏移容忍（秒），跨主机 FULL 档依赖 NTP */
    private long clockSkewSeconds = 30;

    /** 连续失败多少次进入锁定 */
    private int maxFailCount = 5;

    /** 锁定时长（分钟），到点自动放行 */
    private int lockMinutes = 15;

    /** 每个 IP 在窗口内允许多少次登录尝试，<=0 关闭该限速 */
    private int loginIpLimit = 10;
    private int loginIpWindowSeconds = 60;
}
