package com.example.marketing.admin.security;

import com.example.marketing.admin.config.AdminProperties;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每 IP 的登录限速。放在 admin 而不是网关：网关对 {@code /api/admin/**} 只有一个桶
 * （{@code RL_ADMIN}），那一条挡的是"整个后台被打爆"，而撞库是专打登录口的 ——
 * 两者需要的阈值完全不同（后台读操作 50/s 很宽，登录 10 次/分钟已经很紧）。
 * 把子路径特例塞进网关会让限流配置变成一堆 if 路径。
 *
 * <p>计数含成功的登录尝试：限的是"这个 IP 多快在试口令"，不是"失败了几次"。
 * 一次正常运维（输错两次、成功两次）烧掉 4 个额度，默认 10 次/分钟够用但不是无限。
 * 别把它当失败锁定看 —— 失败锁定在 DB 的 fail_count 上，那一把按账号计、不依赖 Redis。
 *
 * <p>失败关闭方向：Redis 不可用时计数拿不到值，按"放行"处理。理由是这里防的是
 * 自动化撞库的性价比，不是准入 —— 让 Redis 抖动把管理员关在门外，代价比多放几次
 * 口令尝试大（口令侧还有 fail_count 锁定兜底，那把锁在 DB 上，不依赖 Redis）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginGuard {

    static final String KEY_PREFIX = "admin:login:ip:";

    private final StringRedisTemplate redis;
    private final AdminProperties properties;

    /** @throws BizException 超限；调用方不捕获，直接变 42900 */
    public void check(String ip) {
        if (properties.getLoginIpLimit() <= 0) {
            return;
        }
        String key = KEY_PREFIX + (ip == null ? "unknown" : ip);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(properties.getLoginIpWindowSeconds()));
        }
        if (!allows(count, properties.getLoginIpLimit())) {
            log.warn("[admin] 登录过于频繁 ip={}, count={}, limit={}/{}s",
                    ip, count, properties.getLoginIpLimit(), properties.getLoginIpWindowSeconds());
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                    "登录尝试过于频繁，请 " + properties.getLoginIpWindowSeconds() + " 秒后再试");
        }
    }

    /** 纯判定，单独拎出来是为了让"边界到底是 > 还是 >="可测 */
    static boolean allows(Long count, int limit) {
        return count == null || count <= limit;
    }
}
