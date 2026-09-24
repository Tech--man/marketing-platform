import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// outDir 直接指向 admin 的 classpath：⑥ 的产物是**入仓文件**，构建即落位，
// 不需要 maven 去装 node（spec §3 / §10）。
// emptyOutDir 必须开：留着上一次的 assets/*.js，index.html 就不引用它们了，
// 但 jar 里那一层还在——症状是"包越来越大而界面没变"。
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
    // 同源约束由这里满足：仓库零 CORS 配置，放开跨域等于给后台多开一个口子。
    proxy: { '/api': 'http://127.0.0.1:8090' },
  },
  test: {
    environment: 'jsdom',
    // 视图用了 <el-icon> 等按需组件，unplugin 的 resolver 会注入 element-plus 的
    // style/css 副作用（它 import theme-chalk/*.css）。vitest 默认把 element-plus
    // 外部化交给 Node，Node 不认 .css 扩展名 → "Unknown file extension .css"。
    // 内联后交给 vite 处理（css 被桩掉），组件测试才跑得动。
    server: { deps: { inline: ['element-plus'] } },
  },
})
