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
 * C 端 H5 的静态资源面：把入仓的 {@code static/h5/**} 挂到 {@code /h5/**}，并让
 * {@code /h5} 与 {@code /h5/} 转发到索引页（与后台 {@link UiWebMvcConfig} 同构）。
 *
 * <p>同样放在 admin 而不是网关：网关不该懂前端路由；LITE/dev 下这段随 admin 模块进
 * standalone，FULL 下长在 admin 进程，网关的 {@code h5-route} 只是把同一对
 * {@code ADMIN_HOST/ADMIN_PORT} 换个 Path 前缀。</p>
 */
@Configuration(proxyBeanMethods = false)
public class H5WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/h5").setViewName("forward:/h5/index.html");
        registry.addViewController("/h5/").setViewName("forward:/h5/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/h5/**")
                .addResourceLocations("classpath:/static/h5/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (!resourcePath.isEmpty()) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                        }
                        if (!H5ResourceSupport.shouldFallbackToIndex("/h5/" + resourcePath)) {
                            return null;
                        }
                        ClassPathResource index = new ClassPathResource("static/" + H5ResourceSupport.INDEX_LOCATION);
                        return index.exists() ? index : null;
                    }
                });
    }

    @Bean
    public FilterRegistrationBean<H5HeaderFilter> h5HeaderFilter() {
        FilterRegistrationBean<H5HeaderFilter> bean = new FilterRegistrationBean<>(new H5HeaderFilter());
        bean.addUrlPatterns("/h5/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    /** 只写头，不改路由；判定在 {@link H5ResourceSupport} */
    static class H5HeaderFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            H5ResourceSupport.applyTo(res, req.getRequestURI());
            chain.doFilter(req, res);
        }
    }
}
