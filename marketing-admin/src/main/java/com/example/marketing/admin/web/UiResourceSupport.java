package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;

/**
 * ⑥ 的"该回退吗 / 该怎么缓存 / 该给什么 CSP"判定。
 *
 * <p>刻意与 {@link UiWebMvcConfig} 分开：这里是可单测的纯决策，配置类只负责把 Spring MVC
 * 的钩子接上来。三处判断里任何一个写错都会伤人（索引被缓存 → 升级后白屏；js 404 被回退成
 * 一份 HTML → 报错信息把人支去查构建；CSP 漏在脚本响应上 → 少一层 XSS 兜底），
 * 而它们在 MVC 里没有一个能便宜地测。</p>
 */
public final class UiResourceSupport {

    /** classpath 下的索引页位置（{@code static/} 前缀由 ResourceHandler 负责） */
    public static final String INDEX_LOCATION = "ui/index.html";

    private static final String PREFIX = "/ui/";

    private UiResourceSupport() {
    }

    /**
     * 这个请求要不要在资源不存在时回退到 index.html（history 路由）。
     *
     * <p>规则只有三条：根路径回退；带扩展名的不回退（缺文件要说"缺"）；带 {@code ..} 的
     * 一律不回退——这段逻辑跑在 classpath 资源解析上，不能成为一个读文件的旁路。</p>
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

    /** 只管响应头，不管路由：由 /ui/** 上的那个 filter 调（见 {@link UiWebMvcConfig}） */
    public static void applyTo(HttpServletResponse res, String requestPath) {
        res.setHeader("Content-Security-Policy", "script-src 'self'; object-src 'none'; base-uri 'self'");
        String tail = requestPath != null && requestPath.startsWith(PREFIX)
                ? requestPath.substring(PREFIX.length()) : requestPath;
        if (tail != null && tail.startsWith("assets/")) {
            // 文件名带内容指纹，内容变了名字就变，所以可以放心给一年。
            res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            res.setHeader("Cache-Control", "no-store");
        }
    }
}
