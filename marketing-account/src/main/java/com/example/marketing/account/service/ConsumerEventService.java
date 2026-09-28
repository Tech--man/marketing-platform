package com.example.marketing.account.service;

import com.example.marketing.account.infrastructure.entity.ConsumerEventEntity;
import com.example.marketing.account.infrastructure.mapper.ConsumerEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 消费者身份事件落库。
 *
 * <p><b>直连本表写入，不走 {@code AuditOutbox} 那条 Redis Stream 总线</b>：
 * outbox 存在的理由是"业务进程够不到 marketing_admin 库"，而 account 就是
 * {@code consumer_event_log} 的 owning 服务，同进程同库，绕一圈总线只会多一个
 * 会丢消息的环节（那条流满则丢最旧）。审计与事件两件事各自留在自己的 owner 手里。</p>
 *
 * <p>失败不影响主流程：记不下来不等于不许登录。这与后台审计的取舍一致
 * （AuditService 吞 INSERT 异常并全字段告警）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerEventService {

    private final ConsumerEventMapper eventMapper;

    public void record(String action, Long userId, String identifier, String jti,
                       String ip, String userAgent, String result) {
        try {
            ConsumerEventEntity event = new ConsumerEventEntity();
            event.setAction(action);
            event.setUserId(userId);
            event.setIdentifier(identifier);
            event.setJti(jti);
            event.setIp(ip);
            event.setUserAgent(truncate(userAgent, 256));
            event.setResult(result);
            event.setCreateTime(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("[account] 身份事件落库失败 action={}, uid={}, identifier={}, result={}, err={}",
                    action, userId, identifier, result, e.toString());
        }
    }

    /** 绝不落明文口令与 token：调用方只传标识符、jti 与原因 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
