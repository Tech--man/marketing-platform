import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'

// T4 会把路由表换成"登录页 + AppLayout + 十个业务页"。
// 基座用 history：回退由 admin 侧的 ResourceHandler 负责（spec §5），网关只按 /ui/** 转发。
const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [{ path: '/', name: 'home', component: HomeView }],
})

export default router
