package com.example.marketing.account.security;

import com.example.marketing.account.config.AccountProperties;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每 IP 的登录/注册限速。放在 account 而不是网关：网关对 account-route 只有一个桶，
 * 那一条挡的是"整个服务被打爆"，而撞库与批量注册是专打这两个口的 ——
 * 两者阈值差着量级（读接口几十 QPS 很宽，登录 5 次/分钟已经很紧）。
 * 把子路径特例塞进网关会让限流配置变成一堆 if 路径。
 *
 * <p>计数含成功的登录尝试：限的是"这个 IP 多快在试口令"，不是"失败了几次"。
 * 别把它当失败锁定看 —— 失败锁定在 DB 的 fail_count 上，那一把按账号计、不依赖 Redis。</p>
 *
 * <p>失败关闭方向与后台一致：Redis 不可用时按"放行"处理。这里防的是自动化撞库的性价比，
 * 不是准入；让 Redis 抖动把真实消费者关在门外，代价比多放几次口令尝试大
 * （口令侧还有 fail_count 锁定兜底，那把锁在 DB 上）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerLoginGuard {

    static final String LOGIN_KEY_PREFIX = "consumer:login:ip:";
    static final String REGISTER_KEY_PREFIX = "consumer:register:ip:";

    private final StringRedisTemplate redis;
    private final AccountProperties properties;

    /** @throws BizException 超限；调用方不捕获，直接变 42900 */
    public void checkLogin(String ip) {
        check(LOGIN_KEY_PREFIX, ip, properties.getLoginIpLimit(), properties.getLoginIpWindowSeconds(), "登录");
    }

    /** 注册口的独立额度：与登录分开计数，否则一个 IP 注册几次就把登录额度吃光 */
    public void checkRegister(String ip) {
        check(REGISTER_KEY_PREFIX, ip, properties.getRegisterIpLimit(),
                properties.getRegisterIpWindowSeconds(), "注册");
    }

    private void check(String prefix, String ip, int limit, int windowSeconds, String what) {
        if (limit <= 0) {
            return;
        }
        String key = prefix + (ip == null ? "unknown" : ip);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        }
        if (!allows(count, limit)) {
            log.warn("[account] {}过于频繁 ip={}, count={}, limit={}/{}s",
                    what, ip, count, limit, windowSeconds);
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                    what + "尝试过于频繁，请 " + windowSeconds + " 秒后再试");
        }
    }

    /** 纯判定，单独拎出来是为了让"边界到底是 > 还是 >="可测 */
    static boolean allows(Long count, int limit) {
        return count == null || count <= limit;
    }
}
