package com.example.marketing.common.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua 脚本加载工具：从 classpath 加载脚本并按资源路径缓存，避免每次请求重复解析。
 *
 * <p>约定：所有 Lua 脚本放在各服务 resources/lua/ 目录下。</p>
 */
public final class LuaScripts {

    private static final ConcurrentHashMap<String, RedisScript<Long>> CACHE = new ConcurrentHashMap<>();

    private LuaScripts() {
    }

    /**
     * 加载返回值为整数的 Lua 脚本。
     *
     * @param resource classpath 相对路径，如 "lua/deduct_stock.lua"
     */
    @SuppressWarnings("unchecked")
    public static RedisScript<Long> ofLong(String resource) {
        return CACHE.computeIfAbsent(resource, path -> {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource(path));
            script.setResultType(Long.class);
            return script;
        });
    }
}
