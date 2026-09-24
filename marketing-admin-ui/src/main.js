import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 组件与 API 由 vite.config.js 里的 unplugin 按需引入，所以这里**不**注册全局 ElementPlus、
// 也**不**引整包 CSS ——两者都会把 360 KB 的 CSS 与整个组件库塞进首屏，
// 而这份 dist 是入仓产物、直接进常态服役档 LITE 的 jar。
// 例外是命令式调用的那几个（ElMessage/ElMessageBox）：resolver 管不到它们，样式得手动引。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).mount('#app')
