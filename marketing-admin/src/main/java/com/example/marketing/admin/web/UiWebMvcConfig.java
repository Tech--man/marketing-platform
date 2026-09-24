package com.example.marketing.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * ⑥ 的静态资源面：把入仓的 {@code static/ui/**} 挂到 {@code /ui/**}，并让 history 路由
 * 在直接刷新时也能开（回退 index.html）。
 *
 * <p>放在 admin 而不是网关：网关不该懂前端路由。五个入口都不用它单独配任何东西——
 * LITE/dev 下这段随 admin 模块进 standalone（standalone 的扫描根是
 * {@code com.example.marketing}，所以 {@code admin.web} 在边界内），网关的 {@code ui-route}
 * 只是把同一对 {@code ADMIN_HOST/ADMIN_PORT} 换个值。</p>
 *
 * <p><b>缓存头由 {@link UiHeaderFilter} 独占，不用
 * {@code ResourceHandlerRegistry.setCacheControl(...)}。</b>后者会在 handler 里再写一次
 * {@code Cache-Control}，把 filter 给指纹资源设的 {@code immutable} 覆盖成 no-store——
 * 一个"看起来更保守"的默认值，实际效果是每发 assets 请求都回源。同一个头只能有一个主人。</p>
 */
@Configuration(proxyBeanMethods = false)
public class UiWebMvcConfig implements WebMvcConfigurer {

    /**
     * {@code /ui} 与 {@code /ui/} 必须显式转发到索引页：{@code ResourceHttpRequestHandler}
     * 在 {@code processPath} 里就把空路径判成 404，压根不会问下面的 resolver
     * （真栈实测两连红：先是目录被判存在，修掉后发现根本进不到 resolver）。
     * 转发目标是一条真实资源路径，于是首页与深链走同一份文件、同一套响应头
     * （头由 {@link UiHeaderFilter} 在**原始**请求上就写好了，forward 不影响）。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/ui").setViewName("forward:/ui/index.html");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 空路径必须直接走回退：createRelative("") 拿到的是 ui/ 这个**目录**，
                        // 它 exists() 且 isReadable() 都为真，于是被当成资源返回，
                        // 最后由 handler 以"是个目录"为由拒掉 → /ui/ 恒 404（真栈实测踩到，
                        // 而 shouldFallbackToIndex("/ui/") 早就判对了，是这里绕过了它）。
                        if (!resourcePath.isEmpty()) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                        }
                        if (!UiResourceSupport.shouldFallbackToIndex("/ui/" + resourcePath)) {
                            return null;
                        }
                        ClassPathResource index = new ClassPathResource("static/" + UiResourceSupport.INDEX_LOCATION);
                        return index.exists() ? index : null;
                    }
                });
    }

    @Bean
    public FilterRegistrationBean<UiHeaderFilter> uiHeaderFilter() {
        FilterRegistrationBean<UiHeaderFilter> bean = new FilterRegistrationBean<>(new UiHeaderFilter());
        bean.addUrlPatterns("/ui/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    /** 只写头，不改路由；判定在 {@link UiResourceSupport}，那里有单测 */
    static class UiHeaderFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            UiResourceSupport.applyTo(res, req.getRequestURI());
            chain.doFilter(req, res);
        }
    }
}
