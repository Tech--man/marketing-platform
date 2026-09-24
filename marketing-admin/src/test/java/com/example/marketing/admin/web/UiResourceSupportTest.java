package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥ 的三态判定：什么该回退到 index、什么该 404、什么该 no-store。
 *
 * <p>用 {@link MockHttpServletResponse} 而不是起整个 web 上下文：这三件事是纯决策，
 * 而 Spring MVC 的 ResourceHandler 只在真实 servlet 里跑得起来。为了一个 if 起一个上下文，
 * 换来的是"这条断言在 CI 里被环境问题跳过"——④ 已经因为不给 Clock bean 而见识过
 * "单测看不见装配失败"是什么后果，反过来（为了测逻辑而依赖整个装配）同样的坑不必再踩一次。</p>
 */
class UiResourceSupportTest {

    @Test
    void 索引页必须no_store因为指纹文件名一变旧索引就会404() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/");
        assertEquals("no-store", res.getHeader("Cache-Control"),
                "索引页可缓存 = 升级后台后用户拿着旧索引去要新指纹，白屏");
    }

    @Test
    void 指纹资源给一年immutable而缺文件不许被回退成一份HTML() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/assets/index-Cq7fZ2.js");
        String cc = res.getHeader("Cache-Control");
        assertTrue(cc.contains("max-age=31536000"), "指纹资源应给一年期，实际：" + cc);
        assertTrue(cc.contains("immutable"), "缺 immutable 意味着浏览器仍会带条件请求，实际：" + cc);

        // 只有"看起来是前端路由"才回 index。assets/ 与任何带扩展名的路径必须 404：
        // js 404 拿到一份 HTML 时浏览器报 "Failed to load module script"，
        // 排查方向会被带去查构建，而真问题只是仓库里少了一个文件。
        assertTrue(UiResourceSupport.shouldFallbackToIndex("/ui/"));
        assertTrue(UiResourceSupport.shouldFallbackToIndex("/ui/audits"));
        assertFalse(UiResourceSupport.shouldFallbackToIndex("/ui/assets/index-Cq7fZ2.js"));
        assertFalse(UiResourceSupport.shouldFallbackToIndex("/ui/favicon.ico"));
        assertFalse(UiResourceSupport.shouldFallbackToIndex("/ui/../application.yml"),
                "带 .. 的必须拒：回退逻辑不能变成读 classpath 的口子");
    }

    @Test
    void CSP只允许自源脚本且禁object与外联base() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/");
        String csp = res.getHeader("Content-Security-Policy");
        assertNotNull(csp, "没有 CSP：⑥ 的 XSS 兜底只剩 900s TTL（spec §7 说那是三层里的一层）");
        assertTrue(csp.contains("script-src 'self'"));
        assertTrue(csp.contains("object-src 'none'"));
        assertTrue(csp.contains("base-uri 'self'"));
    }

    @Test
    void 每个ui响应都要带CSP不只是索引页() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/assets/index-Cq7fZ2.js");
        assertNotNull(res.getHeader("Content-Security-Policy"),
                "脚本文件本身也是攻击面可达的地方，头不能只在 HTML 上给");
    }
}
