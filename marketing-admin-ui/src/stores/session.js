import { defineStore } from 'pinia'
import { api, SESSION_KEY } from '@/api/client'

const EXPIRES_KEY = 'mkt.admin.expiresAt'

/**
 * 后台会话。TTL 只能来自登录响应（`AdminProperties.accessTtlSeconds`，今天 900s）：
 * 前端自己写死一个数，就会在改配置后出现"界面说还有 5 分钟而后端已经拒了"。
 *
 * <p>token 存 localStorage 而不是 cookie：`Authorization: Bearer` 是网关唯一识别路径，
 * 换 cookie 要么动 ③ 的鉴权链要么加反代，而不写 cookie 就没有 CSRF。
 * 残余的 XSS 风险由 CSP + 900s TTL + 单会话吊销三层兜（spec §7）。</p>
 */
export const useSession = defineStore('session', {
  state: () => ({
    token: localStorage.getItem(SESSION_KEY) || '',
    expiresAt: Number(localStorage.getItem(EXPIRES_KEY) || 0),
    me: null,
  }),
  getters: {
    authed: (s) => !!s.token,
    role: (s) => (s.me ? s.me.role : ''),
    // 角色只用来灰化入口，不是安全边界——真正的判定在后端，每个写端点自己会回 40300。
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
    /**
     * 退出。后端调用失败也**必须**清本地：留着那枚 token 只会让下一发继续 40102，
     * 而用户已经点了"退出"。
     */
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
