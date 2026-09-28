package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C 端 H5 的三态判定，与 {@link UiResourceSupportTest} 对称（前缀是 /h5/）。 */
class H5ResourceSupportTest {

    @Test
    void 索引页必须no_store() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        H5ResourceSupport.applyTo(res, "/h5/");
        assertEquals("no-store", res.getHeader("Cache-Control"));
    }

    @Test
    void 指纹资源给一年immutable而缺文件不许被回退成HTML() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        H5ResourceSupport.applyTo(res, "/h5/assets/index-Ab12Cd.js");
        String cc = res.getHeader("Cache-Control");
        assertTrue(cc.contains("max-age=31536000"), "实际：" + cc);
        assertTrue(cc.contains("immutable"), "实际：" + cc);

        assertTrue(H5ResourceSupport.shouldFallbackToIndex("/h5/"));
        assertFalse(H5ResourceSupport.shouldFallbackToIndex("/h5/assets/index-Ab12Cd.js"));
        assertFalse(H5ResourceSupport.shouldFallbackToIndex("/h5/favicon.ico"));
        assertFalse(H5ResourceSupport.shouldFallbackToIndex("/h5/../application.yml"));
    }

    @Test
    void CSP与缓存头对每个h5响应都写() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        H5ResourceSupport.applyTo(res, "/h5/assets/index-Ab12Cd.js");
        String csp = res.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("script-src 'self'"));
        assertTrue(csp.contains("object-src 'none'"));
        assertTrue(csp.contains("base-uri 'self'"));
    }
}
