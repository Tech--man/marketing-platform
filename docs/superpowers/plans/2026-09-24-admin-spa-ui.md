# ⑥ 独立 SPA 管理后台 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给已有的后台 API（③④⑤ 的 `/api/admin/**`）装上一份同源部署在 `/ui/` 的 Vue3 SPA，让运营与运维在浏览器里完成"看大盘、改配置、改活动、放票、重预热、踢会话"，而不是靠 curl。

**Architecture:** 前端源码在 `marketing-admin-ui/`（**不进 root pom**），Vite 构建产物**入仓**到 `marketing-admin/src/main/resources/static/ui/`，由 admin 模块以 classpath 静态资源提供（LITE/dev 随 standalone 一起进同一个 JVM，零新增进程）。网关加一条 `ui-route` + 白名单 + 限流桶。鉴权仍是 ③ 的 `Authorization: Bearer <admin token>`，前端只做体验层的过期/吊销动作，不当安全边界。

**Tech Stack:** Vue 3 + Vite + vue-router 4 + pinia + Element Plus（unplugin 按需引入）+ vitest（前端逻辑单测，jsdom 环境）；Java 侧 Spring Boot 3.2.5 MVC（`WebMvcConfigurer` + `PathResourceResolver`）、JUnit5、snakeyaml（读 yml 断言网关配置）。

**Spec:** `docs/superpowers/specs/2026-09-24-admin-spa-ui-design.md`（母版 §8 的重验结果、11 页清单、三个门禁、真浏览器旅程都在那里）

## 全局约束（每个任务都受它管）

- **跑 maven 前必须先 `source scripts/common.sh`**，否则 Homebrew JDK 26 下 Lombok 直接炸（本仓反复踩过）。
- **不新增进程**：SPA 只以 classpath 静态资源存在；不引 nginx、不把 node 挂进 maven 生命周期（spec §10）。
- **不放开 CORS**：同源 `/ui/`，开发期靠 vite proxy（spec §1 倒数第二条）。
- **dist 是入仓产物**：只有 `scripts/build-ui.sh` 能改 `static/ui/**`；`node_modules/` 必须被忽略。
- **`-1` / `applicable=false` / `ERROR` 三种"看不见"不许画成 0**（④ 的纪律，界面必须继承）。
- **断言必须能区分**：每条新测试都要做一次变异检查——把生产码改回旧值/改成恒真，断言必须红。写不出红的断言等于没写。
- **不碰 `marketing-open-api`**；不改 ③④⑤ 的后端契约（本段只加静态资源与网关路由，端点零改动）。
- 每任务一个提交，提交信息前缀沿用仓库习惯（`feat(ui):` / `test(ui):` / `chore(ui):` / `docs:`）。
- 冒烟基数：本段开始前 **LITE/dev 89 条、FULL 三档 90 条**；单测基数 **347 用例 / 73 类**。收尾时两处都要刷新。

---

### Task 1: 前端骨架 + 唯一构建入口 + 首次产物入仓

**Files**
- Create: `marketing-admin-ui/package.json`
- Create: `marketing-admin-ui/vite.config.js`
- Create: `marketing-admin-ui/index.html`
- Create: `marketing-admin-ui/src/main.js`
- Create: `marketing-admin-ui/src/App.vue`
- Create: `marketing-admin-ui/src/router/index.js`
- Create: `marketing-admin-ui/src/views/HomeView.vue`
- Create: `scripts/build-ui.sh`
- Modify: `.gitignore`（追加前端两行）
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/web/UiDistIntegrityTest.java`

**Interfaces**
- Produces：`static/ui/index.html` 与 `static/ui/assets/*-[hash].js|css`（后续任务的落点）；`scripts/build-ui.sh`（唯一重建入口，后续每个改前端的任务都要跑它再提交）；`UiDistIntegrityTest`（T11 会往里加断言，保持同一个类）。

- [ ] **Step 1 先写红的门禁测试**（这一步在任何前端代码之前：产物不存在时它必须失败）

```java
package com.example.marketing.admin.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 的产物门禁。这个测试**不连库、不起 Spring 上下文**——它只回答一个问题：
 * jar 里的后台界面是不是自洽的（索引在、它引用的指纹文件也都在）。
 * 一个连不上的库不会让白屏后台变得更容易发现，但会让这条断言变成"跳过"。
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
        assertTrue(m.find(), "索引页里没有 /ui/assets/*.js 入口，前端源码或 base 配置漂了：" + html);
        assertTrue(new ClassPathResource("static/" + m.group(1)).exists(),
                "索引页引用了 " + m.group(1) + " 但仓库里没有这个文件——半提交的 dist 会打出白屏 jar");
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-admin test -Dtest=UiDistIntegrityTest`
Expected: FAIL —— `static/ui/index.html 不在 classpath`

- [ ] **Step 2 前端骨架**

`marketing-admin-ui/package.json`（版本号写范围，`npm install` 解析出的精确值记进本计划的执行记录）：

```json
{
  "name": "marketing-admin-ui",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "element-plus": "^2.8.0",
    "pinia": "^2.2.0",
    "vue": "^3.5.0",
    "vue-router": "^4.4.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.0",
    "@vue/test-utils": "^2.4.0",
    "jsdom": "^25.0.0",
    "unplugin-auto-import": "^0.18.0",
    "unplugin-vue-components": "^0.27.0",
    "vite": "^5.4.0",
    "vitest": "^2.1.0"
  }
}
```

`marketing-admin-ui/vite.config.js`：

```js
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// outDir 直接指向 admin 的 classpath：⑥ 的产物是入仓文件，构建即落位，
// 不需要 maven 去装 node（spec §3）。emptyOutDir 必须开——
// 留着上一次的 assets/*.js，index.html 就不引用它们了，但 jar 里那一层还在。
export default defineConfig({
  base: '/ui/',
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: '../marketing-admin/src/main/resources/static/ui',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8090' },
  },
  test: { environment: 'jsdom' },
})
```

`marketing-admin-ui/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>营销平台后台</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>
```

`marketing-admin-ui/src/main.js`：

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
```

`marketing-admin-ui/src/App.vue`：

```vue
<template>
  <router-view />
</template>
```

`marketing-admin-ui/src/router/index.js`：

```js
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'

// history 基座：spec §5 的 fallback 由 admin 侧的 ResourceHandler 负责，
// 网关只按 /ui/** 转发，不懂前端路由。
const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [{ path: '/', name: 'home', component: HomeView }],
})

export default router
```

`marketing-admin-ui/src/views/HomeView.vue`：

```vue
<template>
  <main>
    <h1>营销平台后台</h1>
    <p data-testid="stage">骨架已就位，页面在 T4-T10 逐个进来。</p>
  </main>
</template>
```

- [ ] **Step 3 构建入口脚本**

`scripts/build-ui.sh`：

```bash
#!/usr/bin/env bash
# ============================================================
# ⑥ 唯一的前端构建入口：装依赖 → vite build → 注入构建指纹 → 报告体积
#   产物 marketing-admin/src/main/resources/static/ui/** 是**入仓文件**，
#   所以改了 .vue 没跑这里 = jar 里还是旧界面（UiDistIntegrityTest 挡一半，
#   check-ui-dist.sh 挡另一半，见 T11）。
# 用法：./scripts/build-ui.sh            首次或依赖变了（会 npm install）
#       SKIP_INSTALL=1 ./scripts/build-ui.sh   只重构建
# 前置：本机 node ≥ 18（实测 v22.22.3 + npm 10.9.8）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

UI="$PWD/marketing-admin-ui"
OUT="$PWD/marketing-admin/src/main/resources/static/ui"

if ! command -v node >/dev/null 2>&1; then
  echo "!! 没找到 node：⑥ 的产物需要一次本机构建（不装进 maven 生命周期，见 spec §10）" >&2
  exit 1
fi

cd "$UI"
if [ "${SKIP_INSTALL:-0}" = "1" ] && [ -d node_modules ]; then
  echo "==> 跳过 npm install"
else
  echo "==> npm install（首次会拉 Element Plus 与 vite，几分钟）"
  npm install --no-audit --no-fund
fi

echo "==> vite build"
npm run build

# 指纹注释：让"jar 里的界面是哪棵树的哪个时刻"能在浏览器里直接看出来。
# 没有这一行，产物漂了只能靠比对文件时间猜。
REV=$(git rev-parse --short HEAD)
DIRTY=$(git status --porcelain -- marketing-admin-ui | head -1)
[ -n "$DIRTY" ] && REV="$REV-dirty"
BUILD_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
perl -0pi -e "s{</head>}{"}{'<!-- build-ui: rev=$REV at=$BUILD_AT -->\n  </head>}s" "$OUT/index.html"

FILES=$(find "$OUT" -type f | wc -l | tr -d ' ')
BYTES=$(du -sk "$OUT" | cut -f1)
echo "==> 产物：$FILES 个文件，${BYTES} KiB（未压缩）→ $OUT"
grep -n "build-ui" "$OUT/index.html" || { echo "!! 指纹没注进去：index.html 形状变了，检查 vite 的 html 模板" >&2; exit 1; }
```

`.gitignore` 追加：

```
marketing-admin-ui/node_modules/
marketing-admin-ui/dist/
```

- [ ] **Step 4 跑构建，确认产物落位**

```bash
chmod +x scripts/build-ui.sh
./scripts/build-ui.sh
ls -la marketing-admin/src/main/resources/static/ui
```
Expected：`==> 产物：N 个文件，K KiB（未压缩）`、`index.html` 里含 `<!-- build-ui: rev=... -->`、`assets/` 下有 `index-<hash>.js` 与 `.css`。**把精确的 node/npm/依赖版本与这一步的 N/K 抄进"执行记录"。**

- [ ] **Step 5 跑门禁测试确认变绿**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin test -Dtest=UiDistIntegrityTest`
Expected: PASS

- [ ] **Step 6 变异检查（必须做，不许省）**

把 `vite.config.js` 的 `base` 临时改成 `'/app/'` → `SKIP_INSTALL=1 ./scripts/build-ui.sh` → 测试**必须红**在"`src=\"/ui/assets/...\"` 匹配不到"。改回 `'/ui/'` 重构建，回绿。红过才继续。

- [ ] **Step 7 全仓回归 + 提交**

```bash
source scripts/common.sh && mvn -q install
git add marketing-admin-ui .gitignore scripts/build-ui.sh marketing-admin/src/main/resources/static/ui marketing-admin/src/test/java/com/example/marketing/admin/web/UiDistIntegrityTest.java
git commit -m "feat(ui): ⑥ 前端骨架与唯一构建入口（dist 入仓 + 门禁测试）"
```

---

### Task 2: admin 侧静态资源、history fallback、缓存头与 CSP

**Files**
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/web/UiResourceSupport.java`
- Create: `marketing-admin/src/main/java/com/example/marketing/admin/web/UiWebMvcConfig.java`
- Test: `marketing-admin/src/test/java/com/example/marketing/admin/web/UiResourceSupportTest.java`

**Interfaces**
- Consumes：T1 的 `static/ui/index.html` + `static/ui/assets/**`。
- Produces：`UiResourceSupport.INDEX_LOCATION`（`"ui/index.html"`）、`UiResourceSupport.shouldFallbackToIndex(String requestPath)`、`UiResourceSupport.applyTo(HttpServletResponse, String requestPath)`，以及 `UiWebMvcConfig`（含 `uiHeaderFilter` bean）——T11/T12 与 README 都按这几个名字引用。

> 为什么把判定抽成一个纯类：MVC 的 `PathResourceResolver` 只有在真实 servlet 上下文里才跑得起来，
> 而这段逻辑里**唯一会伤到人的**就是"什么该回退 index、什么该 404、什么该 no-store"这三个判断。
> 把它们做成可单测的纯函数，配置类只剩接线；不然只能起整个 web 上下文去测一个 if。

- [ ] **Step 1 写红的测试**

```java
package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class UiResourceSupportTest {

    private static String header(HttpServletResponse res, String name) {
        return res.getHeader(name);
    }

    @Test
    void 索引页必须no_store因为指纹文件名一变旧索引就会404() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/");
        assertEquals("no-store", header(res, "Cache-Control"),
                "索引页可缓存 = 升级后台后用户拿着旧索引去要新指纹，白屏");
    }

    @Test
    void 指纹资源给一年immutable而缺文件不许被回退成200一份HTML() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        UiResourceSupport.applyTo(res, "/ui/assets/index-Cq7fZ2.js");
        String cc = header(res, "Cache-Control");
        assertTrue(cc.contains("max-age=31536000"), "指纹资源应给一年期");
        assertTrue(cc.contains("immutable"));

        // 回退判据：只有"看起来是前端路由"才回 index。
        // assets/ 与任何带扩展名的路径必须 404 —— js 404 拿到一份 HTML 时
        // 浏览器报 "Failed to load module script"，排查方向会被带去查构建而不是查缺文件。
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
        String csp = header(res, "Content-Security-Policy");
        assertNotNull(csp, "没有 CSP：⑥ 的 XSS 兜底只剩 900s TTL（spec §7 说这是三层里的一层）");
        assertTrue(csp.contains("script-src 'self'"));
        assertTrue(csp.contains("object-src 'none'"));
        assertTrue(csp.contains("base-uri 'self'"));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-admin test -Dtest=UiResourceSupportTest`
Expected: 编译失败（类不存在）。若报 `MockHttpServletResponse` 找不到，加 `spring-test` 到 admin 的 test scope——它今天已经有（`@SpringBootTest` 类的装配测试在用），别改成手写 stub。

- [ ] **Step 2 实现纯判定类**

```java
package com.example.marketing.admin.web;

import jakarta.servlet.http.HttpServletResponse;

/**
 * ⑥ 的"该回退吗 / 该怎么缓存"判定。刻意与 {@code WebMvcConfigurer} 分开：
 * 前者是可单测的纯函数（MockHttpServletResponse 就够），后者只负责把 Spring MVC
 * 的钩子接到它上面。这三个判断里任何一个写错都会伤到人，而它们在 MVC 里没法便宜地测。
 */
