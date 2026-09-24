package com.example.marketing.admin.observe;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨进程抓取的失败模式必须逐个验：这一层出错的表现永远是"某个 target 读不到"，
 * 而读不到的正确反应是**说清楚读不到**，不是给一个 0 或一份空大盘。
 *
 * <p>测试用 JDK 自带的 {@code com.sun.net.httpserver} 起真回环端口，而不是 MockRestServiceServer：
 * 生产实现走 {@code java.net.http.HttpClient}（仓库里没有 RestTemplate 可 mock），
 * 而且真服务能真的验"重定向没跟"——桩里数一下被调了几次就行。</p>
 *
 * <p>端口用 bind(0) 拿到的随机口：{@link TargetRef} 本身不校验（校验在 {@link OpsTargets}：
 * 启动期校验配置值、请求期校验 target 名是否在清单里），所以测试可以直连随机口，
 * 而生产路径永远只拿得到白名单内的值。这是分层，不是给测试开口子。</p>
 */
class ProxyMeterSourceTest {

    private HttpServer server;
    private String host;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/prometheus", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        host = server.getAddress().getHostString();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(String path, int status, String body) {
        server.removeContext(path);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private ProxyMeterSource source() {
        return new ProxyMeterSource(Map.of("stub", new TargetRef("stub", host, port)));
    }

    @Test
    @DisplayName("正常抓取：走的是与本地源同一个解析器")
    void scrapesOk() {
        route("/actuator/prometheus", 200, "marketing_audit_drained_total 3.0\n");

        PrometheusTextParser.ParseResult result = source().scrape("stub");

        assertEquals(0, result.malformedLines());
        assertEquals(3.0, PrometheusTextParser.valueOf(
                PrometheusTextParser.byName(result.samples(), "marketing.audit.drained"), null, null));
    }

    @Test
    @DisplayName("非 2xx 报错并带上状态码")
    void serverErrorIsReported() {
        route("/actuator/prometheus", 500, "boom");

        ScrapeException e = assertThrows(ScrapeException.class, () -> source().scrape("stub"));
        assertTrue(e.getMessage().contains("500"), "状态码要进消息: " + e.getMessage());
    }

    @Test
    @DisplayName("3xx 一律不跟：白名单之外的重定向就是 SSRF 通道")
    void redirectsAreRefused() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/elsewhere", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        route("/actuator/prometheus", 302, "");

        assertThrows(ScrapeException.class, () -> source().scrape("stub"));
        assertEquals(0, redirected.get(), "跟了重定向 = 目标地址由对方决定");
    }

    @Test
    @DisplayName("超大响应报错而不是半份：半份文本会被读成\"少了几个指标\"")
    void oversizedBodyIsNotSilentlyTruncated() {
        route("/actuator/prometheus", 200, "x".repeat(ProxyMeterSource.MAX_BYTES + 1024));

        ScrapeException e = assertThrows(ScrapeException.class, () -> source().scrape("stub"));
        assertTrue(e.getMessage().contains("256"), "要说清是超限: " + e.getMessage());
    }

    @Test
    @DisplayName("连不上时包成 ScrapeException，不裸抛 IOException")
    void connectionFailureIsWrapped() throws IOException {
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int closed = dead.getAddress().getPort();
        dead.stop(0);   // JDK17 的 HttpServer 不是 AutoCloseable，只能显式停
        ProxyMeterSource source =
                new ProxyMeterSource(Map.of("nobody", new TargetRef("nobody", "127.0.0.1", closed)));

        assertThrows(ScrapeException.class, () -> source.scrape("nobody"));
    }

    @Test
    @DisplayName("清单外的 target 直接拒：它不是\"抓不到\"，是\"根本不该抓\"")
    void unknownTargetRejected() {
        ProxyMeterSource source = source();

        assertThrows(ScrapeException.class, () -> source.scrape("evil"));
        assertTrue(source.serves("stub"));
        assertTrue(!source.serves("evil"));
        assertEquals("proxy", source.mode());
    }
}
