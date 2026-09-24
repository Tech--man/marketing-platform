package com.example.marketing.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥ 的产物门禁。这个测试<b>不连库、不起 Spring 上下文</b>——它只回答一个问题：
 * jar 里的后台界面是不是自洽的（索引在、它引用的指纹文件也都在）。
 *
 * <p>起上下文在这里反而是坏事：一个连不上的库会让这条断言变成"跳过"，
 * 而白屏后台恰恰需要在 {@code mvn test} 里被稳定地抓到。dist 是入仓产物，
 * "改了 .vue 忘了重构建"与"只拷了 index.html 没拷新的 assets"是同一种事故，
 * 两者都能被下面两条断言挡掉。</p>
 */
class UiDistIntegrityTest {

    @Test
    void classpath里有ui索引页且它引用的入口脚本存在() throws Exception {
        ClassPathResource index = new ClassPathResource("static/ui/index.html");
        assertTrue(index.exists(),
                "static/ui/index.html 不在 classpath：⑥ 的产物没构建进仓库（跑 ./scripts/build-ui.sh）");

        String html = new String(index.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("src=\"/ui/(assets/[^\"]+\\.js)\"").matcher(html);
        assertTrue(m.find(), "索引页里没有 /ui/assets/*.js 入口，前端源码或 vite 的 base 漂了：" + html);

        // 引用的绝对路径 /ui/assets/x.js ↔ classpath 的 static/ui/assets/x.js：
        // 中间的 ui/ 不能漏（漏了这条门禁会恒红，第一版就是这样）。
        ClassPathResource entry = new ClassPathResource("static/ui/" + m.group(1));
        assertTrue(entry.exists(),
                "索引页引用了 " + m.group(1) + " 但仓库里没有这个文件——半提交的 dist 会打出白屏 jar");
        assertNotNull(m.group(1));
    }
}
