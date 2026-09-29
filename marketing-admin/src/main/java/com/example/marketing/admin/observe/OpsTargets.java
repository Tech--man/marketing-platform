package com.example.marketing.admin.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ④ 能抓哪些进程。这张清单是 ④ 唯一的 SSRF 面，所以按<b>正面清单</b>实现：
 * 只有明确列出的 host 形态与端口才收下，其余一律拒。
 *
 * <p>为什么不用"排除内网/排除保留端口"这种黑名单：本仓库的 MySQL 与 Redis 就发在
 * {@code 127.0.0.1:3307/6379}，黑名单漏一个组合就是一个能读任意端口的后台接口。</p>
 *
 * <p>清单来自 yml 静态列表（不是 nacos）：LITE/dev 根本没有注册中心，而"任何注册进来的
 * 服务都能被发现"本身就是 SSRF 面。</p>
 */
public final class OpsTargets {

    /** 本仓的服务名形态：marketing-*（进程/compose 服务名）、mkt-*（容器名）、standalone、回环地址 */
    private static final Pattern HOST = Pattern.compile("^(marketing-[a-z]+|standalone|127\\.0\\.0\\.1|mkt-[a-z-]+)$");

    /** 各应用的 actuator 端口（网关的管理端口已与业务入口分离，为 8091）。
     *  写死而不是"1024-65535 都行"：多开的端口就是给内网服务开门 */
    private static final Set<Integer> PORTS =
            Set.of(8081, 8082, 8083, 8084, 8085, 8086, 8087, 8090, 8091);

    /** target 名会进 URL 展示与日志，也会由请求方给出，所以同样限死字符集 */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");

    /**
     * 校验整张配置。任何一条不合规就抛——错误必须点名是哪条：
     * 启动期炸掉比运维点大盘时报"抓不到"好得多（配置打错字是唯一成因）。
     */
    public static Map<String, TargetRef> parseAll(Map<String, String> raw) {
        Map<String, TargetRef> out = new LinkedHashMap<>();
        raw.forEach((name, spec) -> out.put(name, parse(name, spec)));
        return Map.copyOf(out);
    }

    public static TargetRef parse(String name, String spec) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法的 ops target 名称 \"" + name
                    + "\"：只允许小写字母开头的 a-z0-9-，最长 40");
        }
        if (spec == null) {
            throw new IllegalArgumentException("ops target " + name + " 没有配置 host:port");
        }
        String trimmed = spec.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException("ops target " + name + " 的取值 \"" + spec
                    + "\" 不是 host:port 形状");
        }
        String host = trimmed.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ops target " + name + " 的端口不是数字: \"" + spec + "\"");
        }
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("ops target " + name + " 的 host \"" + host
                    + "\" 不在白名单内（允许 marketing-* / mkt-* / standalone / 127.0.0.1，区分大小写）");
        }
        if (!PORTS.contains(port)) {
            throw new IllegalArgumentException("ops target " + name + " 的端口 " + port
                    + " 不在允许集合 " + PORTS + " 内");
        }
        return new TargetRef(name, host, port);
    }

    private OpsTargets() {
    }
}
