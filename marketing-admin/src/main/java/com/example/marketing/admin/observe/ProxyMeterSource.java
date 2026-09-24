package com.example.marketing.admin.observe;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 跨进程抓 {@code /actuator/prometheus}。FULL 分进程时 admin 进程里没有业务模块的
 * {@code MeterRegistry}，只能这么读（母版事实 #2）。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>不跟重定向</b>。白名单只管得住"我请求了谁"，3xx 会把目标交给对方决定；</li>
 *   <li><b>超限不静默截断</b>。半份文本会被解析成"这个进程少了一半指标"，
 *       而那正是本段最不该造出来的读数；</li>
 *   <li><b>任何失败都抛 {@link ScrapeException}</b>，由上层逐 target 记成 ERROR + 原因，
 *       而不是把整张大盘变红或变空。</li>
 * </ol>
 */
public class ProxyMeterSource implements MetricSource {

    /** 响应体上限（母版 §7）。public 是为了测试能按它造一个超限响应，而不是猜这个数 */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final Map<String, TargetRef> targets;
    private final HttpClient http;

    public ProxyMeterSource(Map<String, TargetRef> targets) {
        this.targets = targets;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public String mode() {
        return "proxy";
    }

    @Override
    public boolean serves(String target) {
        return targets.containsKey(target);
    }

    @Override
    public PrometheusTextParser.ParseResult scrape(String target) {
        TargetRef ref = targets.get(target);
        if (ref == null) {
            throw new ScrapeException("target \"" + target + "\" 不在 ④ 的可抓清单里（清单是配置项，"
                    + "不接受外部输入 host）");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(ref.url()))
                .timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) {
                    ignored.skip(MAX_BYTES);
                } catch (IOException suppressed) {
                    // 关闭失败不影响"这次抓取按状态码判失败"这个结论
                }
                throw new ScrapeException("HTTP " + status + " from " + ref.url()
                        + (status >= 300 && status < 400 ? "（重定向一律不跟，见 SSRF 约束）" : ""));
            }
            return PrometheusTextParser.parse(readBounded(response.body(), ref));
        } catch (IOException e) {
            throw new ScrapeException("抓取 " + ref.url() + " 失败: " + e.getClass().getSimpleName()
                    + " " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException("抓取 " + ref.url() + " 被中断", e);
        }
    }

    /** 读到 MAX_BYTES 为止；真超限就抛——返回半份比抛错更坏 */
    private static String readBounded(InputStream body, TargetRef ref) throws IOException {
        try (InputStream in = body) {
            byte[] buf = new byte[MAX_BYTES + 1];
            int read = 0;
            int n;
            while (read < buf.length && (n = in.read(buf, read, buf.length - read)) > 0) {
                read += n;
            }
            if (read > MAX_BYTES) {
                throw new ScrapeException("响应超过 256KB 上限（" + ref.url() + "）——"
                        + "拒绝按半份解析，那会被读成\"少了几个指标\"");
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        }
    }
}
