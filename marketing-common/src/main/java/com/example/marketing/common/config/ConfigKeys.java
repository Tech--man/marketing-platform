package com.example.marketing.common.config;

import java.util.List;

/**
 * 在线配置的 Redis 键名，唯一允许出现这些字面量的地方。
 *
 * <p>{@code mkt:cfg:seq} 是一个全局 INCR 计数器。version 若靠"读 DB 的 MAX+1"得到，
 * 两个管理员并发写会拿到同一个版本号，读方按版本判"没变"就不再取第二份快照，
 * 于是陈旧值静默生效——那是本项目最贵的一类 bug。</p>
 */
public final class ConfigKeys {

    /** 只有这四种取值；新增形态要同时改这里与 DDL 的列注释 */
    public static final List<String> FORMS = List.of("GLOBAL", "LITE", "FULL", "DEV");
    public static final String GLOBAL = "GLOBAL";
    public static final String SEQUENCE = "mkt:cfg:seq";

    public static String snapshot(String form) {
        return "mkt:cfg:snapshot:" + form;
    }

    public static String version(String form) {
        return "mkt:cfg:version:" + form;
    }

    public static String schema(String service) {
        return "mkt:cfg:schema:" + service;
    }

    private ConfigKeys() {
    }
}
