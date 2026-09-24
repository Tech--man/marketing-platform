package com.example.marketing.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥ 的产物门禁。这个测试<b>不连库、不起 Spring 上下文</b>——它只回答一个问题：
 * jar 里的后台界面是不是自洽的。
 *
 * <p>{@code static/ui/**} 是入仓的构建产物，于是有两种"错了也不会立刻响"的事故：
 * 改了 .vue 忘了重构建（代码与界面各说各话），以及只拷了新的 {@code index.html}
 * 没拷新的 {@code assets/*}（打出一个白屏后台）。仓库没有 CI，所以这两条唯一会被
 * 自动跑到的地方就是这里——{@code mvn test} 顺带就验了。</p>
 */
class UiDistIntegrityTest {

    private static final Pattern ASSET_REF =
            Pattern.compile("/ui/(assets/[^\"']+\\.(?:js|css))");

    private static String indexHtml() throws Exception {
        ClassPathResource index = new ClassPathResource("static/ui/index.html");
        assertTrue(index.exists(),
                "static/ui/index.html 不在 classpath：⑥ 的产物没构建进仓库（跑 ./scripts/build-ui.sh）");
        return new String(index.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void 索引页在且带构建指纹() throws Exception {
        String html = indexHtml();
        assertTrue(html.contains("<!-- build-ui: rev="),
                "索引页没有 build-ui 指纹注释：产物不是由 scripts/build-ui.sh 生成的（或注入步骤漂了）");
    }

    @Test
    void 索引页引用的每个指纹资源都必须在仓库里() throws Exception {
        String html = indexHtml();
        List<String> refs = new ArrayList<>();
        Matcher m = ASSET_REF.matcher(html);
        while (m.find()) {
            refs.add(m.group(1));
        }
        assertFalse(refs.isEmpty(), "索引页里没有 /ui/assets/*.{js,css} 引用：前端源码或 vite 的 base 漂了");
        for (String ref : refs) {
            // 引用路径是 /ui/assets/x，classpath 下是 static/ui/assets/x：
            // 中间的 ui/ 不能漏（第一版就漏过一次，表现为恒红在"仓库里没有这个文件"）
            assertTrue(new ClassPathResource("static/ui/" + ref).exists(),
                    "索引页引用了 " + ref + " 但仓库里没有这个文件——半提交的 dist 会打出白屏 jar");
        }
    }

    @Test
    void 仓库里不许躺着没被引用的旧产物() throws Exception {
        String html = indexHtml();
        Set<String> referenced = new HashSet<>();
        Matcher m = ASSET_REF.matcher(html);
        while (m.find()) {
            referenced.add(m.group(1));
        }
        // 入口 chunk 里可能再引用异步 chunk（现在没有，将来 code-split 会有）：
        // 所以把每个被引用的 js 的正文也扫一遍，宁可多认一些也不要误报孤儿。
        for (String ref : List.copyOf(referenced)) {
            if (!ref.endsWith(".js")) {
                continue;
            }
            ClassPathResource r = new ClassPathResource("static/ui/" + ref);
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher mm = Pattern.compile("[\"'](/ui/assets/[^\"']+\\.(?:js|css))[\"']").matcher(body);
            while (mm.find()) {
                referenced.add(mm.group(1).substring("/ui/".length()));
            }
        }
        Resource[] all = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:static/ui/assets/*");
        List<String> orphans = new ArrayList<>();
        for (Resource r : all) {
            String name = "assets/" + r.getFilename();
            assertNotNull(name);
            if (!referenced.contains(name)) {
                orphans.add(name);
            }
        }
        assertTrue(orphans.isEmpty(),
                "dist 里有没被引用的产物（多半是 emptyOutDir 没生效）：" + orphans
                        + " —— 症状是包越来越大而界面没变");
    }
}