public final class UiResourceSupport {

    /** classpath 下的索引页位置（static/ 前缀由 ResourceHandler 负责） */
    public static final String INDEX_LOCATION = "ui/index.html";

    private static final String PREFIX = "/ui/";

    private UiResourceSupport() {
    }

    /** 请求路径要不要回退到 index.html（history 路由） */
    public static boolean shouldFallbackToIndex(String requestPath) {
        if (requestPath == null || requestPath.contains("..")) {
            return false;
        }
        String tail = requestPath.startsWith(PREFIX) ? requestPath.substring(PREFIX.length()) : requestPath;
        if (tail.isEmpty()) {
            return true;
        }
        if (tail.startsWith("assets/")) {
            return false;
        }
        return !tail.contains(".");
    }

    /** 只管头，不管路由：由 /ui/** 上的一个 filter 调用（见 UiWebMvcConfig） */
    public static void applyTo(HttpServletResponse res, String requestPath) {
        res.setHeader("Content-Security-Policy", "script-src 'self'; object-src 'none'; base-uri 'self'");
        String tail = requestPath != null && requestPath.startsWith(PREFIX)
                ? requestPath.substring(PREFIX.length()) : requestPath;
        if (tail != null && tail.startsWith("assets/")) {
            res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            res.setHeader("Cache-Control", "no-store");
        }
    }
}
```

- [ ] **Step 3 接线成配置类**

```java
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
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * ⑥ 的静态资源面。放 admin 而不是网关：网关不该懂前端路由（spec §5）。
 * 五套入口都不用它单独配任何东西——LITE/dev 下这段随 admin 模块进 standalone，
 * 网关的 ui-route 只是把同一对 ADMIN_HOST/ADMIN_PORT 换个值。
 *
 * <p>缓存头刻意由 {@link UiHeaderFilter} 独占，不用 {@code ResourceHandlerRegistry
 * .setCacheControl(...)}：后者会在 handler 里再写一次 {@code Cache-Control}，
 * 把 filter 给指纹资源设的 {@code immutable} 覆盖成 no-store——一个"看起来更保守"
 * 的默认值，实际让每发 assets 请求都回源。两处只有一个能拥有这个头。</p>
 */
