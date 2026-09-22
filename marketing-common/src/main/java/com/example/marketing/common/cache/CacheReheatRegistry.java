package com.example.marketing.common.cache;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CacheReheater} 注册表：按 type 分发，未知 type 直接报错而不是静默无操作 ——
 * 静默会让运维以为"已经刷新了"。
 */
public class CacheReheatRegistry {

    private final Map<String, CacheReheater> byType;

    public CacheReheatRegistry(List<CacheReheater> reheaters) {
        Map<String, CacheReheater> map = new LinkedHashMap<>();
        for (CacheReheater reheater : reheaters) {
            CacheReheater previous = map.putIfAbsent(reheater.type(), reheater);
            if (previous != null) {
                throw new IllegalStateException("CacheReheater 类型重复注册: " + reheater.type());
            }
        }
        this.byType = Map.copyOf(map);
    }

    public CacheReheater.Result reheat(String type, String key, boolean force) {
        CacheReheater reheater = byType.get(type);
        if (reheater == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知缓存类型: " + type + "，可用: " + types());
        }
        return reheater.reheat(key, force);
    }

    public List<String> types() {
        return byType.keySet().stream().sorted().toList();
    }
}
