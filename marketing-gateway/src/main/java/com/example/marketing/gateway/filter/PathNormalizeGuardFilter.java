package com.example.marketing.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 路径归一化守卫（P1，2026-09-30 第二轮复审）。
 *
 * <p><b>为什么需要它</b>：网关的 {@code /ui/**}、{@code /h5/**} 静态路由不带鉴权前缀
 * （鉴权 filter 只看 {@code /api/**}），Spring Cloud Gateway 又<b>不归一化</b>路径——
 * {@code /ui/../actuator/prometheus} 能命中 {@code Path=/ui/**} 谓词（AntPathMatcher
 * 把 {@code ..} 当普通段），原样转发后由下游 Tomcat 归一化落到同端口的
 * {@code /actuator/**}。admin/standalone 的 actuator 与业务同端口、无 Security 链，
 * 这条遍历等于把 2026-09-29 特意收回环的网关 actuator（8091）在下游身上重新对公网
 * 打开（LITE 形态泄露全部七模块指标）。</p>
 *
 * <p><b>为什么放在最前</b>：归一化攻击的要点是"让每一层看到不同的路径"，所以这道闸
 * 必须先于一切按 path 判定的 filter（TraceId -200 / PreAuthRateLimit -150 / 鉴权
 * -110/-105）。拒绝对解码后的路径逐段检查：{@code ..}、{@code .}、反斜杠——正常
 * 前端与 API 调用不会构造这些形状（编码形式 {@code %2e%2e} 解码后同样命中）。</p>
 */
@Slf4j
@Component
public class PathNormalizeGuardFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // getURI().getPath() 已做百分比解码——与下游 Tomcat 归一化前的解码口径一致，
        // 检查解码后的形状才拦得住 %2e%2e 变体
        String path = exchange.getRequest().getURI().getPath();
        if (path != null && hasTraversal(path)) {
            log.warn("[path-guard] 拒绝含路径穿越段的请求 path={} remote={}",
                    path, exchange.getRequest().getRemoteAddress());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 40000);
            body.put("message", "请求路径不合法");
            body.put("data", null);
            return GatewayResponses.writeJson(exchange.getResponse(), HttpStatus.BAD_REQUEST, body);
        }
        return chain.filter(exchange);
    }

    /** 逐段检查：.. / . / 反斜杠（\ 与 %5c 解码后同形） */
    static boolean hasTraversal(String path) {
        if (path.indexOf('\\') >= 0) {
            return true;
        }
        int start = 0;
        int length = path.length();
        while (start <= length) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? length : slash;
            int segLen = end - start;
            if (segLen == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') {
                return true;
            }
            if (segLen == 1 && path.charAt(start) == '.') {
                return true;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 先于一切按 path 判定的 filter（归一化攻击靠"每层看到不同路径"得手）
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
