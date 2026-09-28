package com.example.marketing.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 端 H5 的产物门禁，与 {@link UiDistIntegrityTest} 同一职责：jar 里的 H5 界面是不是自洽的。
 *
 * <p>与后台那版的关键差别：H5 用了路由级 code-split（每个视图一个异步 chunk），所以引用图
 * 不是一层而是可达闭包——这里从 index.html 出发，把每个被引用 js 的正文再扫一遍、做不动点迭代，
 * 直到没有新资源被发现。这样"半提交的 dist"（只拷了索引没拷某个异步 chunk）会被精确点名。</p>
 */
class H5DistIntegrityTest {

    private static final String BASE = "static/h5/";
    // vite 的引用图混用两种写法：索引 HTML 与入口 preload 数组用 `assets/xxx.js`，
    // 而异步 chunk 之间用相对 `./xxx.js`（base 在运行时才拼）。两种都要收，否则整个
    // code-split 图会从入口 loader 处断开，被误判成一片孤儿。
    private static final Pattern ASSET_ABS = Pattern.compile("assets/[A-Za-z0-9_.-]+\\.(?:js|css)");
    private static final Pattern ASSET_REL = Pattern.compile("\\./([A-Za-z0-9_.-]+\\.(?:js|css))");

    private static void collect(String body, java.util.function.Consumer<String> sink) {
        Matcher a = ASSET_ABS.matcher(body);
        while (a.find()) sink.accept(a.group());
        Matcher r = ASSET_REL.matcher(body);
        while (r.find()) sink.accept("assets/" + r.group(1));
    }

    private static String indexHtml() throws Exception {
        ClassPathResource index = new ClassPathResource(BASE + "index.html");
        assertTrue(index.exists(),
                "static/h5/index.html 不在 classpath：C 端产物没构建进仓库（跑 ./scripts/build-h5.sh）");
        return new String(index.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 从索引出发，闭包收集所有被（直接或间接）引用的资源相对路径（assets/xxx）。 */
    private static Set<String> reachable(String html) throws Exception {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        collect(html, queue::add);
        while (!queue.isEmpty()) {
            String ref = queue.poll();
            if (!seen.add(ref)) continue;
            if (!ref.endsWith(".js")) continue;
            ClassPathResource r = new ClassPathResource(BASE + ref);
            assertTrue(r.exists(), "引用了 " + ref + " 但仓库里没有这个文件——半提交的 dist 会打出白屏 H5");
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            collect(body, (n) -> { if (!seen.contains(n)) queue.add(n); });
        }
        return seen;
    }

    @Test
    void 索引页在且带构建指纹() throws Exception {
        String html = indexHtml();
        assertTrue(html.contains("<!-- build-h5: rev="),
                "索引页没有 build-h5 指纹注释：产物不是由 scripts/build-h5.sh 生成的");
    }

    @Test
    void 索引页引用的每个指纹资源都必须在仓库里() throws Exception {
        String html = indexHtml();
        List<String> refs = new ArrayList<>();
        collect(html, refs::add);
        assertFalse(refs.isEmpty(), "索引页里没有 assets/*.{js,css} 引用：前端源码或 vite 的 base 漂了");
        for (String ref : refs) {
            assertTrue(new ClassPathResource(BASE + ref).exists(), "缺文件：" + ref);
        }
    }

    @Test
    void 仓库里不许躺着没被引用的旧产物() throws Exception {
        Set<String> referenced = reachable(indexHtml());
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:" + BASE + "assets/*");
        List<String> orphans = new ArrayList<>();
        for (Resource r : all) {
            String name = "assets/" + r.getFilename();
            assertNotNull(name);
            if (!referenced.contains(name)) orphans.add(name);
        }
        assertTrue(orphans.isEmpty(),
                "dist 里有没被引用的产物（多半是 emptyOutDir 没生效）：" + orphans);
    }
}
