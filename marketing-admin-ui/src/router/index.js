import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '@/stores/session'
import AppLayout from '@/AppLayout.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'

/**
 * 路由表。⑥ 的页面清单在 spec §6，这里一个任务加一条子路由（T5 大盘、T6 配置…）。
 *
 * <p>基座用 history，所以 {@code /ui/audits} 直接刷新也要能开——回退由 admin 侧的
 * {@code UiWebMvcConfig} 负责，网关只按 {@code /ui/**} 转发，不懂前端路由。</p>
 */
const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: AppLayout,
    children: [{ path: '', name: 'home', component: HomeView }],
  },
]

const router = createRouter({ history: createWebHistory('/ui/'), routes })

/**
 * 守卫只做两件事：没凭证去登录、身份没取过去取一次。
 * 它**不是**权限模型——每个写端点自己会回 40300，这里的 `roles` 只决定菜单露不露。
 */
router.beforeEach(async (to) => {
  const s = useSession()
  if (to.meta.public) return true
  if (!s.authed) return { name: 'login', query: { next: to.fullPath } }
  if (!s.me) {
    try {
      await s.whoAmI()
    } catch (e) {
      // 吊销：不带 next。那一发可能已经落库，回原页会诱导人再点一次
      if (e.action === 'login-strict') return { name: 'login' }
      return { name: 'login', query: { next: to.fullPath } }
    }
  }
  if (to.meta.roles && !to.meta.roles.includes(s.role)) return { name: 'home' }
  return true
})

export default router
