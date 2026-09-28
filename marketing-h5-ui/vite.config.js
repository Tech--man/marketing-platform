import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// C 端 H5：产物直接落进 admin 的 classpath（与 ⑥ 的 /ui 同一套"构建即落位、
// 产物入仓"的口径）。base=/h5/ 让网关的 /h5/** 路由与资源前缀对齐；
// outDir 指到 static/h5，emptyOutDir 防上一版指纹资源残留把包撑大却界面没变。
export default defineConfig({
  base: '/h5/',
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: '../marketing-admin/src/main/resources/static/h5',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    // 同源约束：仓库对 C 端零 CORS 配置，dev 走代理即可，放开跨域等于多开一个口子。
    proxy: { '/api': 'http://127.0.0.1:8090' },
  },
  test: { environment: 'jsdom' },
})
