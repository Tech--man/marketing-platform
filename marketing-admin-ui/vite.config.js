import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 前后端分离（2026-10-01）：产物不再入仓、不再进后端 jar——输出到 vite 默认的
// dist/（gitignore），由 marketing-web 的多阶段 Dockerfile 在镜像内构建并经
// nginx 以 /ui/ 承载。本地预览用 npm run dev（5173，dev proxy 保持同源零 CORS）。
export default defineConfig({
  base: '/ui/',
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: 'dist',
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
