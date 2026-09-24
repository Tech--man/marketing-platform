import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '@/stores/session'
import { buildRoutes } from './routes'

const router = createRouter({ history: createWebHistory('/ui/'), routes: buildRoutes() })

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
  if (to.meta.roles && !to.meta.roles.includes(s.role)) return { name: 'ops' }
  return true
})

export default router
