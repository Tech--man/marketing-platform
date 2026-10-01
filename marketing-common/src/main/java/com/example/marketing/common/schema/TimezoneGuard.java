package com.example.marketing.common.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.ZoneId;

/**
 * JVM 时区一致性告警（2026-10-01 审计 P2-2 的进程侧兜底）。
 *
 * <p><b>为什么时区是正确性问题</b>：DB 连接串钉死 {@code serverTimezone=Asia/Shanghai}，
 * 而到期收口（ActivityExpirationJob）、超时取消扫描、参与窗判定都是
 * {@code LocalDateTime.now() 直比 DATETIME 列}——JVM 走 UTC 主机时这些判定整体
 * 偏 8 小时（f706bfc 在容器形态上就是栽在这，Dockerfile 已补 ENV TZ 收口；
 * 宿主进程形态由 start-dev/start-all 显式传 -Duser.timezone 收口）。本守卫是
 * 第三道防线：谁绕过入口脚本裸起 JVM，启动日志里立刻能看到时区不齐的告警，
 * 而不是等"过期活动 60s 不 FINISH"这种症状倒查。</p>
 *
 * <p><b>只告警不拒绝启动</b>：与 {@link SchemaMigrationGuard} 的 fail-fast 不同，
 * 时区不齐不破坏数据完整性（方向只会是"晚收口"），而宿主开发机的时区可能性太
 * 多，fail-fast 会把可运行的环境误杀。默认期望 Asia/Shanghai（与 serverTimezone
 * 一致），可用 {@code marketing.timezone-expected} 覆写。</p>
 */
public class TimezoneGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TimezoneGuard.class);

    private final String expectedZoneId;

    public TimezoneGuard(String expectedZoneId) {
        this.expectedZoneId = expectedZoneId;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String actual = ZoneId.systemDefault().getId();
        if (!mismatch()) {
            log.info("[timezone-guard] JVM 时区与期望一致（{}），到期/超时类判定无偏移", actual);
            return;
        }
        log.warn("[timezone-guard] JVM 时区 {} 与 DB serverTimezone 期望 {} 不一致："
                        + "LocalDateTime.now() 直比 DATETIME 的判定（到期收口/超时取消/参与窗）"
                        + "将整体偏移。容器形态检查 Dockerfile 的 ENV TZ；宿主形态给 JVM 加 "
                        + "-Duser.timezone={}",
                actual, expectedZoneId, expectedZoneId);
    }

    /** 供测试断言：期望与实际是否不齐 */
    public boolean mismatch() {
        return !ZoneId.systemDefault().getId().equals(expectedZoneId);
    }
}