@Configuration(proxyBeanMethods = false)
public class UiWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
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

    /** 只写头，不改路由。判定在 UiResourceSupport 里，那里有单测 */
    static class UiHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            UiResourceSupport.applyTo(res, req.getRequestURI());
            chain.doFilter(req, res);
        }
    }
}
```

> LITE/dev 下 admin 与四个业务模块同在 standalone：这段配置由谁装配过一遍要确认——
> `UiWebMvcConfig` 在 `com.example.marketing.admin.web` 包，而 standalone 扫的是
> `com.example.marketing`（见 `MarketingStandaloneApplication`）。Step 5 的直连 8085 实测
> 就是这条装配边界的验证；如果 8085 上没有 `/ui/`，先看扫描边界再怀疑前端。

- [ ] **Step 4 跑测试到绿**

Run: `source scripts/common.sh && mvn -q -pl marketing-admin test -Dtest='UiResourceSupportTest,UiDistIntegrityTest'`
Expected: PASS

- [ ] **Step 5 真栈实测（不能只看单测）**

```bash
./scripts/deploy-preview.sh                      # LITE
T=$(curl -s -X POST http://127.0.0.1:8090/api/admin/auth/login -H 'Content-Type: application/json' \
   -d '{"username":"admin","password":"rootdev123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
ASSET=$(grep -o '/ui/assets/[^"]*\.js' marketing-admin/src/main/resources/static/ui/index.html | head -1)
curl -is http://127.0.0.1:8090/ui/ | grep -iE 'HTTP/|cache-control|content-security-policy'
curl -is "http://127.0.0.1:8090$ASSET" | grep -iE 'HTTP/|cache-control'
curl -is http://127.0.0.1:8090/ui/audits | head -1
curl -is http://127.0.0.1:8090/ui/nope.js | grep -iE 'HTTP/'
```
Expected（网关改动在 T3，这一步先经 8085 直连）：`/ui/` 200 + `no-store` + CSP；`$ASSET` 200 + `immutable`；`/ui/audits` 200（回退生效，但此时 404 也算预期内，T3 之后再看）；`/ui/nope.js` **404**。
> 直连 8085 时把上面 URL 的 `:8090` 换成 `:8085`。

- [ ] **Step 6 变异检查**

`shouldFallbackToIndex` 里 `return !tail.contains(".")` 改成 `return true` → 测试必须红在 `/ui/assets/...` 那条（"js 404 变 200 HTML"）。回绿后再提交。

- [ ] **Step 7 提交**

```bash
source scripts/common.sh && mvn -q install
git add marketing-admin/src/main/java/com/example/marketing/admin/web marketing-admin/src/test/java/com/example/marketing/admin/web
git commit -m "feat(ui): ⑥ 静态资源与 history fallback 长在 admin，索引 no-store/指纹 immutable + CSP"
```

---

### Task 3: 网关 ui-route（两套 profile）+ 白名单 + 限流桶 + 配置断言

**Files**
- Modify: `marketing-gateway/src/main/resources/application.yml:27-68`（local routes 段）、`:77-78`（whitelist）、`:84-116`（rate-limit map）、`:148-178`（nacos routes 段）
- Test: `marketing-gateway/src/test/java/com/example/marketing/gateway/config/GatewayUiRouteConfigTest.java`

**Interfaces**
- Produces：route id `ui-route`（限流 key `gateway.ratelimit.ui-route.limit` 因此在 ⑤ 的声明范围内可选，本段不新增声明）、白名单项 `/ui/**`。

- [ ] **Step 1 写红的配置测试**

```java
package com.example.marketing.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑥ 的路由面断言。为什么读 yml 而不是起上下文：
 * ③/⑤ 的教训是"local 改了 nacos 没改"这种病只在升档后才暴露，
 * 而一次 ApplicationContextRunner 只装配一个 profile，看不见"两段都写了没有"这件事。
 * 直接按 `---` 分文档逐段断言，才是这条病的探针。
 * （application.yml 实测两份文档：默认段 1-135 行、nacos 段 136 行起。）
 */
class GatewayUiRouteConfigTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object doc : new Yaml().loadAll(new ClassPathResource("application.yml").getInputStream()).toList()) {
            if (doc instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routesOf(Map<String, Object> doc) {
        return (List<Map<String, Object>>) ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) doc.get("spring")).get("cloud")).get("gateway")
        ).get("routes");
    }

    private static boolean hasUiRoute(List<Map<String, Object>> routes) {
        return routes.stream().anyMatch(r -> "ui-route".equals(r.get("id"))
                && String.valueOf(r.get("predicates")).contains("/ui/**"));
    }

    @Test
    void local与nacos两段都要有ui_route_漏一段就是只在升档后红() throws Exception {
        List<Map<String, Object>> docs = documents();
        assertEquals(2, docs.size(), "文档数变了，这份探针的分段依据就不成立了（先看 yml 是不是加了第三段）");
        assertTrue(hasUiRoute(routesOf(docs.get(0))), "默认(local)段没有 ui-route");
        assertTrue(String.valueOf(docs.get(1)).contains("nacos"), "第二段不是 nacos 段，断言会指错地方");
        assertTrue(hasUiRoute(routesOf(docs.get(1))),
                "nacos 段缺 ui-route：LITE 一切正常，FULL 的 /ui 整片 404");
    }

    @Test
    void ui路径必须在白名单里否则静态资源会要C端token() throws Exception {
        List<String> whitelist = gatewaySection().get("whitelist") instanceof List<?> l
                ? (List<String>) l : List.of();
        assertTrue(whitelist.contains("/ui/**"),
                "/ui/** 不在白名单：AuthFilter 会按 C 端口径要 demo token，页面打不开");
    }

    @Test
    void ui_route必须在限流map里因为不在map里等于完全不限流() throws Exception {
        Map<String, Object> rl = (Map<String, Object>) gatewaySection().get("rate-limit");
        assertTrue(rl.containsKey("ui-route"),
                "route id 不在 rate-limit map 里 = 不限流且不报错（RateLimitFilter 的语义）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gatewaySection() throws Exception {
        Map<String, Object> mkt = (Map<String, Object>) documents().get(0).get("marketing");
        return (Map<String, Object>) ((Map<String, Object>) mkt.get("gateway"));
    }
}
```

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway test -Dtest=GatewayUiRouteConfigTest`
Expected: 三条全红

- [ ] **Step 2 改 yml**

local routes 段末尾（紧跟 `admin-route` 之后）：

```yaml
        # ⑥ 的后台界面。必须与 admin-route 用同一对占位符：LITE 下 compose 把它们指到
        # standalone:8085，FULL 进程档指到 127.0.0.1:8086，容器档指到 marketing-admin:8086。
        # 单独写死一个地址 = 五套入口里总有一套打不开界面。
        - id: ui-route
          uri: http://${ADMIN_HOST:127.0.0.1}:${ADMIN_PORT:8086}
          predicates:
            - Path=/ui/**
```

nacos routes 段末尾：

```yaml
        - id: ui-route
          uri: lb://marketing-admin
          predicates: [ Path=/ui/** ]
```

whitelist：

```yaml
    whitelist:
      - /actuator/**
      # ⑥ 的静态资源。它们本身不含任何数据（真数据仍要 admin token），
      # 不进白名单的后果是"打开后台先要有一个 C 端 demo token"——荒谬且难排查。
      - /ui/**
```

rate-limit map 末尾：

```yaml
      # 首屏十几个请求（js/css/字体），100/s 掐不到正常打开，
      # 但挡住了"把后台 jar 当文件服务器刷"。
      ui-route:
        limit: ${RL_UI:100}
        window-seconds: 1
```

- [ ] **Step 3 跑测试到绿**

Run: `source scripts/common.sh && mvn -q -pl marketing-gateway test -Dtest=GatewayUiRouteConfigTest`
Expected: PASS ×3

- [ ] **Step 4 真栈实测 LITE 与 FULL 两种装配**

```bash
./scripts/deploy-preview.sh
curl -is http://127.0.0.1:8090/ui/ | head -3         # 期望 200 + no-store
docker exec mkt-preview-gateway getent hosts marketing-admin || true   # 只是看名字解析，不影响 LITE
```
然后起 FULL 容器档（`./scripts/stop-preview.sh && export ADMIN_JWT_SECRET=$(cat .admin-jwt-secret) && SKIP_BUILD=1 ./scripts/deploy-full.sh`）再打一次 `curl -is http://127.0.0.1:8090/ui/`。
Expected: 两档都 200。**FULL 容器档必须单独看一眼**——那是 `lb://marketing-admin` 这条路唯一被验的地方。

- [ ] **Step 5 变异检查**

把 nacos 段的 `ui-route` 整条注释掉 → 第一条断言必须红（"只在升档后红"这条病被探针抓到）。恢复。
再把 `${RL_UI:100}` 那三行删掉 → 第三条必须红。恢复。

- [ ] **Step 6 全仓回归 + 提交**

```bash
source scripts/common.sh && mvn -q install
git add marketing-gateway/src/main/resources/application.yml marketing-gateway/src/test/java/com/example/marketing/gateway/config/GatewayUiRouteConfigTest.java
git commit -m "feat(gateway): ⑥ ui-route 两套 profile + /ui 白名单 + 独立限流桶"
```

---

### Task 4: 前端基座（api 客户端 / 会话 store / 路由守卫 / 登录页）+ vitest

**Files**
- Create: `marketing-admin-ui/src/api/client.js`
- Create: `marketing-admin-ui/src/stores/session.js`
- Create: `marketing-admin-ui/src/router/index.js`（改写：守卫 + 路由表骨架）
- Create: `marketing-admin-ui/src/views/LoginView.vue`
- Create: `marketing-admin-ui/src/AppLayout.vue`
- Test: `marketing-admin-ui/test/client.spec.js`
- Test: `marketing-admin-ui/test/session.spec.js`

**Interfaces**
- Consumes：`POST /api/admin/auth/login` → `{code,message,data:{token,expiresInSeconds,...},success}`；`GET /api/admin/auth/me`。
- Produces：`api.get/post/put/del(path, body?)` → resolve 后端 `data`，非 0 码抛 `ApiError{code,message,payload}`；`SESSION_KEY='mkt.admin.token'`、`useSession()`（`token`、`role`、`me`、`expiresAt`、`login()`、`logout()`、`secondsLeft()`）；`ApiError` 的码常量 `E.UNAUTHORIZED=40100 / E.EXPIRED=40101 / E.REVOKED=40102 / E.FORBIDDEN=40300 / E.CONFLICT=41008 / E.NOT_BROADCAST=41009 / E.NOT_APPLICABLE=41010 / E.THROTTLED=42900`。

