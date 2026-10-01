import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// C 端 H5：前后端分离（2026-10-01）后产物输出到 vite 默认 dist/（gitignore），
// 由 marketing-web 镜像内构建并经 nginx 以 /h5/ 承载。base=/h5/ 让网关的
// /h5/** 路由与资源前缀对齐；本地预览用 npm run dev（5174，dev proxy 保持同源）。
export default defineConfig({
  base: '/h5/',
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    // 同源约束：仓库对 C 端零 CORS 配置，dev 走代理即可，放开跨域等于多开一个口子。
    proxy: { '/api': 'http://127.0.0.1:8090' },
  },
  test: { environment: 'jsdom' },
})
