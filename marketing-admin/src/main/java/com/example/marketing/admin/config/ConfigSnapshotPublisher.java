package com.example.marketing.admin.config;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigMerge;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按四种形态各生成一份合并后的全量快照并广播。
 *
 * <p>顺序固定：先 {@code SET snapshot} 再 {@code SET version}。反过来会让读方看到新版本
 * 却取到旧内容，而"版本号领先于内容"比"慢一秒"糟得多。</p>
 *
 * <p>某形态合并后为空时**删**两个键：只发布"表里出现过的 form"会留一个洞——把 LITE 的行
 * 删干净之后 LITE 快照停在旧值上，"恢复出厂"永远不生效。</p>
 */
@Slf4j
@Service
public class ConfigSnapshotPublisher {

    private final StringRedisTemplate redis;
    private final AdminConfigStore store;
    private final ConfigSchemaReader schemaReader;

    public ConfigSnapshotPublisher(StringRedisTemplate redis, AdminConfigStore store,
                                  ConfigSchemaReader schemaReader) {
        this.redis = redis;
        this.store = store;
        this.schemaReader = schemaReader;
    }

    /**
     * 全局单调序号。Redis 不可用时连广播也做不到，直接按"未广播"报出去：
     * 与其假装写成功了，不如让运营知道什么都没生效。
     */
    public long nextSequence() {
        try {
            Long seq = redis.opsForValue().increment(ConfigKeys.SEQUENCE);
            if (seq == null) {
                throw new IllegalStateException("INCR 返回空");
            }
            return seq;
        } catch (Exception e) {
            throw BizException.of(ErrorCode.CONFIG_NOT_BROADCAST,
                    "Redis 不可用，配置未写入（广播依赖 Redis 序号）: " + e.getMessage());
        }
    }

    public void publishAll(long seq) {
        List<ConfigMerge.Row> rows = store.rows();
        long defVer = schemaReader.schemaVersion();
        Map<String, ConfigDefinition> declared = new LinkedHashMap<>();
        for (ConfigDefinition d : schemaReader.declared()) {
            declared.put(d.key(), d);
        }
        try {
            for (String form : ConfigKeys.FORMS) {
                Map<String, String> merged = ConfigMerge.merge(form, rows);
                if (merged.isEmpty()) {
                    redis.delete(List.of(ConfigKeys.snapshot(form), ConfigKeys.version(form)));
                    continue;
                }
                Map<String, ConfigSnapshot.Entry> entries = new LinkedHashMap<>();
                merged.forEach((key, value) -> {
                    ConfigDefinition def = declared.get(key);
                    entries.put(key, new ConfigSnapshot.Entry(value,
                            def == null ? ConfigType.STRING : def.type(), defVer));
                });
                redis.opsForValue().set(ConfigKeys.snapshot(form), ConfigSnapshotCodec.write(
                        new ConfigSnapshot(seq, Instant.now().toString(), entries)));
                redis.opsForValue().set(ConfigKeys.version(form), String.valueOf(seq));
            }
            log.info("[config] 快照已广播 seq={}, 真值行数={}", seq, rows.size());
        } catch (Exception e) {
            throw BizException.of(ErrorCode.CONFIG_NOT_BROADCAST,
                    "配置已落库但未广播（各进程仍按旧值），用重新广播修复: " + e.getMessage());
        }
    }
}