- [ ] **Step 1 装 vitest 并写红的测试**

```bash
cd marketing-admin-ui && npx vitest --version   # 记下解析到的精确版本 → 执行记录
```

`marketing-admin-ui/test/client.spec.js`：

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { api, ApiError, E } from '@/api/client'
import { SESSION_KEY } from '@/api/client'

function respond(body, status = 200) {
  global.fetch = vi.fn().mockResolvedValue({
    status,
    json: async () => body,
    headers: { get: (k) => (k === 'Retry-After' ? '31' : null) },
  })
}

beforeEach(() => localStorage.clear())

describe('api client', () => {
  it('带上 admin token：Authorization 是唯一识别路径', async () => {
    localStorage.setItem(SESSION_KEY, 'T1')
    respond({ code: 0, data: { ok: 1 }, success: true })
    await expect(api.get('/api/admin/auth/me')).resolves.toEqual({ ok: 1 })
    const [, opt] = global.fetch.mock.calls[0]
    expect(opt.headers.Authorization).toBe('Bearer T1')
  })

  it('业务码非 0 要抛 ApiError 并把整份响应带着（40000 的 message 里有允许区间）', async () => {
    respond({ code: 40000, message: '阈值必须在 [1, 100000] 之间', success: false })
    const err = await api.put('/api/admin/config', {}).then(() => null, (e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe(40000)
    expect(err.message).toContain('[1, 100000]')
  })

  it('三个 401 类码分处：过期跳登录可回原页、吊销不回原页、无凭证只清 token', async () => {
    const actions = []
    respond({ code: E.EXPIRED, message: 'token 过期', success: false })
    await api.get('/x').catch((e) => actions.push(e.action))
    respond({ code: E.REVOKED, message: '会话已作废', success: false })
    await api.get('/x').catch((e) => actions.push(e.action))
    respond({ code: E.UNAUTHORIZED, message: '未认证', success: false })
    await api.get('/x').catch((e) => actions.push(e.action))
    expect(actions).toEqual(['login-redirect', 'login-strict', 'clear'])
  })

  it('被吊销的会话绝不自动重放：那一发可能已经落库了', async () => {
    respond({ code: E.REVOKED, message: 'x', success: false })
    const spy = vi.fn()
    global.fetch = spy
    await api.get('/x').catch(() => {})
    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('42900 把 Retry-After 带出来给界面显示倒计时', async () => {
    respond({ code: E.THROTTLED, message: '太频繁', success: false }, 429)
    const err = await api.get('/x').then(() => null, (e) => e)
    expect(err.retryAfterSeconds).toBe(31)
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run`
Expected: FAIL（`@/api/client` 不存在）

- [ ] **Step 2 实现 client**

`marketing-admin-ui/src/api/client.js`：

```js
export const SESSION_KEY = 'mkt.admin.token'

export const E = {
  BAD_REQUEST: 40000,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  UNAUTHORIZED: 40100,
  EXPIRED: 40101,
  REVOKED: 40102,
  THROTTLED: 42900,
  BUDGET: 41000,
  STATE: 41001,
  CONFLICT: 41008,
  NOT_BROADCAST: 41009,
  NOT_APPLICABLE: 41010,
}

export class ApiError extends Error {
  constructor(code, message, payload, action, retryAfterSeconds) {
    super(message)
    this.code = code
    this.payload = payload
    this.action = action
    this.retryAfterSeconds = retryAfterSeconds
  }
}

// 鉴权失败的三种码必须走三条路（spec §7）：
// 过期是"回来原页面"，吊销是"到此为止"，无凭证是"没什么可清的了"。
function authAction(code) {
  if (code === E.EXPIRED) return 'login-redirect'
  if (code === E.REVOKED) return 'login-strict'
  if (code === E.UNAUTHORIZED) return 'clear'
  return null
}

async function request(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem(SESSION_KEY)
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    /* 网关 502/空响应：走下面的 HTTP 分支 */
  }
  const code = payload ? payload.code : res.status * 100
  if (payload && payload.success && code === 0) return payload.data
  const action = authAction(code)
  if (action) localStorage.removeItem(SESSION_KEY)
  const retryAfter = res.headers ? Number(res.headers.get('Retry-After')) : NaN
  throw new ApiError(
    code,
    (payload && payload.message) || `HTTP ${res.status}`,
    payload,
    action,
    Number.isFinite(retryAfter) ? retryAfter : undefined,
  )
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b) => request('POST', p, b ?? {}),
  put: (p, b) => request('PUT', p, b ?? {}),
  del: (p) => request('DELETE', p),
}
```

- [ ] **Step 3 会话 store + 路由守卫 + 登录页 + 布局**

`marketing-admin-ui/src/stores/session.js`：

```js
import { defineStore } from 'pinia'
import { api, SESSION_KEY } from '@/api/client'

const EXPIRES_KEY = 'mkt.admin.expiresAt'

export const useSession = defineStore('session', {
  state: () => ({
    token: localStorage.getItem(SESSION_KEY) || '',
    expiresAt: Number(localStorage.getItem(EXPIRES_KEY) || 0),
    me: null,
  }),
  getters: {
    authed: (s) => !!s.token,
    role: (s) => (s.me ? s.me.role : ''),
    // 角色只用来灰化按钮，不是安全边界（后端已经判过；这里只是别让运营点了才知道不行）
    canWrite: (s) => !!s.me && s.me.role === 'admin',
    canOperate: (s) => !!s.me && (s.me.role === 'admin' || s.me.role === 'operator'),
  },
  actions: {
    secondsLeft(now = Date.now()) {
      return Math.max(0, Math.round((this.expiresAt - now) / 1000))
    },
    async login(username, password) {
      const d = await api.post('/api/admin/auth/login', { username, password })
      this.token = d.token
      // expiresInSeconds 是后端给的（AdminProperties.accessTtlSeconds=900）。
      // 前端不自己发明 TTL，否则倒计时与真实过期对不上，用户会以为"还早"却被 40101。
      this.expiresAt = Date.now() + d.expiresInSeconds * 1000
      localStorage.setItem(SESSION_KEY, d.token)
      localStorage.setItem(EXPIRES_KEY, String(this.expiresAt))
      this.me = d
      return d
    },
    async whoAmI() {
      this.me = await api.get('/api/admin/auth/me')
      return this.me
    },
    async logout() {
      try {
        await api.post('/api/admin/auth/logout')
      } finally {
        this.clear()
      }
    },
    clear() {
      this.token = ''
      this.expiresAt = 0
      this.me = null
      localStorage.removeItem(SESSION_KEY)
      localStorage.removeItem(EXPIRES_KEY)
    },
  },
})
```

`marketing-admin-ui/src/router/index.js`（替换 T1 的骨架版）：

```js
import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '@/stores/session'

const views = (name) => () => import(`@/views/${name}.vue`)

const routes = [
  { path: '/login', name: 'login', component: views('LoginView'), meta: { public: true } },
  { path: '/', redirect: '/ops' },
  { path: '/ops', name: 'ops', component: views('OpsView'), meta: { title: '运维大盘' } },
  { path: '/config', name: 'config', component: views('ConfigView'), meta: { title: '在线配置' } },
  { path: '/activities', name: 'activities', component: views('ActivitiesView'), meta: { title: '活动' } },
  { path: '/coupons', name: 'coupons', component: views('CouponsView'), meta: { title: '券模板' } },
  { path: '/rules', name: 'rules', component: views('RulesView'), meta: { title: '优惠规则' } },
  { path: '/seckill', name: 'seckill', component: views('SeckillView'), meta: { title: '秒杀活动' } },
  { path: '/cache', name: 'cache', component: views('CacheView'), meta: { title: '缓存重预热', roles: ['admin', 'operator'] } },
  { path: '/users', name: 'users', component: views('UsersView'), meta: { title: '账号' } },
  { path: '/sessions', name: 'sessions', component: views('SessionsView'), meta: { title: '在线会话' } },
  { path: '/audits', name: 'audits', component: views('AuditsView'), meta: { title: '审计' } },
]

const router = createRouter({ history: createWebHistory('/ui/'), routes })

router.beforeEach(async (to) => {
  const s = useSession()
  if (to.meta.public) return true
  if (!s.authed) return { name: 'login', query: { next: to.fullPath } }
  if (!s.me) {
    try {
      await s.whoAmI()
    } catch (e) {
      if (e.action === 'login-strict') return { name: 'login' }
      return { name: 'login', query: { next: to.fullPath } }
    }
  }
  if (to.meta.roles && !to.meta.roles.includes(s.role)) return { name: 'ops' }
  return true
})

export default router
```

`marketing-admin-ui/src/views/LoginView.vue`：

```vue
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSession } from '@/stores/session'
import { E } from '@/api/client'

const s = useSession()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const cooldown = ref(0)
let ticker = null

// 倒计时用后端给的 expiresInSeconds，不自己发明 TTL（spec §7）
const left = computed(() => s.secondsLeft())
onMounted(() => {
  ticker = setInterval(() => {
    if (s.authed && left.value <= 60) s.clear()
  }, 1000)
})
onUnmounted(() => clearInterval(ticker))

async function submit() {
  busy.value = true
  try {
    await s.login(username.value, password.value)
    await s.whoAmI()
    router.replace(route.query.next || '/ops')
  } catch (e) {
    if (e.code === E.THROTTLED) {
      // LoginGuard 每 IP 10 次/分钟且含成功尝试。不显示还要等多久，
      // 运营只会以为"口令错了/后台坏了"，然后继续点，把窗口一直续上。
      cooldown.value = e.retryAfterSeconds || 60
      ElMessage.warning(`登录过于频繁，还有 ${cooldown.value} 秒可再试`)
    } else if (e.code === E.UNAUTHORIZED) {
      // 40100 的文案在后端已经把"账号不存在"与"口令错"合成同一句，别在这里猜
      ElMessage.error(e.message)
    } else {
      ElMessage.error(e.message || '登录失败')
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login">
    <h1>营销平台后台</h1>
    <form @submit.prevent="submit">
      <input v-model="username" autocomplete="username" placeholder="账号" />
      <input v-model="password" type="password" autocomplete="current-password" placeholder="口令" />
      <button :disabled="busy || cooldown > 0" type="submit">
        {{ cooldown > 0 ? `请等待 ${cooldown}s` : '登录' }}
      </button>
    </form>
    <p v-if="s.authed && left > 0" data-testid="countdown">本次会话剩余 {{ left }}s</p>
  </main>
</template>
```

`marketing-admin-ui/src/AppLayout.vue`：

```vue
<script setup>
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed } from 'vue'
import { useSession } from '@/stores/session'

const s = useSession()
const route = useRoute()
const NAV = [
  { to: '/ops', label: '运维大盘' },
  { to: '/config', label: '在线配置', write: true },
  { to: '/activities', label: '活动', write: true },
  { to: '/coupons', label: '券模板', write: true },
  { to: '/rules', label: '优惠规则', write: true },
  { to: '/seckill', label: '秒杀活动', write: true },
  { to: '/cache', label: '缓存重预热', operate: true },
  { to: '/users', label: '账号' },
  { to: '/sessions', label: '在线会话' },
  { to: '/audits', label: '审计' },
]
const visible = computed(() =>
  NAV.filter((n) => (n.write ? s.canWrite : n.operate ? s.canOperate : true)))
</script>

<template>
  <div class="shell">
    <aside>
      <RouterLink to="/ops" class="brand">营销平台后台</RouterLink>
      <nav>
        <RouterLink v-for="n in visible" :key="n.to" :to="n.to"
                    :class="{ on: route.path === n.to }">{{ n.label }}</RouterLink>
      </nav>
      <footer>
        <!-- 页脚的角色与"会话还剩多久"是运营唯一能看到自己是不是只读的地方。
             灰化入口只是省事，真正的判定在后端——别把它当权限。 -->
        <p data-testid="role">{{ s.me?.role || '未登录' }} · 剩余 {{ s.secondsLeft() }}s</p>
        <button @click="s.logout()">退出</button>
      </footer>
    </aside>
    <section class="content"><RouterView /></section>
  </div>
</template>
```

`App.vue` 相应改成 `<router-view />` 之外不再包任何东西（登录页与布局各自决定外壳）。
T5-T10 的每个页面都挂在 `AppLayout` 之下：把 T4 的路由表里除 `login` 外的每条都改成
`children` 形式挂在 `component: AppLayout` 的父路由下（一步到位，避免每个任务再改一次 router）。

- [ ] **Step 4 跑测试到绿 + 变异检查**

Run: `cd marketing-admin-ui && npx vitest run` → PASS ×5。
变异：把 `authAction` 里 `REVOKED` 也映射成 `login-redirect` → 第三条必须红；把"抛错前重试一发"这种"贴心"代码加进去 → 第四条必须红（证明它挡得住自动重放）。

- [ ] **Step 5 构建 + 全仓回归 + 提交**

```bash
SKIP_INSTALL=1 ./scripts/build-ui.sh
source scripts/common.sh && mvn -q install
git add marketing-admin-ui/src marketing-admin-ui/test marketing-admin/src/main/resources/static/ui
git commit -m "feat(ui): ⑥ 前端基座（api 客户端三码分处 + 会话倒计时 + 路由守卫）"
```

---

### Task 5: 列表基座组件 + 运维大盘页（④ 读数的界面化）

**Files**
- Create: `marketing-admin-ui/src/components/PagedTable.vue`
- Create: `marketing-admin-ui/src/components/TriState.vue`
- Create: `marketing-admin-ui/src/views/OpsView.vue`
- Test: `marketing-admin-ui/test/tristate.spec.js`

**Interfaces**
- Consumes：`GET /api/admin/ops` → `OpsSnapshotView{mode,ownForm,takenAt,targets[],backlog{liveness{...}},consistency[],metrics[],liveness{},audit{},notes[]}`；`GET /api/admin/{activities,coupon/templates,discount/rules,seckill/activities}` 的 `PageResult`。
- Produces：`PagedTable`（props：`endpoint`、`columns`、`pageSize`；expose：`reload()`）、`TriState`（props：`value`、`applicable`）——T6-T10 的每个列表页都消费 `PagedTable`。

- [ ] **Step 1 写红的 TriState 测试（④ 的纪律在界面上的投影）**

```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TriState from '@/components/TriState.vue'

describe('TriState', () => {
  it('-1 是"判定不了"，绝不能显示成 0', () => {
    const w = mount(TriState, { props: { value: -1, applicable: true } })
    expect(w.text()).toContain('判定不了')
    expect(w.text()).not.toMatch(/(^|[^.\d])0([^.\d]|$)/)
  })
  it('applicable=false 显示"本形态不适用"，而不是空着', () => {
    const w = mount(TriState, { props: { value: -1, applicable: false } })
    expect(w.text()).toContain('不适用')
  })
  it('真 0 才显示 0', () => {
    const w = mount(TriState, { props: { value: 0, applicable: true } })
    expect(w.text()).toBe('0')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/tristate.spec.js` → FAIL（组件不存在）

- [ ] **Step 2 实现三个组件**

`TriState.vue`：`value < 0 && applicable` → "判定不了"（灰底 + `title` 说明"④ 读不到，不等于没问题"）；`!applicable` → "不适用"；否则显示数值，`> 0` 时红。**不许把 -1 归一化。**

`PagedTable.vue`：`GET endpoint?page=&size=`，解 `PageResult`（`records/total/page/size`），`columns` 是 `[{prop,label,width?,formatter?}]`；加载中/失败都要占位，失败时把 `err.message` 原样显示（40000 的 message 里有允许区间，藏起来就等于让人瞎猜）。

`OpsView.vue`：按 `mode` 显示"这一盘读的是谁"，`targets` 表（name/url/source/status/样本数），`backlog.schemas` 表 + 合计（任一 `UNKNOWN` → 合计也显示"判定不了"，与后端同一口径），`backlog.streams` 用 `TriState`，`consistency` 三行 + note，`liveness.processes` 三态（`null` → "本档不适用"），`audit` 摘要，`notes` 原文列出。**页面上不出现任何写按钮**（④ 只读，spec §5 的边界）。

- [ ] **Step 3 测试到绿 + 变异检查**

`npx vitest run` → PASS ×3。变异：把 `-1` 归一化成 0 → 第一条必须红。

- [ ] **Step 4 真浏览器验证（第一处，用 browser-use）**

LITE 起栈后打开 `http://127.0.0.1:8090/ui/`：登录 → 大盘截图。核对三件事：`mode` 与 target 列表和 `curl /api/admin/ops` 一致；`MKT_STREAM_*` 在 LITE 显示数字、RocketMQ 说明只在 FULL 出现（换档验一次）；`consistency` 三条都是 0 或非 0 与后端逐字一致。**不一致就停下来修，别继续堆页面。**

- [ ] **Step 5 构建 + 回归 + 提交**

```bash
SKIP_INSTALL=1 ./scripts/build-ui.sh && source scripts/common.sh && mvn -q install
git add marketing-admin-ui/src/components marketing-admin-ui/src/views/OpsView.vue marketing-admin/src/main/resources/static/ui
git commit -m "feat(ui): ⑥ 运维大盘页与列表基座（-1/不适用绝不显示成 0）"
```

---

### Task 6: 在线配置页（⑤）

**Files**
- Create: `marketing-admin-ui/src/views/ConfigView.vue`
- Modify: `marketing-admin-ui/src/api/client.js`（如需导出查询串拼装 helper）
- Test: `marketing-admin-ui/test/config-view.spec.js`

**Interfaces**
- Consumes：`GET /api/admin/config` → `ConfigOverviewView{ownForm,entries[{form,rows[{cfgKey,cfgValue,source,...}]}],orphan[],unreported[],ignored[]}`；`PUT /api/admin/config`（body `cfgKey/form/value/remark`）、`DELETE /api/admin/config?cfgKey=&form=`、`POST /api/admin/config/rebroadcast`。角色：写仅 `admin`。

- [ ] **Step 1 写红的测试**

```js
// marketing-admin-ui/test/config-view.spec.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ConfigView from '@/views/ConfigView.vue'

function reply(code, data, message = '') {
  return vi.spyOn(global, 'fetch').mockResolvedValue({
    status: code === 0 ? 200 : 400,
    headers: { get: () => null },
    json: async () => ({ code, message, data, success: code === 0 }),
  })
}

beforeEach(() => localStorage.setItem('mkt.admin.token', 'T'))
afterEach(() => vi.restoreAllMocks())

const overview = {
  ownForm: 'LITE',
  entries: [{ form: 'LITE', rows: [
    { cfgKey: 'a', cfgValue: '1', source: 'FORM' },
    { cfgKey: 'b', cfgValue: '2', source: 'GLOBAL' },
    { cfgKey: 'c', cfgValue: '3', source: 'DEFAULT' },
  ] }],
  orphan: [], unreported: [], ignored: [],
}

describe('ConfigView', () => {
  it('三种来源三种标签：把 GLOBAL 与 DEFAULT 混成一色就等于看不出这值哪来的', async () => {
    reply(0, overview)
    const w = mount(ConfigView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(w.get('[data-src="FORM"]').text()).toContain('本档')
    expect(w.get('[data-src="GLOBAL"]').text()).toContain('全局')
    expect(w.get('[data-src="DEFAULT"]').text()).toContain('出厂')
  })

  it('41009 已落库未广播：界面必须出现"重新广播"，不能显示成成功', async () => {
    reply(0, overview)
    const w = mount(ConfigView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    reply(41009, null, '配置已落库但未广播')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-act="rebroadcast"]').exists()).toBe(true)
    expect(w.text()).not.toContain('保存成功')
  })

  it('40000 越界：表单不清空且原样显示后端 message（里面写着允许区间）', async () => {
    reply(0, overview)
    const w = mount(ConfigView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    reply(40000, null, '阈值必须在 [1, 100000] 之间')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-field="value"]').element.value).toBeTruthy()
    expect(w.text()).toContain('[1, 100000]')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/config-view.spec.js` → 三条 FAIL（视图不存在）
- [ ] **Step 2 实现**：按 form 分组的表 + "改值 / 删行（=恢复出厂）/ 重新广播"。删行必须二次确认，文案写"删掉这行后本档退回 yml 出厂值，不是删成 0"。
- [ ] **Step 3** `npx vitest run` 到绿；变异检查两处（把 `DEFAULT` 和 `GLOBAL` 渲染成同一标签 → 红；把 41009 的按钮条件删掉 → 红）。
- [ ] **Step 4** 浏览器实测：把 `seckill-route` 阈值改成 3/s，连打 `/api/seckill/activities` 看到 429，改回来（**收尾必须删掉覆盖行**，与 smoke 链路 5 同一纪律）。
- [ ] **Step 5** 构建 + `mvn -q install` + 提交 `feat(ui): ⑥ 在线配置页（③⑤：来源可辨、41009 有出口）`

---

### Task 7: 活动页（状态机 + 预算 + 灰度 + 41008）

**Files**
- Create: `marketing-admin-ui/src/views/ActivitiesView.vue`
- Create: `marketing-admin-ui/src/components/ConflictBar.vue`
- Test: `marketing-admin-ui/test/activities.spec.js`

**Interfaces**
- Consumes：`GET/POST /api/admin/activities`、`POST /api/admin/activities/{no}/transition?event=`、`PUT .../budget`、`PUT .../gray`（全部仅 `admin`）。`ActivityView` 带 `version`。

- [ ] **Step 1 写红的测试**

```js
// marketing-admin-ui/test/activities.spec.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ActivitiesView from '@/views/ActivitiesView.vue'

const reply = (code, data, message = '') =>
  vi.spyOn(global, 'fetch').mockResolvedValue({
    status: 200, headers: { get: () => null },
    json: async () => ({ code, message, data, success: code === 0 }),
  })

const page = { records: [{ activityNo: 'A1', name: 'n', status: 'AUDITING', version: 7,
  budgetAmount: 100, grayPercent: null }], total: 1, page: 1, size: 20 }

beforeEach(() => localStorage.setItem('mkt.admin.token', 'T'))
afterEach(() => vi.restoreAllMocks())

const mountView = () => {
  reply(0, page)
  const w = mount(ActivitiesView, { global: { plugins: [createPinia()] } })
  return w
}

describe('ActivitiesView', () => {
  it('改预算必须把 version 原样带上（不带 = 放弃乐观锁，后写覆盖先写）', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-budget"]').trigger('click')
    await w.get('[data-field="budgetAmount"]').setValue('101')
    reply(0, { ...page.records[0], budgetAmount: 101 })
    await w.get('[data-act="submit-budget"]').trigger('click')
    await flushPromises()
    const [, opt] = global.fetch.mock.calls.at(-1)
    expect(opt.url).toContain('/api/admin/activities/A1/budget')
    expect(JSON.parse(opt.body).version).toBe(7)
  })

  it('41008 要给 ConflictBar 与"重新加载并对比"，不能只弹一个 toast', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-budget"]').trigger('click')
    reply(41008, null, '他人已更新该活动，请重新加载')
    await w.get('[data-act="submit-budget"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="conflict-bar"]').exists()).toBe(true)
    expect(w.find('[data-act="reload-diff"]').exists()).toBe(true)
  })

  it('状态机按钮按当前 status 裁剪，但非法流转的 41001 文案仍要原样显示', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('AUDITING')
    expect(w.find('[data-event="APPROVE"]').exists()).toBe(true)
    expect(w.find('[data-event="SUBMIT"]').exists()).toBe(false)
    reply(41001, null, 'AUDITING 不能直接到 ONLINE')
    await w.get('[data-event="APPROVE"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('AUDITING 不能直接到 ONLINE')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/activities.spec.js` → FAIL（视图不存在）
- [ ] **Step 2 实现**（表单字段：`activityNo/name/status/budgetAmount/grayPercent/grayWhitelist/起止时间`；灰度表单下方写死一句提示："灰度只写 DB，5s 内由 activity 回源生效，不会立刻反映在缓存读数里"）。
- [ ] **Step 3** 测试到绿 + 变异检查（去掉 `version` 透传 → 红；把 41008 当普通错误弹 toast → 红）。
- [ ] **Step 4** 浏览器实测一次真写：给 smoke 建的临时活动改预算 +1 元 → 列表看到新值 → `GET /api/activity/{no}/budget/remain`（C 端）与后台一致。**这一步是本段唯一"改到共享数据"的动作，做完把改动记进执行记录**（便于对照下一轮冒烟）。
- [ ] **Step 5** 构建 + 回归 + 提交 `feat(ui): ⑥ 活动页（乐观锁撞号给出口，不当成保存失败）`

---

### Task 8: 券模板 / 优惠规则 / 秒杀活动三页

**Files**
- Create: `marketing-admin-ui/src/views/CouponsView.vue`
- Create: `marketing-admin-ui/src/views/RulesView.vue`
- Create: `marketing-admin-ui/src/views/SeckillView.vue`
- Test: `marketing-admin-ui/test/resource-views.spec.js`

**Interfaces**
- Consumes：`GET/POST /api/admin/coupon/templates`、`PUT /api/admin/coupon/templates/{no}/stock`、`PUT .../{no}/status`；`GET/POST /api/admin/discount/rules`（upsert，`status` 在 body）；`GET/POST /api/admin/seckill/activities`、`PUT /api/admin/seckill/activities/{no}/stock`、`PUT .../{no}/status`。全部仅 `admin`。**三条 URL 前缀形状互不相同**（spec §1），每页顶部注释写死自己的端点常量。

- [ ] **Step 1 写红的测试**

```js
// marketing-admin-ui/test/resource-views.spec.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import CouponsView from '@/views/CouponsView.vue'
import RulesView from '@/views/RulesView.vue'
import SeckillView from '@/views/SeckillView.vue'

const reply = (code, data, message = '') =>
  vi.spyOn(global, 'fetch').mockResolvedValue({
    status: 200, headers: { get: () => null },
    json: async () => ({ code, message, data, success: code === 0 }),
  })
const asPage = (records) => ({ records, total: records.length, page: 1, size: 20 })
const opts = { global: { plugins: [createPinia()] } }
beforeEach(() => localStorage.setItem('mkt.admin.token', 'T'))
afterEach(() => vi.restoreAllMocks())

describe('三页的端点与口径', () => {
  it('券改库存低于已发数：40000 原样显示且表单不清空', async () => {
    reply(0, asPage([{ templateNo: 'CT2026001', stockTotal: 100, granted: 80, status: 'ONLINE' }]))
    const w = mount(CouponsView, opts)
    await flushPromises()
    await w.get('[data-act="edit-stock"]').trigger('click')
    await w.get('[data-field="stockTotal"]').setValue('10')
    reply(40000, null, '库存不能低于已发放数量 80')
    await w.get('[data-act="submit-stock"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已发放数量 80')
    expect(w.get('[data-field="stockTotal"]').element.value).toBe('10')
  })

  it('秒杀非 ONLINE 改库存必须写"不会重建分桶"（否则运营以为票放出来了）', async () => {
    reply(0, asPage([{ activityNo: 'SK2026001', status: 'OFFLINE', totalStock: 5000, soldStock: 0 }]))
    const w = mount(SeckillView, opts)
    await flushPromises()
    await w.get('[data-act="edit-stock"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('不会重建分桶')
    expect(global.fetch.mock.calls.at(-1)[0]).toContain('/api/admin/seckill/activities/SK2026001/stock')
  })

  it('规则的新建与启停是同一条 upsert（不是两个端点）', async () => {
    reply(0, asPage([{ ruleNo: 'R1', status: 'ON' }]))
    const w = mount(RulesView, opts)
    await flushPromises()
    await w.get('[data-act="toggle"]').trigger('click')
    await flushPromises()
    const [url, opt] = global.fetch.mock.calls.at(-1)
    expect(url).toBe('/api/admin/discount/rules')
    expect(opt.method).toBe('POST')
    expect(JSON.parse(opt.body).status).toBe('OFF')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/resource-views.spec.js` → FAIL（三个视图不存在）
- [ ] **Step 2 实现三页**（复用 `PagedTable`；每页一个抽屉表单）。
- [ ] **Step 3** 测试到绿 + 变异检查（把非 ONLINE 的提示条件去掉 → 红）。
- [ ] **Step 4** 浏览器实测：给 `SK2026001` 改一次总库存（**ONLINE 态**，看分桶真的重建：`GET /api/seckill/stock/SK2026001` 余量随之变），再把 `ACT`/规则各建一条演示数据；改完把库存复位到 5000 并跑一次 `./scripts/reset-demo-data.sh`，不给下一轮冒烟留极端值。
- [ ] **Step 5** 构建 + 回归 + 提交 `feat(ui): ⑥ 券/规则/秒杀三页（端点不约定式拼装 + 非 ONLINE 改库存的口径写进界面）`

---

### Task 9: 重预热页 + 账号与会话页

**Files**
- Create: `marketing-admin-ui/src/views/CacheView.vue`
- Create: `marketing-admin-ui/src/views/UsersView.vue`
- Create: `marketing-admin-ui/src/views/SessionsView.vue`
- Test: `marketing-admin-ui/test/reheat.spec.js`

**Interfaces**
- Consumes：`GET /api/admin/cache/types`、`POST /api/admin/cache/reheat?type=&key=&force=true`、`GET /api/admin/cache/reheat/ack?type=&id=`（`admin|operator`）；`GET /api/admin/users`、`GET /users/{id}`、`PUT /users/{id}/status`（仅 admin）；`GET /api/admin/sessions?mine=`、`DELETE /sessions/{jti}`（不带 `mine` 仅 admin；踢人仅 admin）。

- [ ] **Step 1 写红的测试**

```js
// marketing-admin-ui/test/reheat.spec.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CacheView from '@/views/CacheView.vue'
import UsersView from '@/views/UsersView.vue'
import { useSession } from '@/stores/session'

const seq = (...bodies) => {
  let i = 0
  return vi.spyOn(global, 'fetch').mockImplementation(async () => {
    const b = bodies[Math.min(i, bodies.length - 1)]
    i += 1
    return { status: 200, headers: { get: () => null },
      json: async () => (typeof b === 'function' ? b() : b) }
  })
}
const ok = (data) => ({ code: 0, message: 'OK', data, success: true })
const err = (code, message) => ({ code, message, data: null, success: false })

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem('mkt.admin.token', 'T')
})
afterEach(() => vi.restoreAllMocks())

describe('重预热与账号面', () => {
  it('DISPATCHED 显示"已投递、等回执"，轮到 DONE 才改口（既不算成功也不算失败）', async () => {
    seq(ok(['budget']),
      ok({ status: 'DISPATCHED', type: 'budget', key: 'A1', id: 'r-1' }),
      ok({ status: 'DISPATCHED' }),
      ok({ status: 'DONE', type: 'budget', key: 'A1', id: 'r-1', after: 5100 }))
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-field="key"]').setValue('A1')
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已投递')
    expect(w.text()).not.toContain('成功')
    vi.advanceTimersByTime(4000)
    await flushPromises()
    expect(w.text()).toContain('5100')
    vi.useRealTimers()
  })

  it('41010 说清"本形态没有认领这个 type 的进程"，而不是"请求失败"', async () => {
    seq(ok(['budget']), err(41010, '本形态没有注册 budget 的重预热消费者'))
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('没有')
    expect(w.text()).toContain('budget')
  })

  it('operator 能看到重预热，看不到"停用账号"（后端已判，前端只是省事）', async () => {
    const s = useSession()
    s.me = { role: 'operator' }
    expect(s.canOperate).toBe(true)
    expect(s.canWrite).toBe(false)
    seq(ok([]), ok({ records: [], total: 0, page: 1, size: 20 }))
    const w = mount(UsersView)
    await flushPromises()
    expect(w.text()).not.toContain('停用')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/reheat.spec.js` → FAIL（视图不存在）
- [ ] **Step 2 实现**：轮询间隔 2s、上限 30s，超时后显示"仍待回执，点这里再查"——**不许静默放弃**（③ 的回执有 10 分钟 TTL，静默会让人以为丢了）。
- [ ] **Step 3** 测试到绿 + 变异检查（把 `DISPATCHED` 当成功 → 红）。
- [ ] **Step 4** 浏览器实测：FULL 容器档下跑一次 `type=budget` 的重预热，看它从 `DISPATCHED` 走到 `DONE` 并显示 owning 服务回写的 `after`（这是 ③ 跨进程回执第一次有界面可看）。
- [ ] **Step 5** 构建 + 回归 + 提交 `feat(ui): ⑥ 重预热与账号会话页（DISPATCHED 不是成功也不是失败）`

---

### Task 10: 审计页 + 导航与角色收尾

**Files**
- Create: `marketing-admin-ui/src/views/AuditsView.vue`
- Modify: `marketing-admin-ui/src/AppLayout.vue`（导航顺序、退出、倒计时到 60s 的提醒）
- Test: `marketing-admin-ui/test/audits.spec.js`

- [ ] **Step 1 写红的测试**

```js
// marketing-admin-ui/test/audits.spec.js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AuditsView from '@/views/AuditsView.vue'

const reply = (data) =>
  vi.spyOn(global, 'fetch').mockResolvedValue({
    status: 200, headers: { get: () => null },
    json: async () => ({ code: 0, message: 'OK', data, success: true }),
  })

beforeEach(() => localStorage.setItem('mkt.admin.token', 'T'))
afterEach(() => vi.restoreAllMocks())

const page = (records, total) => ({ records, total, page: 1, size: 20 })

describe('AuditsView', () => {
  it('四个过滤参数的名字与后端逐字一致（前端不许改名：改了就是静默丢掉过滤条件）', async () => {
    reply(page([], 0))
    const w = mount(AuditsView)
    await flushPromises()
    await w.get('[data-field="action"]').setValue('activity.transition')
    await w.get('[data-field="resourceId"]').setValue('ACT-X')
    await flushPromises()
    const url = global.fetch.mock.calls.at(-1)[0]
    expect(url).toContain('action=activity.transition')
    expect(url).toContain('resourceId=ACT-X')
    expect(url).not.toMatch(/resource_id|actionType=/)
  })

  it('显示 total 且翻到第 2 页不会顺手再打一次第 1 页', async () => {
    reply(page([{ id: 1 }], 41))
    const w = mount(AuditsView)
    await flushPromises()
    const before = global.fetch.mock.calls.length
    await w.get('[data-act="next-page"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('41')
    expect(global.fetch.mock.calls.length).toBe(before + 1)
    expect(global.fetch.mock.calls.at(-1)[0]).toContain('page=2')
  })
})
```

Run: `cd marketing-admin-ui && npx vitest run test/audits.spec.js` → FAIL（视图不存在）
- [ ] **Step 2 实现**：审计明细里的 `summary` 已脱敏（③），界面原样显示，**不做**任何"猜字段再拼一次"的解读。
- [ ] **Step 3** 测试到绿 + 变异检查。
- [ ] **Step 4** 浏览器实测：跑一轮 `smoke-test.sh` 后进审计页，按本轮 `activityNo` 过滤应查得到那条 `activity.transition`——**这条断言把"界面看到的东西"与"冒烟验过的东西"对上了**，是本段的第二层证据。
- [ ] **Step 5** 构建 + 回归 + 提交 `feat(ui): ⑥ 审计页与导航收尾`

---

### Task 11: dist 一致性门禁补全

**Files**
- Create: `scripts/check-ui-dist.sh`
- Modify: `marketing-admin/src/test/java/com/example/marketing/admin/web/UiDistIntegrityTest.java`
- Modify: `README.md`（把"唯一构建入口"写清）

**Interfaces**
- Produces：`scripts/check-ui-dist.sh` 退出码（0 一致 / 1 不一致 / 2 jar 不存在）。

- [ ] **Step 1 加红的测试断言**（同一类，不新建）：解析 `index.html` 里所有 `/ui/assets/**` 引用（js 与 css 与 preload 链接），逐个断言 classpath 存在；再断言 `static/ui/**` 下**没有孤儿文件**（存在 `assets/*.js` 却没被索引页或其 chunk 引用图引用）——孤儿来自 `emptyOutDir` 没生效，而它的症状是"jar 里躺着一份没人用的旧代码"。
- [ ] **Step 2 实现 shell 门禁**：

```bash
#!/usr/bin/env bash
# ============================================================
# ⑥ 的产物一致性：仓库里的 static/ui/** 必须与 jar 里逐项 sha256 相同。
#   dist 是入仓产物 → "改了源码没重构建"与"只改了产物"都表现为界面与代码不同步，
#   而且只在打包之后才看得见。仓库没有 CI（.github 都不存在），所以这道闸靠人跑，
#   README 与 spec §8 都写明了这一点：它是第三道，前两道是 build-ui.sh 与单测。
# 用法：./scripts/check-ui-dist.sh      （前提：mvn package 已跑过）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=$(ls marketing-admin/target/marketing-admin-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
  echo "!! 找不到 marketing-admin 的 jar：先 source scripts/common.sh && mvn -q -pl marketing-admin package" >&2
  exit 2
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -qq "$JAR" 'BOOT-INF/classes/static/ui/*' -d "$WORK" || {
  echo "!! jar 里没有 static/ui：⑥ 的产物没进包（检查 vite outDir）" >&2; exit 1; }

DRIFT=0
while IFS= read -r f; do
  rel=${f#"$WORK"/BOOT-INF/classes/}
  want=$(shasum -a 256 "$f" | cut -d' ' -f1)
  if [ ! -f "$rel" ]; then echo "!! jar 里有而仓库没有：$rel"; DRIFT=1; continue; fi
  got=$(shasum -a 256 "$rel" | cut -d' ' -f1)
  [ "$want" = "$got" ] || { echo "!! 内容不一致：$rel"; DRIFT=1; }
done < <(find "$WORK" -type f)
[ "$DRIFT" = "0" ] && echo "==> jar 与仓库的 static/ui 逐项一致" || echo "!! 重跑 ./scripts/build-ui.sh 并把产物一起提交" >&2
exit $DRIFT
```

- [ ] **Step 3 变异检查（这一步就是它的验收）**：`touch marketing-admin/src/main/resources/static/ui/index.html && echo x >> .../index.html` → 门禁必须非零退出；随后 `SKIP_INSTALL=1 ./scripts/build-ui.sh` 复原 → 退出 0。
- [ ] **Step 4** `source scripts/common.sh && mvn -q -pl marketing-admin test -Dtest=UiDistIntegrityTest` 到绿。
- [ ] **Step 5** 提交 `test(ui): ⑥ dist 一致性三道闸补全（无 CI 的仓库里唯一自动的一道）`

---

### Task 12: 冒烟链路 8 + 五形态口径收口 + README + 母版 §13

**Files**
- Modify: `scripts/smoke-test.sh`（新增链路 8，在链路 7 之后、收尾之前）
- Modify: `README.md`（⑥ 一节、API 表 `/ui/`、覆盖矩阵、计数、已知噪音）
- Modify: `docs/superpowers/specs/2026-09-23-admin-console-business-ops-ui-design.md`（§13 追加 ⑥ 偏离）
- Modify: `docs/superpowers/specs/2026-09-24-admin-spa-ui-design.md`（本段实测回写）

- [ ] **Step 1 链路 8（3 条，形态无关）**

```bash
head2 "链路 8：后台界面（加了界面 ≠ 加了口子；缓存头错了会让人跑到旧索引上）"
UIROOT="${GW}/ui/"
R=$(curl -s -m 15 -o /tmp/mkt-smoke-ui.html -w '%{http_code}' "$UIROOT")
[ "$R" = "200" ] && ok "后台首页可打开（200，无需任何 token）" || bad "后台首页 HTTP $R" "$(head -c 200 /tmp/mkt-smoke-ui.html)"
HDRS=$(curl -sI -m 15 "$UIROOT" | tr -d '\r')
echo "$HDRS" | grep -qi 'cache-control: no-store' && ok "索引页 no-store（旧索引 + 新指纹 = 白屏）" \
  || bad "索引页没给 no-store" "$(echo "$HDRS" | grep -i cache-)"
ASSET=$(grep -o '/ui/assets/[^"]*\.js' /tmp/mkt-smoke-ui.html | head -1)
if [ -z "$ASSET" ]; then
  bad "索引页里没有指纹脚本（上一步的界面是空壳或 base 漂了）" ""
else
  expect "指纹资源给一年 immutable" 'cache-control: public, max-age=31536000, immutable' \
    "$(curl -sI -m 15 "$GW$ASSET" | tr -d '\r' | tr 'A-Z' 'a-z')"
fi
expect "界面是同源静态资源，不给未授权的数据通路" '"code":40100' \
  "$(curl -s -m 15 "$GW/api/admin/users")"
```

> 第 4 条只在 `ASSET` 取得到时才算，所以基数按实测写（LITE/dev 92 或 93）。**别为了凑数把空针断言留下**——`expect` 拒空针，但 `grep` 型的会静默。

- [ ] **Step 2** `bash -n scripts/smoke-test.sh`；LITE 真跑，逐条看红。
- [ ] **Step 3 整趟真浏览器旅程（spec §9 的第二层证据，不可选）**

用 `browser-use` 在 LITE 上走一遍，每一步截图存档到 `docs/superpowers/evidence/⑥/`：
① 打开 `http://127.0.0.1:8090/ui/` → 未登录被弹到 `/ui/login` 且地址栏带回 `?next=`；
② 用 `admin/rootdev123` 登录 → 落到大盘，页脚角色显示 `admin`、倒计时在走；
③ 依次点开 10 个导航项，**每页都要有数据而不是空表**（空表要么真没数据、要么端点错了，逐条对着 curl 判）；
④ 做一次真写：活动页给一条 `ACT-SMOKE-*` 改预算 +1 元 → 列表新值 → 用 C 端 `GET /api/activity/{no}/budget/remain` 对上同一个数；
⑤ 重预热页对 `type=budget` 那一行按一次，LITE 下应立刻 `DONE`（换 FULL 容器档则应看到 `DISPATCHED → DONE`）；
⑥ 会话页把自己的会话踢掉 → 下一发请求必须回登录页且**不自动重放**（对应 `40102`）；
⑦ 退出后直接改地址栏去 `/ui/audits` → 回登录。
任何一步不一致：停下来修到一致，再往下走。截图不是装饰，是"界面说的与 curl 说的是同一件事"的证据。

- [ ] **Step 4 五形态口径**：A LITE、D dev 必跑（同一份 dist，验的是"两种装配下静态资源都在"）；C FULL 容器跑一次（唯一验 `lb://marketing-admin` 那条 ui-route 的机会）；B FULL 进程按需。**每格记录**：断言数、`/ui/` 的 HTTP 与两个缓存头、`docker stats` 与 dist 的 KiB。
- [ ] **Step 5 README**：新增"### 7. 后台界面（⑥）"（同源为什么不能放开、三个门禁哪道是自动的、构建入口与"改了 .vue 必须重跑"）；API 表加 `GET /ui/**`；覆盖矩阵加一格；计数刷新（单测类数/用例数、断言基数、七条→八条链路）。
- [ ] **Step 6 母版 §13 追加 ⑥ 偏离**（至少：`AdminAuthFilter` 在网关且验签而非"admin 侧读头"；`applyTo`/`shouldFallbackToIndex` 从配置类里剥成纯函数（MVC 上下文不值得为一个 if 起）；CSP 与缓存头由 filter 独占、`ResourceHandlerRegistry.setCacheControl` 因此**不能用**（它会把指纹资源的 immutable 覆盖成 no-store）；vitest 是本段新增的第二套测试运行时、spec 没写；jar 体积实测值；FULL 两档只验 `ui-route` 可达不重跑全冒烟）。
- [ ] **Step 7 提交**：`docs: ⑥ 口径收口（后台界面一节 + 三道门禁 + 五形态 /ui 实测记录）`

---

## 执行记录（实施时逐条填，数字必须来自命令输出）

| 任务 | 交付 | 新增用例 | 实测 |
|---|---|---|---|
| — | — | — | — |

## 落地时对计划的修正

（实施时逐条追加）
