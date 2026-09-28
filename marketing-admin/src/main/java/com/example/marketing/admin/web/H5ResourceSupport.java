package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;

/**
 * C 端 H5 的静态资源判定，与 {@link UiResourceSupport} 同构但前缀不同。
 *
 * <p>刻意与后台那份分开、而不是把 {@code UiResourceSupport} 参数化：那三条判断
 * （回退/缓存/CSP）任何一个写错都会伤人，且各自有独立的单测在守。合并成一个带 prefix
 * 入参的类，会让两条路径共享同一批断言的复制粘贴，反而更容易漂移。这里保持"两份、
 * 各自被测"，代价是十几行结构重复，换来的是改 /ui 的行为不会顺带改到 /h5。</p>
 */
public final class H5ResourceSupport {

    /** classpath 下的 H5 索引页位置（{@code static/} 前缀由 ResourceHandler 负责） */
    public static final String INDEX_LOCATION = "h5/index.html";

    private static final String PREFIX = "/h5/";

    private H5ResourceSupport() {
    }

    /**
     * 这个请求要不要在资源不存在时回退到 index.html。
     *
     * <p>规则同后台：根路径回退；带扩展名的不回退（缺文件要说"缺"）；带 {@code ..} 的
     * 一律不回退——这段逻辑跑在 classpath 资源解析上，不能成为一个读文件的旁路。
     * H5 用 hash 路由（{@code /h5/#/cart}），所以真深链其实永远落在 {@code /h5/} 这一发；
     * history 回退在这里是防御性的，不是主路径。</p>
     */
    public static boolean shouldFallbackToIndex(String requestPath) {
        if (requestPath == null || requestPath.contains("..")) {
            return false;
        }
        String tail = requestPath.startsWith(PREFIX) ? requestPath.substring(PREFIX.length()) : requestPath;
        if (tail.isEmpty()) {
            return true;
        }
        if (tail.startsWith("assets/")) {
            return false;
        }
        return !tail.contains(".");
    }

    /** 只管响应头，不管路由：由 /h5/** 上的 filter 调（见 {@link H5WebMvcConfig}） */
    public static void applyTo(HttpServletResponse res, String requestPath) {
        res.setHeader("Content-Security-Policy", "script-src 'self'; object-src 'none'; base-uri 'self'");
        String tail = requestPath != null && requestPath.startsWith(PREFIX)
                ? requestPath.substring(PREFIX.length()) : requestPath;
        if (tail != null && tail.startsWith("assets/")) {
            res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            res.setHeader("Cache-Control", "no-store");
        }
    }
}
